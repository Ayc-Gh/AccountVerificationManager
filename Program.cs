using System;
using System.IO;
using System.Security.Cryptography;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace AccountSplit2FA
{
    internal static class Program
    {
        [DllImport("user32.dll")] private static extern bool SetProcessDPIAware();
        [STAThread] private static void Main(string[] args)
        {
            try { SetProcessDPIAware(); } catch (EntryPointNotFoundException) { }
            Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
            string historyFile = args.Length == 2 && args[0] == "--history-file" ? args[1] : null;
            Application.Run(new MainForm(historyFile));
        }
    }
    internal static class Theme
    {
        public static readonly Color Background = Color.FromArgb(244, 247, 251), Ink = Color.FromArgb(27, 40, 62),
            Muted = Color.FromArgb(118, 132, 153), Blue = Color.FromArgb(58, 104, 235), Line = Color.FromArgb(224, 231, 241),
            Surface = Color.FromArgb(248, 250, 253), Navy = Color.FromArgb(25, 40, 68);
        public static Font Font(float size, FontStyle style) { return new Font("Microsoft YaHei UI", size, style); }
    }
    public class MainForm : Form
    {
        private TextBox input, account, password, secret, manualSeparator;
        private Label status, codeLabel, countdown, resultBadge, autoHint, placeholder;
        private ActionButton accountCopy, passwordCopy, secretCopy, codeCopy, allCopy, autoMode, manualMode;
        private Panel manualOptions;
        private CheckBox hidePassword;
        private ProgressTrack progress;
        private Timer timer;
        private AccountData data;
        private bool automatic = true, transientStatus;
        private long lastStep = -1;
        private DateTime statusUntil;
        private readonly HistoryStore history;
        private TableLayoutPanel shell;
        private Card historyCard;
        private FlowLayoutPanel historyList;
        private Label historyCount;
        private ActionButton historyToggle, clearHistory;
        private Guid selectedHistory = Guid.Empty;
        private bool historyExpanded = true;

        public MainForm() : this(null) { }
        public MainForm(string historyFile)
        {
            history = new HistoryStore(historyFile);
            Text = "账号验证管家 v1.4";
            historyExpanded = SystemInformation.WorkingArea.Width >= 1240;
            ClientSize = new Size(historyExpanded ? Math.Min(1300, SystemInformation.WorkingArea.Width - 40) : 1020, 708);
            MinimumSize = new Size(historyExpanded ? 1230 : 950, 730);
            StartPosition = FormStartPosition.CenterScreen; BackColor = Theme.Background;
            Font = Theme.Font(10, FontStyle.Regular); AutoScaleMode = AutoScaleMode.Dpi; DoubleBuffered = true;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            TableLayoutPanel root = Table(1, 5); root.Dock = DockStyle.Fill; root.Padding = new Padding(28, 18, 28, 14);
            foreach (int height in new int[] { 76, 260, 18 }) root.RowStyles.Add(new RowStyle(SizeType.Absolute, height));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); root.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
            shell = Table(2, 1); shell.Dock = DockStyle.Fill;
            shell.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            shell.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, historyExpanded ? 280 : 0));
            Controls.Add(shell); shell.Controls.Add(root, 0, 0);
            root.Controls.Add(CreateHeader(), 0, 0); root.Controls.Add(CreateInputCard(), 0, 1);
            TableLayoutPanel resultLayout = Table(3, 1); resultLayout.Dock = DockStyle.Fill;
            resultLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 61));
            resultLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 18));
            resultLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 39));
            resultLayout.Controls.Add(CreateResultCard(), 0, 0); resultLayout.Controls.Add(CreateCodeCard(), 2, 0);
            root.Controls.Add(resultLayout, 0, 3); root.Controls.Add(CreateFooter(), 0, 4);
            historyCard = CreateHistoryCard(); historyCard.Visible = historyExpanded;
            shell.Controls.Add(historyCard, 1, 0);
            KeyPreview = true;
            KeyDown += delegate(object sender, KeyEventArgs e) {
                if (e.Control && e.KeyCode == Keys.Enter) { ParseInput(); e.SuppressKeyPress = true; }
                if (e.KeyCode == Keys.Escape) { ClearInput(); e.SuppressKeyPress = true; }
            };
            Shown += delegate { input.Focus(); };
            timer = new Timer { Interval = 200 };
            timer.Tick += delegate {
                placeholder.Visible = input.TextLength == 0;
                UpdateCode();
                if (transientStatus && DateTime.UtcNow > statusUntil) SetIdleStatus();
            };
            ResetResult(); SetIdleStatus();
            try { history.Load(); } catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("历史记录无法读取，原文件已保留。可清空历史后重试。", true); }
            RefreshHistory(); timer.Start();
            FormClosed += delegate { timer.Stop(); timer.Dispose(); ResetResult(); input.Clear(); };
        }
        private Control CreateHeader()
        {
            TableLayoutPanel header = Table(3, 1); header.Dock = DockStyle.Fill;
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 58)); header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 130));
            header.Controls.Add(new BrandMark { Size = new Size(44, 44), Margin = new Padding(0, 3, 0, 0) }, 0, 0);
            TableLayoutPanel titles = Table(1, 2); titles.Dock = DockStyle.Fill;
            titles.RowStyles.Add(new RowStyle(SizeType.Absolute, 36)); titles.RowStyles.Add(new RowStyle(SizeType.Absolute, 25));
            titles.Controls.Add(Label("账号验证管家", 22, FontStyle.Bold, Theme.Ink), 0, 0);
            titles.Controls.Add(Label("登录信息与动态验证码，一次整理。", 9.5F, FontStyle.Regular, Theme.Muted), 0, 1);
            header.Controls.Add(titles, 1, 0);
            historyToggle = Button("历史记录 0/10", ButtonKind.Secondary, 128);
            historyToggle.Height = 34; historyToggle.Anchor = AnchorStyles.Right | AnchorStyles.Top;
            historyToggle.Margin = new Padding(0, 8, 0, 0);
            historyToggle.Click += delegate { ToggleHistory(); };
            header.Controls.Add(historyToggle, 2, 0); return header;
        }
        private Control CreateInputCard()
        {
            Card card = new Card { Dock = DockStyle.Fill, Padding = new Padding(20, 16, 20, 16) };
            TableLayoutPanel content = Table(1, 4); content.Dock = DockStyle.Fill;
            content.RowStyles.Add(new RowStyle(SizeType.Absolute, 34)); content.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
            content.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); content.RowStyles.Add(new RowStyle(SizeType.Absolute, 52));
            TableLayoutPanel heading = Table(2, 1); heading.Dock = DockStyle.Fill;
            heading.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); heading.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 190));
            heading.Controls.Add(Label("粘贴原始内容", 12.5F, FontStyle.Bold, Theme.Ink), 0, 0);
            Label hint = Label("账号 / 密码 / 2FA 密钥", 9, FontStyle.Regular, Theme.Muted); hint.TextAlign = ContentAlignment.MiddleRight;
            heading.Controls.Add(hint, 1, 0); content.Controls.Add(heading, 0, 0);
            TableLayoutPanel options = Table(3, 1); options.Dock = DockStyle.Fill;
            options.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 240)); options.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 18));
            options.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            Card switcher = new Card { Dock = DockStyle.Fill, FillColor = Color.FromArgb(239, 243, 249), BorderColor = Color.FromArgb(239, 243, 249),
                Radius = 9, Padding = new Padding(3), Margin = new Padding(0, 0, 0, 6) };
            TableLayoutPanel switches = Table(2, 1); switches.Dock = DockStyle.Fill;
            switches.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50)); switches.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            autoMode = Button("自动识别", ButtonKind.Selected, 114); autoMode.Dock = DockStyle.Fill; autoMode.Height = 30;
            autoMode.AccessibleName = "自动识别分隔符"; autoMode.Click += delegate { SetSeparatorMode(true); };
            manualMode = Button("手动设置", ButtonKind.Ghost, 114); manualMode.Dock = DockStyle.Fill; manualMode.Height = 30;
            manualMode.AccessibleName = "手动设置分隔符"; manualMode.Click += delegate { SetSeparatorMode(false); };
            switches.Controls.Add(autoMode, 0, 0); switches.Controls.Add(manualMode, 1, 0); switcher.Controls.Add(switches);
            options.Controls.Add(switcher, 0, 0);
            Panel modeDetails = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent };
            autoHint = Label("连续 ≥3 个符号 · 支持 ---、===、||| 等", 9, FontStyle.Regular, Theme.Muted);
            manualOptions = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent, Visible = false };
            TableLayoutPanel manualLayout = Table(3, 1); manualLayout.Dock = DockStyle.Fill;
            manualLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 64)); manualLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 160));
            manualLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); manualLayout.Controls.Add(Label("分隔符", 9, FontStyle.Regular, Theme.Muted), 0, 0);
            InputFrame manualFrame = new InputFrame { Dock = DockStyle.Fill, Padding = new Padding(10, 6, 10, 5), Margin = new Padding(0, 0, 0, 6) };
            manualSeparator = new TextBox { Text = "----", MaxLength = 128, BorderStyle = BorderStyle.None, Dock = DockStyle.Fill,
                Font = new Font("Consolas", 11), BackColor = Theme.Surface, ForeColor = Theme.Ink, AccessibleName = "手动分隔符" };
            manualSeparator.TextChanged += delegate { if (!automatic) SeparatorOptionsChanged(); };
            manualFrame.Controls.Add(manualSeparator); manualFrame.Watch(manualSeparator); manualLayout.Controls.Add(manualFrame, 1, 0);
            manualLayout.Controls.Add(Label("  按原样匹配", 8.5F, FontStyle.Regular, Theme.Muted), 2, 0); manualOptions.Controls.Add(manualLayout);
            modeDetails.Controls.Add(manualOptions); modeDetails.Controls.Add(autoHint); options.Controls.Add(modeDetails, 2, 0); content.Controls.Add(options, 0, 1);
            InputFrame inputFrame = new InputFrame { Dock = DockStyle.Fill, Padding = new Padding(13, 12, 13, 9) };
            input = new TextBox { Multiline = true, AcceptsReturn = false, ScrollBars = ScrollBars.Vertical, BorderStyle = BorderStyle.None,
                Dock = DockStyle.Fill, Font = new Font("Consolas", 11), BackColor = Theme.Surface, ForeColor = Theme.Ink, MaxLength = 16384, AccessibleName = "原始账号内容" };
            placeholder = Label("账号----密码----2FA密钥", 11, FontStyle.Regular, Color.FromArgb(153, 165, 184));
            placeholder.Font = new Font("Consolas", 11); placeholder.BackColor = Theme.Surface; placeholder.TextAlign = ContentAlignment.TopLeft;
            placeholder.Click += delegate { input.Focus(); };
            inputFrame.Controls.Add(input); inputFrame.Controls.Add(placeholder); placeholder.BringToFront(); inputFrame.Watch(input);
            input.TextChanged += delegate { placeholder.Visible = input.TextLength == 0; if (data != null) ResetResult(); SetIdleStatus(); };
            content.Controls.Add(inputFrame, 0, 2);
            TableLayoutPanel actions = Table(3, 1); actions.Dock = DockStyle.Fill; actions.Padding = new Padding(0, 12, 0, 0);
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 162)); actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 158));
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            ActionButton parse = Button("拆分并生成", ButtonKind.Primary, 150); parse.Glyph = ButtonGlyph.Arrow;
            parse.Click += delegate { ParseInput(); }; actions.Controls.Add(parse, 0, 0);
            ActionButton paste = Button("粘贴并解析", ButtonKind.Secondary, 146); paste.Glyph = ButtonGlyph.Clipboard;
            paste.Click += delegate { PasteAndParse(); }; actions.Controls.Add(paste, 1, 0);
            ActionButton clear = Button("清空内容", ButtonKind.Ghost, 100); clear.Anchor = AnchorStyles.Right;
            clear.Click += delegate { ClearInput(); }; actions.Controls.Add(clear, 2, 0); content.Controls.Add(actions, 0, 3);
            card.Controls.Add(content); return card;
        }
        private Control CreateResultCard()
        {
            Card card = new Card { Dock = DockStyle.Fill, Padding = new Padding(20, 15, 20, 15) };
            TableLayoutPanel content = Table(1, 4); content.Dock = DockStyle.Fill; content.RowStyles.Add(new RowStyle(SizeType.Absolute, 36));
            for (int i = 0; i < 3; i++) content.RowStyles.Add(new RowStyle(SizeType.Percent, 33.333F));
            TableLayoutPanel header = Table(2, 1); header.Dock = DockStyle.Fill;
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); header.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 98));
            header.Controls.Add(Label("登录信息", 12.5F, FontStyle.Bold, Theme.Ink), 0, 0);
            hidePassword = new CheckBox { Text = "隐藏密码", AutoSize = false, Dock = DockStyle.Fill, ForeColor = Theme.Muted,
                Font = Theme.Font(8.5F, FontStyle.Regular), BackColor = Color.Transparent, TextAlign = ContentAlignment.MiddleLeft };
            hidePassword.CheckedChanged += delegate { if (password != null) password.UseSystemPasswordChar = hidePassword.Checked; };
            header.Controls.Add(hidePassword, 1, 0); content.Controls.Add(header, 0, 0);
            content.Controls.Add(CreateField("账号", "复制账号", out account, out accountCopy), 0, 1);
            content.Controls.Add(CreateField("密码", "复制密码", out password, out passwordCopy), 0, 2);
            content.Controls.Add(CreateField("2FA 密钥", "复制密钥", out secret, out secretCopy), 0, 3);
            accountCopy.Click += delegate { if (data != null) Copy(data.Account, "账号", accountCopy); };
            passwordCopy.Click += delegate { if (data != null) Copy(data.Password, "密码", passwordCopy); };
            secretCopy.Click += delegate { if (data != null) Copy(data.Secret, "2FA 密钥", secretCopy); };
            card.Controls.Add(content); return card;
        }
        private Control CreateCodeCard()
        {
            Card card = new Card { Dock = DockStyle.Fill, FillColor = Theme.Navy, BorderColor = Theme.Navy, Padding = new Padding(22, 18, 22, 20) };
            TableLayoutPanel content = Table(1, 6); content.Dock = DockStyle.Fill;
            content.RowStyles.Add(new RowStyle(SizeType.Absolute, 28)); content.RowStyles.Add(new RowStyle(SizeType.Absolute, 23));
            content.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); content.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
            content.RowStyles.Add(new RowStyle(SizeType.Absolute, 18)); content.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
            content.Controls.Add(Label("动态验证码", 12.5F, FontStyle.Bold, Color.White), 0, 0);
            resultBadge = Label("解析后自动生成，每 30 秒刷新", 8.5F, FontStyle.Regular, Color.FromArgb(158, 177, 208)); content.Controls.Add(resultBadge, 0, 1);
            codeLabel = Label("--- ---", 41, FontStyle.Bold, Color.FromArgb(121, 151, 197)); codeLabel.Font = new Font("Consolas", 41, FontStyle.Bold);
            codeLabel.TextAlign = ContentAlignment.MiddleCenter; codeLabel.AccessibleName = "当前六位验证码，等待解析"; content.Controls.Add(codeLabel, 0, 2);
            countdown = Label("等待解析", 9, FontStyle.Regular, Color.FromArgb(174, 190, 216)); countdown.TextAlign = ContentAlignment.MiddleCenter; content.Controls.Add(countdown, 0, 3);
            progress = new ProgressTrack { Dock = DockStyle.Fill, Margin = new Padding(0, 4, 0, 10) }; content.Controls.Add(progress, 0, 4);
            codeCopy = Button("复制验证码", ButtonKind.OnDark, 240); codeCopy.Dock = DockStyle.Fill; codeCopy.Glyph = ButtonGlyph.Copy; codeCopy.Font = Theme.Font(10, FontStyle.Bold);
            codeCopy.Click += delegate { if (data != null) { UpdateCode(); Copy(data.Code(Totp.UnixNow()), "验证码", codeCopy); } };
            content.Controls.Add(codeCopy, 0, 5); card.Controls.Add(content); return card;
        }
        private Control CreateFooter()
        {
            TableLayoutPanel footer = Table(3, 1); footer.Dock = DockStyle.Fill; footer.Padding = new Padding(2, 8, 0, 0);
            footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); footer.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 138)); footer.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 122));
            status = Label("", 8.5F, FontStyle.Regular, Theme.Muted); status.AutoEllipsis = true; footer.Controls.Add(status, 0, 0);
            Label shortcut = Label("Ctrl+Enter  解析", 8, FontStyle.Regular, Theme.Muted); shortcut.TextAlign = ContentAlignment.MiddleRight; shortcut.Padding = new Padding(0, 0, 14, 0); footer.Controls.Add(shortcut, 1, 0);
            allCopy = Button("复制全部", ButtonKind.Ghost, 120); allCopy.Dock = DockStyle.Fill; allCopy.Glyph = ButtonGlyph.Copy; allCopy.AccessibleName = "复制全部结果";
            allCopy.Click += delegate { if (data != null) Copy("账号：" + data.Account + "\r\n密码：" + data.Password + "\r\n2FA密钥：" + data.Secret + "\r\n验证码：" + data.Code(Totp.UnixNow()), "全部结果", allCopy); };
            footer.Controls.Add(allCopy, 2, 0); return footer;
        }
        private Control CreateField(string label, string accessibleCopy, out TextBox value, out ActionButton copy)
        {
            TableLayoutPanel row = Table(2, 2); row.Dock = DockStyle.Fill; row.Padding = new Padding(0, 7, 0, 5);
            row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 76));
            row.RowStyles.Add(new RowStyle(SizeType.Absolute, 22)); row.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            row.Controls.Add(Label(label, 8.5F, FontStyle.Regular, Theme.Muted), 0, 0);
            value = new TextBox { ReadOnly = true, BorderStyle = BorderStyle.None, BackColor = Color.White, ForeColor = Theme.Ink,
                Font = new Font("Consolas", label == "2FA 密钥" ? 10.5F : 12), Anchor = AnchorStyles.Left | AnchorStyles.Right, AccessibleName = label + "结果", Margin = new Padding(0, 0, 14, 0) };
            row.Controls.Add(value, 0, 1); copy = Button("复制", ButtonKind.Copy, 70); copy.Height = 32; copy.Glyph = ButtonGlyph.Copy;
            copy.AccessibleName = accessibleCopy; copy.Anchor = AnchorStyles.Right; row.Controls.Add(copy, 1, 0); row.SetRowSpan(copy, 2); return row;
        }
        private static TableLayoutPanel Table(int columns, int rows)
        {
            TableLayoutPanel table = new TableLayoutPanel { ColumnCount = columns, RowCount = rows,
                BackColor = Color.Transparent, Margin = new Padding(0), Padding = new Padding(0) };
            if (rows == 1) table.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            if (columns == 1) table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            return table;
        }
        private static Label Label(string text, float size, FontStyle style, Color color) { return new Label { Text = text, Font = Theme.Font(size, style), ForeColor = color, Dock = DockStyle.Fill, AutoSize = false, BackColor = Color.Transparent, UseMnemonic = false, Margin = new Padding(0), TextAlign = ContentAlignment.MiddleLeft }; }
        private static ActionButton Button(string text, ButtonKind kind, int width) { return new ActionButton { Text = text, Kind = kind, Width = width, Height = 40, Font = Theme.Font(9, FontStyle.Regular), Margin = new Padding(0) }; }
        private void SetSeparatorMode(bool useAutomatic)
        {
            if (automatic == useAutomatic) return; automatic = useAutomatic;
            autoMode.Kind = automatic ? ButtonKind.Selected : ButtonKind.Ghost; manualMode.Kind = automatic ? ButtonKind.Ghost : ButtonKind.Selected;
            autoMode.Invalidate(); manualMode.Invalidate(); autoHint.Visible = automatic; manualOptions.Visible = !automatic;
            if (!automatic) { manualOptions.BringToFront(); manualSeparator.Focus(); } SeparatorOptionsChanged();
        }
        private void SeparatorOptionsChanged() { if (status == null || account == null) return; ResetResult(); SetStatus("分隔符设置已更改，请重新解析。", false); }
        private void PasteAndParse()
        {
            try {
                if (!Clipboard.ContainsText()) { SetStatus("剪贴板中没有文本，请先复制一条账号记录。", true); return; }
                string text = Clipboard.GetText(); if (text.Length > input.MaxLength) { SetStatus("内容过长，请每次只粘贴一条账号记录。", true); return; }
                input.Text = text; ParseInput();
            } catch (ExternalException) { SetStatus("暂时无法读取剪贴板，请稍后重试。", true); }
        }
        private void ParseInput()
        {
            placeholder.Visible = input.TextLength == 0;
            ResetResult();
            try {
                data = AccountParser.Parse(input.Text, automatic ? null : manualSeparator.Text);
                ShowResult();
                SetStatus("解析成功 · 分隔符 " + SeparatorPreview(data.FirstSeparator) + (data.FirstSeparator == data.LastSeparator ? "" : " / " + SeparatorPreview(data.LastSeparator)), false);
                try {
                    history.Record(data, automatic); selectedHistory = history.Entries[0].Id; RefreshHistory();
                } catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("解析成功，但历史记录保存失败；请检查本机历史文件。", true); }
            } catch (FormatException error) { SetStatus(error.Message, true); }
        }
        private static string SeparatorPreview(string value) { value = value.Replace("\t", "\\t"); return "「" + (value.Length > 12 ? value.Substring(0, 12) + "…" : value) + "」"; }
        private void SetCopyEnabled(bool enabled) { accountCopy.Enabled = passwordCopy.Enabled = secretCopy.Enabled = codeCopy.Enabled = allCopy.Enabled = enabled; }
        private void ClearInput() { input.Clear(); ResetResult(); SetStatus("已清空当前内容", false); input.Focus(); }
        private void ResetResult()
        {
            if (data != null) { data.Dispose(); data = null; } lastStep = -1;
            account.Text = password.Text = secret.Text = "等待解析"; account.ForeColor = password.ForeColor = secret.ForeColor = Color.FromArgb(160, 172, 190);
            codeLabel.Text = "--- ---"; codeLabel.ForeColor = Color.FromArgb(121, 151, 197); codeLabel.AccessibleName = "当前六位验证码，等待解析";
            resultBadge.Text = "解析后自动生成，每 30 秒刷新"; countdown.Text = "等待解析"; countdown.ForeColor = Color.FromArgb(174, 190, 216); progress.Fraction = 0; SetCopyEnabled(false);
        }
        private void UpdateCode()
        {
            if (data == null) return; long now = Totp.UnixNow();
            if (now / 30 != lastStep) { string code = data.Code(now); codeLabel.Text = code.Substring(0, 3) + " " + code.Substring(3); codeLabel.ForeColor = Color.White; codeLabel.AccessibleName = "当前验证码 " + code; lastStep = now / 30; }
            int remaining = 30 - (int)(now % 30); countdown.Text = remaining + " 秒后更新";
            countdown.ForeColor = remaining <= 5 ? Color.FromArgb(255, 199, 112) : Color.FromArgb(174, 190, 216);
            progress.BarColor = remaining <= 5 ? Color.FromArgb(255, 194, 99) : Color.FromArgb(123, 164, 255); progress.Fraction = remaining / 30F;
        }
        private void Copy(string value, string label, ActionButton button)
        {
            try { Clipboard.SetText(value, TextDataFormat.UnicodeText); button.ShowCopied(); SetStatus(label + "已复制", false); }
            catch (ExternalException) { SetStatus("剪贴板正被其他程序占用，请重新点击复制。", true); }
        }
        private void SetIdleStatus() { status.Text = "●  本地离线运行 · 最近 10 条记录加密保存"; status.ForeColor = Theme.Muted; transientStatus = false; }
        private void SetStatus(string text, bool error) { status.Text = text; status.ForeColor = error ? Color.FromArgb(197, 64, 77) : Color.FromArgb(32, 137, 105); transientStatus = !error; statusUntil = DateTime.UtcNow.AddSeconds(7); }

        private void ShowResult()
        {
            account.Text = data.Account; password.Text = data.Password; secret.Text = data.Secret;
            account.ForeColor = password.ForeColor = secret.ForeColor = Theme.Ink;
            resultBadge.Text = "6 位验证码 · 每 30 秒自动刷新";
            SetCopyEnabled(true); lastStep = -1; UpdateCode();
        }

        private Card CreateHistoryCard()
        {
            Card card = new Card { Dock = DockStyle.Fill, Margin = new Padding(0, 18, 20, 52), Padding = new Padding(14, 14, 14, 12) };
            TableLayoutPanel layout = Table(1, 4); layout.Dock = DockStyle.Fill;
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
            layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 32));
            layout.Controls.Add(Label("最近识别", 12.5F, FontStyle.Bold, Theme.Ink), 0, 0);
            historyCount = Label("0 / 10 条 · 点击记录恢复", 8.5F, FontStyle.Regular, Theme.Muted);
            layout.Controls.Add(historyCount, 0, 1);
            historyList = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoScroll = true, WrapContents = false,
                FlowDirection = FlowDirection.TopDown, BackColor = Color.White, Margin = new Padding(0) };
            historyList.SizeChanged += delegate { ResizeHistoryItems(); };
            layout.Controls.Add(historyList, 0, 2);
            clearHistory = Button("清空历史记录", ButtonKind.Ghost, 180); clearHistory.Dock = DockStyle.Fill;
            clearHistory.Click += delegate {
                if (MessageBox.Show(this, "清空所有历史记录？当前窗口中的识别结果会保留。", "账号验证管家",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
                try { history.Clear(); selectedHistory = Guid.Empty; RefreshHistory(); SetStatus("历史记录已清空", false); }
                catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("无法清空历史记录，请检查文件是否可写。", true); }
            };
            layout.Controls.Add(clearHistory, 0, 3); card.Controls.Add(layout); return card;
        }

        private void ToggleHistory()
        {
            historyExpanded = !historyExpanded;
            if (historyExpanded) {
                int expandedWidth = Width + 280;
                MinimumSize = new Size(1230, 730);
                if (WindowState == FormWindowState.Normal) Width = Math.Max(1230, expandedWidth);
                shell.ColumnStyles[1].Width = 280; historyCard.Visible = true;
                try { history.Load(); RefreshHistory(); }
                catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("历史记录读取失败，原文件已保留。", true); }
            } else {
                historyCard.Visible = false; shell.ColumnStyles[1].Width = 0; MinimumSize = new Size(950, 730);
                if (WindowState == FormWindowState.Normal) Width = Math.Max(950, Width - 280);
            }
        }

        private void RefreshHistory()
        {
            historyList.SuspendLayout();
            while (historyList.Controls.Count > 0) { Control old = historyList.Controls[0]; historyList.Controls.RemoveAt(0); old.Dispose(); }
            historyCount.Text = history.Entries.Count + " / 10 条 · 点击记录恢复";
            historyToggle.Text = "历史记录 " + history.Entries.Count + "/10";
            clearHistory.Enabled = history.Entries.Count > 0 || File.Exists(history.FilePath);
            if (history.Entries.Count == 0) {
                Label empty = Label("暂无历史记录\r\n\r\n成功识别后自动保存\r\n重启后仍可查看", 9, FontStyle.Regular, Theme.Muted);
                empty.Dock = DockStyle.None; empty.Height = 140; empty.TextAlign = ContentAlignment.MiddleCenter;
                historyList.Controls.Add(empty);
            }
            foreach (HistoryEntry entry in history.Entries) historyList.Controls.Add(CreateHistoryItem(entry));
            ResizeHistoryItems(); historyList.ResumeLayout();
        }

        private void ResizeHistoryItems()
        {
            if (historyList == null) return;
            int width = Math.Max(180, historyList.ClientSize.Width - SystemInformation.VerticalScrollBarWidth - 2);
            foreach (Control item in historyList.Controls) item.Width = width;
        }

        private Control CreateHistoryItem(HistoryEntry entry)
        {
            Card card = new Card { Height = 104, Padding = new Padding(6), Margin = new Padding(0, 0, 0, 8), Radius = 9,
                BorderColor = entry.Id == selectedHistory ? Color.FromArgb(139, 168, 250) : Theme.Line };
            TableLayoutPanel content = Table(1, 3); content.Dock = DockStyle.Fill;
            content.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
            content.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            content.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
            ActionButton open = Button(entry.Account, ButtonKind.Ghost, 200); open.Dock = DockStyle.Fill; open.AlignLeft = true;
            open.Font = Theme.Font(9, FontStyle.Bold); open.AccessibleName = "打开历史记录 " + entry.Account;
            open.Click += delegate { RestoreHistory(entry); }; content.Controls.Add(open, 0, 0);
            Label time = Label(entry.RecognizedAtUtc.ToLocalTime().ToString("MM-dd HH:mm") + "  ·  已加密保存", 8, FontStyle.Regular, Theme.Muted);
            time.Padding = new Padding(8, 0, 0, 0); time.Click += delegate { RestoreHistory(entry); }; content.Controls.Add(time, 0, 1);
            TableLayoutPanel actions = Table(4, 1); actions.Dock = DockStyle.Fill;
            for (int i = 0; i < 4; i++) actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 25));
            string[] captions = { "账号", "密码", "验证码", "整组" };
            for (int i = 0; i < captions.Length; i++) {
                int kind = i; ActionButton copy = Button(captions[i], ButtonKind.Copy, 48); copy.Dock = DockStyle.Fill;
                copy.Font = Theme.Font(8, FontStyle.Regular); copy.Margin = new Padding(1, 0, 1, 0);
                copy.AccessibleName = "历史记录 " + entry.Account + " 复制" + captions[i];
                copy.Click += delegate {
                    string value = kind == 0 ? entry.Account : kind == 1 ? entry.Password :
                        kind == 2 ? entry.CurrentCode(Totp.UnixNow()) : entry.CopyGroup(Totp.UnixNow());
                    Copy(value, "历史" + captions[kind], copy);
                };
                actions.Controls.Add(copy, i, 0);
            }
            content.Controls.Add(actions, 0, 2); card.Click += delegate { RestoreHistory(entry); };
            card.Controls.Add(content); return card;
        }

        private void RestoreHistory(HistoryEntry entry)
        {
            ResetResult(); SetSeparatorMode(entry.Automatic);
            if (!entry.Automatic) manualSeparator.Text = entry.FirstSeparator;
            input.Text = entry.OriginalText(); placeholder.Visible = false;
            data = entry.Open(); ShowResult(); selectedHistory = entry.Id; RefreshHistory();
            SetStatus("已恢复历史记录，可复制账号、密码和当前验证码。", false);
        }

        private static bool HistoryFailure(Exception error)
        {
            return error is IOException || error is UnauthorizedAccessException || error is CryptographicException ||
                error is FormatException || error is System.Security.SecurityException;
        }
    }
    internal static class Shapes
    {
        public static GraphicsPath Round(RectangleF rect, float radius)
        {
            float d = Math.Min(radius * 2, Math.Min(rect.Width, rect.Height)); GraphicsPath path = new GraphicsPath(); if (d <= 0) return path;
            path.AddArc(rect.X, rect.Y, d, d, 180, 90); path.AddArc(rect.Right - d, rect.Y, d, d, 270, 90);
            path.AddArc(rect.Right - d, rect.Bottom - d, d, d, 0, 90); path.AddArc(rect.X, rect.Bottom - d, d, d, 90, 90); path.CloseFigure(); return path;
        }
    }
    internal class Card : Panel
    {
        public Color FillColor = Color.White, BorderColor = Theme.Line; public float Radius = 14;
        public Card() { DoubleBuffered = true; BackColor = Color.Transparent; Margin = new Padding(0); }
        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (GraphicsPath path = Shapes.Round(new RectangleF(.5F, .5F, Width - 1, Height - 1), Radius))
            using (SolidBrush brush = new SolidBrush(FillColor)) using (Pen pen = new Pen(BorderColor)) { e.Graphics.FillPath(brush, path); e.Graphics.DrawPath(pen, path); }
            base.OnPaint(e);
        }
    }
    internal class InputFrame : Card
    {
        private bool active;
        public InputFrame() { FillColor = Theme.Surface; Radius = 9; }
        public void Watch(TextBox text) { text.GotFocus += delegate { active = true; Invalidate(); }; text.LostFocus += delegate { active = false; Invalidate(); }; }
        protected override void OnPaint(PaintEventArgs e) { BorderColor = active ? Color.FromArgb(117, 152, 248) : Theme.Line; base.OnPaint(e); }
    }
    internal enum ButtonKind { Primary, Secondary, Ghost, Copy, OnDark, Selected }
    internal enum ButtonGlyph { None, Copy, Clipboard, Arrow }
    internal class ActionButton : Control
    {
        public ButtonKind Kind; public ButtonGlyph Glyph; public bool AlignLeft;
        private bool hovered, pressed, copied; private Timer copiedTimer;
        public ActionButton()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw |
                ControlStyles.SupportsTransparentBackColor | ControlStyles.Selectable | ControlStyles.StandardClick, true);
            BackColor = Color.Transparent; TabStop = true; Cursor = Cursors.Hand; AccessibleRole = AccessibleRole.PushButton;
        }
        public void ShowCopied()
        {
            copied = true; Invalidate();
            if (copiedTimer == null) { copiedTimer = new Timer { Interval = 1300 }; copiedTimer.Tick += delegate { copied = false; copiedTimer.Stop(); Invalidate(); }; }
            copiedTimer.Stop(); copiedTimer.Start();
        }
        protected override void OnEnabledChanged(EventArgs e) { copied = false; Invalidate(); base.OnEnabledChanged(e); }
        protected override void OnMouseEnter(EventArgs e) { hovered = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hovered = false; pressed = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { if (e.Button == MouseButtons.Left) { Focus(); pressed = true; Invalidate(); } base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { pressed = false; Invalidate(); base.OnMouseUp(e); }
        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { pressed = false; Invalidate(); base.OnLostFocus(e); }
        protected override void OnKeyDown(KeyEventArgs e) { if (e.KeyCode == Keys.Space || e.KeyCode == Keys.Enter) { pressed = true; Invalidate(); e.Handled = true; } base.OnKeyDown(e); }
        protected override void OnKeyUp(KeyEventArgs e) { if (pressed && (e.KeyCode == Keys.Space || e.KeyCode == Keys.Enter)) { pressed = false; Invalidate(); if (Enabled) OnClick(EventArgs.Empty); e.Handled = true; } base.OnKeyUp(e); }
        protected override bool IsInputKey(Keys keyData) { return (keyData & Keys.KeyCode) == Keys.Enter || base.IsInputKey(keyData); }
        protected override void OnPaint(PaintEventArgs e)
        {
            if (Width < 4 || Height < 4) return; e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Color fill = Color.White, ink = Theme.Ink, border = Color.Transparent;
            switch (Kind) {
                case ButtonKind.Primary: fill = pressed ? Color.FromArgb(40, 79, 193) : hovered ? Color.FromArgb(45, 89, 217) : Theme.Blue; ink = Color.White; break;
                case ButtonKind.Secondary: fill = hovered ? Color.FromArgb(243, 247, 255) : Color.White; border = Theme.Line; break;
                case ButtonKind.Ghost: fill = hovered ? Color.FromArgb(232, 239, 250) : Color.Transparent; ink = Theme.Muted; break;
                case ButtonKind.Copy: fill = hovered ? Color.FromArgb(221, 232, 255) : Color.FromArgb(239, 244, 255); ink = Theme.Blue; break;
                case ButtonKind.OnDark: fill = hovered ? Color.FromArgb(226, 236, 255) : Color.White; ink = Theme.Navy; break;
                case ButtonKind.Selected: fill = Color.White; ink = Theme.Blue; border = Color.FromArgb(223, 230, 241); break;
            }
            if (!Enabled) { fill = Kind == ButtonKind.OnDark ? Color.FromArgb(42, 60, 88) : Kind == ButtonKind.Ghost ? Color.Transparent : Color.FromArgb(244, 246, 250); ink = Kind == ButtonKind.OnDark ? Color.FromArgb(124, 144, 174) : Color.FromArgb(169, 180, 197); border = Color.Transparent; }
            if (copied && Enabled) ink = Kind == ButtonKind.Primary ? Color.White : Color.FromArgb(32, 137, 105);
            using (GraphicsPath path = Shapes.Round(new RectangleF(1, 1, Width - 2, Height - 2), 8)) {
                if (fill.A > 0) using (SolidBrush brush = new SolidBrush(fill)) e.Graphics.FillPath(brush, path);
                if (border.A > 0) using (Pen pen = new Pen(border)) e.Graphics.DrawPath(pen, path);
                if (Focused && ShowFocusCues) using (Pen pen = new Pen(Color.FromArgb(125, 158, 249), 1.3F)) e.Graphics.DrawPath(pen, path);
            }
            string caption = copied ? (Width < 90 ? "已复制" : "已复制成功") : Text; ButtonGlyph glyph = copied ? ButtonGlyph.None : Glyph;
            int gap = glyph == ButtonGlyph.None ? 0 : 23; Size size = TextRenderer.MeasureText(caption, Font, new Size(Width, Height), TextFormatFlags.NoPadding);
            int x = AlignLeft ? 8 : (Width - size.Width - gap) / 2;
            if (glyph != ButtonGlyph.None) DrawGlyph(e.Graphics, glyph, new RectangleF(x, (Height - 15) / 2F, 15, 15), ink);
            TextRenderer.DrawText(e.Graphics, caption, Font, new Rectangle(x + gap, 0, AlignLeft ? Math.Max(1, Width - x - gap - 8) : size.Width + 2, Height), ink,
                TextFormatFlags.NoPadding | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis);
        }
        private static void DrawGlyph(Graphics g, ButtonGlyph glyph, RectangleF r, Color ink)
        {
            using (Pen pen = new Pen(ink, 1.4F)) { pen.StartCap = pen.EndCap = LineCap.Round; pen.LineJoin = LineJoin.Round;
                if (glyph == ButtonGlyph.Copy) { g.DrawRectangle(pen, r.X + 5, r.Y + 5, 8, 9); g.DrawLines(pen, new PointF[] { new PointF(r.X + 2, r.Y + 10), new PointF(r.X + 1, r.Y + 10), new PointF(r.X + 1, r.Y + 1), new PointF(r.X + 9, r.Y + 1), new PointF(r.X + 9, r.Y + 2) }); }
                else if (glyph == ButtonGlyph.Clipboard) { g.DrawRectangle(pen, r.X + 3, r.Y + 3, 10, 11); g.DrawRectangle(pen, r.X + 6, r.Y + 1, 4, 4); }
                else { g.DrawLine(pen, r.X + 1, r.Y + 8, r.X + 13, r.Y + 8); g.DrawLines(pen, new PointF[] { new PointF(r.X + 8, r.Y + 3), new PointF(r.X + 13, r.Y + 8), new PointF(r.X + 8, r.Y + 13) }); }
            }
        }
        protected override void Dispose(bool disposing) { if (disposing && copiedTimer != null) copiedTimer.Dispose(); base.Dispose(disposing); }
    }
    internal class BrandMark : Control
    {
        public BrandMark() { SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true); BackColor = Color.Transparent; }
        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (GraphicsPath path = Shapes.Round(new RectangleF(0, 0, Width - 1, Height - 1), 12)) using (SolidBrush fill = new SolidBrush(Theme.Blue)) e.Graphics.FillPath(fill, path);
            using (Pen pen = new Pen(Color.White, 2.7F)) { pen.StartCap = pen.EndCap = LineCap.Round;
                e.Graphics.DrawLine(pen, 12, 13, 31, 13); e.Graphics.DrawLine(pen, 12, 22, 25, 22); e.Graphics.DrawLine(pen, 12, 31, 20, 31);
                e.Graphics.DrawLines(pen, new PointF[] { new PointF(28, 26), new PointF(33, 31), new PointF(28, 36) });
            }
        }
    }
    internal class ProgressTrack : Control
    {
        private float fraction; public Color BarColor = Color.FromArgb(123, 164, 255);
        public float Fraction { get { return fraction; } set { fraction = value; Invalidate(); } }
        public ProgressTrack() { SetStyle(ControlStyles.SupportsTransparentBackColor, true); DoubleBuffered = true; BackColor = Color.Transparent; }
        protected override void OnPaint(PaintEventArgs e)
        {
            if (Width < 2 || Height < 2) return; e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (GraphicsPath track = Shapes.Round(new RectangleF(0, 0, Width, Height), Height / 2F)) using (SolidBrush fill = new SolidBrush(Color.FromArgb(56, 75, 103))) e.Graphics.FillPath(fill, track);
            if (fraction > 0) using (GraphicsPath bar = Shapes.Round(new RectangleF(0, 0, Math.Max(Height, Width * fraction), Height), Height / 2F)) using (SolidBrush fill = new SolidBrush(BarColor)) e.Graphics.FillPath(fill, bar);
        }
    }
}
