using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccountSplit2FA
{
    internal sealed class OtpReplaceDialog : Form
    {
        private readonly AccountData original;
        private AccountData preview;
        private readonly Action<string> commit;
        private readonly TextBox replacementInput;
        private readonly Label newCode, parameters, feedback;
        private readonly ActionButton save, copyNew, upload;
        private readonly Timer timer;
        private long editVersion;

        public OtpReplaceDialog(HistoryEntry entry, Action<string> commit)
        {
            this.commit = commit; original = entry.Open();
            Text = "更换 2FA"; Font = Theme.Font(10, FontStyle.Regular); BackColor = Theme.Background;
            AutoScaleDimensions = new SizeF(96, 96); AutoScaleMode = AutoScaleMode.Dpi;
            FormBorderStyle = FormBorderStyle.FixedDialog; StartPosition = FormStartPosition.CenterParent;
            ShowInTaskbar = false; MinimizeBox = false; MaximizeBox = false;
            ClientSize = new Size(520, 468); Padding = new Padding(24); DoubleBuffered = true;
            TableLayoutPanel layout = Grid(1, 12); layout.Dock = DockStyle.Fill;
            foreach (int height in new int[] { 34, 24, 40, 12, 34, 8, 74, 8, 80, 38, 28, 40 }) layout.RowStyles.Add(new RowStyle(SizeType.Absolute, height));
            layout.Controls.Add(Label("更换 2FA", 18, Theme.Ink, true), 0, 0);
            layout.Controls.Add(Label("保留当前账号、密码和历史名称", 9, Theme.Muted, false), 0, 1);
            Label identity = Label(entry.DisplayName + (entry.Name.Length > 0 ? " · " + entry.DefaultDisplayName : ""), 10, Theme.Ink, true);
            identity.AutoEllipsis = true; layout.Controls.Add(identity, 0, 2);
            TableLayoutPanel imports = Grid(3, 1); imports.Dock = DockStyle.Fill;
            for (int i = 0; i < 3; i++) imports.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.333F));
            ActionButton oldCopy = Button("复制旧验证码", ButtonKind.Secondary);
            oldCopy.Click += delegate { Copy(original.Code(Totp.UnixNow()), "旧验证码已复制"); };
            ActionButton paste = Button("粘贴新密钥", ButtonKind.Secondary);
            paste.Click += delegate { try { if (Clipboard.ContainsText()) replacementInput.Text = Clipboard.GetText(); else Error("剪贴板中没有文本。"); } catch (ExternalException) { Error("剪贴板暂时不可用，请重试。"); } };
            upload = Button("上传二维码", ButtonKind.Secondary); upload.Click += delegate { Upload(); };
            imports.Controls.Add(oldCopy, 0, 0); imports.Controls.Add(paste, 1, 0); imports.Controls.Add(upload, 2, 0); layout.Controls.Add(imports, 0, 4);
            InputFrame inputFrame = new InputFrame { Dock = DockStyle.Fill, FillColor = Color.White, Padding = new Padding(12, 10, 12, 8) };
            replacementInput = new TextBox { Multiline = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, BorderStyle = BorderStyle.None,
                Font = new Font("Consolas", 10), MaxLength = 16384, AccessibleName = "新的2FA密钥或URI", BackColor = Color.White };
            inputFrame.Controls.Add(replacementInput); inputFrame.Watch(replacementInput); layout.Controls.Add(inputFrame, 0, 6);
            Card card = new Card { Dock = DockStyle.Fill, Padding = new Padding(14, 8, 14, 8), FillColor = Theme.Navy, BorderColor = Theme.Navy };
            TableLayoutPanel codes = Grid(2, 2); codes.Dock = DockStyle.Fill;
            codes.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); codes.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 110));
            codes.RowStyles.Add(new RowStyle(SizeType.Absolute, 22)); codes.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            parameters = Label("粘贴新密钥或导入二维码后预览", 8.5F, Color.FromArgb(174, 190, 216), false); parameters.AutoEllipsis = true;
            codes.Controls.Add(parameters, 0, 0); codes.SetColumnSpan(parameters, 2);
            newCode = Label("--- ---", 24, Color.White, true); newCode.Font = new Font("Consolas", 24, FontStyle.Bold); codes.Controls.Add(newCode, 0, 1);
            copyNew = Button("复制新验证码", ButtonKind.OnDark); copyNew.Margin = new Padding(0, 3, 0, 1);
            copyNew.Click += delegate { if (preview != null) Copy(preview.Code(Totp.UnixNow()), "新验证码已复制"); };
            codes.Controls.Add(copyNew, 1, 1); card.Controls.Add(codes); layout.Controls.Add(card, 0, 8);
            feedback = Label("纯密钥沿用当前参数；URI 采用新参数。", 8.5F, Theme.Muted, false); layout.Controls.Add(feedback, 0, 9);
            layout.Controls.Add(Label("确认服务端换绑成功后，再替换本机记录。", 8.5F, Theme.Muted, false), 0, 10);
            TableLayoutPanel actions = Grid(3, 1); actions.Dock = DockStyle.Fill;
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 96)); actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 128));
            ActionButton cancel = Button("取消", ButtonKind.Secondary); cancel.Click += delegate { Close(); };
            save = Button("保存替换", ButtonKind.Primary); save.Click += delegate { Save(); };
            actions.Controls.Add(cancel, 1, 0); actions.Controls.Add(save, 2, 0); layout.Controls.Add(actions, 0, 11); Controls.Add(layout);
            replacementInput.TextChanged += delegate { editVersion++; UpdatePreview(); };
            timer = new Timer { Interval = 200 }; timer.Tick += delegate { UpdateCode(); }; timer.Start();
            Shown += delegate { replacementInput.Focus(); }; UpdatePreview();
        }
        private void UpdatePreview()
        {
            if (preview != null) { preview.Dispose(); preview = null; }
            save.Enabled = copyNew.Enabled = false; newCode.Text = "--- ---";
            parameters.Text = "粘贴新的 Base32 密钥或 otpauth:// URI";
            feedback.ForeColor = Theme.Muted; feedback.Text = "纯密钥沿用当前参数；URI 采用新参数。";
            if (String.IsNullOrWhiteSpace(replacementInput.Text)) return;
            try {
                preview = original.ReplaceOtp(replacementInput.Text);
                parameters.Text = "新验证码 · " + preview.Algorithm + " · " + preview.Digits + " 位 · " + (preview.OtpKind == OtpKind.Hotp ? "HOTP counter=" + preview.Counter : preview.Period + " 秒");
                save.Enabled = copyNew.Enabled = true; UpdateCode();
            } catch (FormatException error) { Error(error.Message); }
        }
        private void UpdateCode() { if (preview != null) { string value = preview.Code(Totp.UnixNow()); newCode.Text = value.Insert(value.Length / 2, " "); } }
        private void Save()
        {
            if (preview == null || !save.Enabled) return;
            try { commit(replacementInput.Text); DialogResult = DialogResult.OK; Close(); }
            catch (InvalidOperationException error) { Error(error.Message); }
            catch (Exception error) { if (!MainForm.HistoryFailure(error)) throw; Error("更换失败，原记录已保留。请重试。"); }
        }
        private void Upload()
        {
            using (OpenFileDialog picker = new OpenFileDialog { Title = "选择新的 2FA 二维码", Filter = "图片文件|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.tif;*.tiff|所有文件|*.*" }) {
                if (picker.ShowDialog(this) != DialogResult.OK) return;
                replacementInput.Clear(); long version = ++editVersion; string path = picker.FileName; upload.Enabled = false;
                feedback.Text = "正在识别新的二维码…";
                Task.Factory.StartNew(() => QrImageReader.ReadFile(path)).ContinueWith(task => {
                    if (IsDisposed || !IsHandleCreated) return;
                    try { BeginInvoke((Action)delegate {
                        upload.Enabled = true; if (version != editVersion) return;
                        if (task.IsFaulted) { Error(task.Exception.GetBaseException() is FormatException ? task.Exception.GetBaseException().Message : "无法读取图片，请重试。"); return; }
                        if (task.Result.Count == 1) replacementInput.Text = task.Result[0];
                        else {
                            ContextMenuStrip choices = new ContextMenuStrip();
                            foreach (string text in task.Result) { string selected = text; choices.Items.Add(QrImageReader.Describe(text), null, delegate { replacementInput.Text = selected; }); }
                            choices.Closed += delegate { choices.Dispose(); }; choices.Show(upload, new Point(0, upload.Height));
                        }
                    }); } catch (InvalidOperationException) { }
                }, TaskScheduler.Default);
            }
        }
        private void Copy(string value, string message) { try { Clipboard.SetText(value); feedback.Text = message; feedback.ForeColor = Theme.Muted; } catch (ExternalException) { Error("剪贴板暂时不可用，请重试。"); } }
        private void Error(string message) { feedback.Text = message; feedback.ForeColor = Color.FromArgb(197, 64, 77); }
        protected override bool ProcessDialogKey(Keys keyData) { if (keyData == Keys.Escape) { Close(); return true; } return base.ProcessDialogKey(keyData); }
        protected override void Dispose(bool disposing) { if (disposing) { if (timer != null) timer.Dispose(); if (preview != null) preview.Dispose(); original.Dispose(); } base.Dispose(disposing); }
        private static TableLayoutPanel Grid(int columns, int rows) {
            TableLayoutPanel result = new TableLayoutPanel { ColumnCount = columns, RowCount = rows, Margin = new Padding(0), BackColor = Color.Transparent };
            if (columns == 1) result.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); if (rows == 1) result.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); return result;
        }
        private static Label Label(string text, float size, Color color, bool bold) { return new Label { Text = text, Font = Theme.Font(size, bold ? FontStyle.Bold : FontStyle.Regular), ForeColor = color, Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft, Margin = new Padding(0), BackColor = Color.Transparent, UseMnemonic = false }; }
        private static ActionButton Button(string text, ButtonKind kind) { return new ActionButton { Text = text, AccessibleName = text, Kind = kind, Font = Theme.Font(9, FontStyle.Regular), Dock = DockStyle.Fill, Margin = new Padding(0, 0, 6, 0) }; }
    }
}
