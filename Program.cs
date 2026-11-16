using System;
using System.IO;
using System.Security.Cryptography;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace AccountSplit2FA
{
    internal static class Program
    {
        [DllImport("user32.dll")] private static extern bool SetProcessDPIAware();
        [STAThread] private static void Main(string[] args)
        {
            QrDependencies.Register();
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
        private ActionButton accountCopy, passwordCopy, secretCopy, codeCopy, allCopy, autoMode, manualMode, nextCode;
        private TableLayoutPanel counterOptions;
        private Panel manualOptions;
        private CheckBox hidePassword;
        private ProgressTrack progress;
        private Timer timer;
        private AccountData data;
        private bool automatic = true, transientStatus;
        private long lastStep = -1;
        private DateTime statusUntil;
        private readonly HistoryStore history;
        private readonly DeferredContextMenu managementMenu;
        private TableLayoutPanel shell;
        private TableLayoutPanel mainPage;
        private Panel contentHost;
        private Card historyCard;
        private FlowLayoutPanel historyList;
        private Label historyCount;
        private Label historyStatus;
        private ActionButton historyToggle, clearHistory;
        private ActionButton qrUpload;
        private ActionButton replaceOtp, expiryButton;
        private bool transferBusy;
        private RetentionPeriod newRetention;
        private DateTime nextHistoryRefresh;
        private string historyStamp = "";
        private long qrImportVersion;
        private Guid selectedHistory = Guid.Empty;
        private bool historyExpanded;
        private bool codeFontPending;
        private readonly Dictionary<int, Font> codeFonts = new Dictionary<int, Font>();

        public MainForm() : this(null) { }
        public MainForm(string historyFile)
        {
            history = new HistoryStore(historyFile);
            Text = "账号验证管家 v1.9.2";
            ClientSize = new Size(860, 660);
            MinimumSize = new Size(780, 660);
            StartPosition = FormStartPosition.CenterScreen; BackColor = Theme.Background;
            Font = Theme.Font(10, FontStyle.Regular); AutoScaleMode = AutoScaleMode.Dpi; DoubleBuffered = true;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            shell = Table(1, 2); shell.Dock = DockStyle.Fill; shell.Padding = new Padding(18, 16, 18, 12);
            shell.RowStyles.Add(new RowStyle(SizeType.Absolute, 72)); shell.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            Controls.Add(shell); shell.Controls.Add(CreateHeader(), 0, 0);
            contentHost = new Panel { Dock = DockStyle.Fill, Margin = new Padding(0), BackColor = Color.Transparent };
            shell.Controls.Add(contentHost, 0, 1);
            mainPage = Table(1, 4); mainPage.Dock = DockStyle.Fill;
            mainPage.RowStyles.Add(new RowStyle(SizeType.Absolute, 242)); mainPage.RowStyles.Add(new RowStyle(SizeType.Absolute, 12));
            mainPage.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); mainPage.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
            mainPage.Controls.Add(CreateInputCard(), 0, 0);
            TableLayoutPanel resultLayout = Table(3, 1); resultLayout.Dock = DockStyle.Fill;
            resultLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 58));
            resultLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 14));
            resultLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 42));
            resultLayout.Controls.Add(CreateResultCard(), 0, 0); resultLayout.Controls.Add(CreateCodeCard(), 2, 0);
            mainPage.Controls.Add(resultLayout, 0, 2); mainPage.Controls.Add(CreateFooter(), 0, 3);
            contentHost.Controls.Add(mainPage);
            historyCard = CreateHistoryCard(); historyCard.Visible = false; contentHost.Controls.Add(historyCard);
            KeyPreview = true;
            KeyDown += delegate(object sender, KeyEventArgs e) {
                if (e.Control && e.KeyCode == Keys.Enter && !historyExpanded) { ParseInput(); e.SuppressKeyPress = true; }
                if (e.KeyCode == Keys.Escape) { if (historyExpanded) SetHistoryPage(false); else ClearInput(); e.SuppressKeyPress = true; }
            };
            Shown += delegate { input.Focus(); };
            timer = new Timer { Interval = 200 };
            timer.Tick += delegate {
                placeholder.Visible = input.TextLength == 0;
                SynchronizeHistory(); UpdateCode();
                if (transientStatus && DateTime.UtcNow > statusUntil) SetIdleStatus();
            };
            ResetResult(); SetIdleStatus();
            try { history.Load(); } catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("历史记录无法读取，原文件已保留。可清空历史后重试。", true); }
            RefreshHistory(); timer.Start();
            FormClosed += delegate { timer.Stop(); timer.Dispose(); ResetResult(); input.Clear(); };
            managementMenu = new DeferredContextMenu(this);
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
            heading.Controls.Add(Label("智能识别解析", 12.5F, FontStyle.Bold, Theme.Ink), 0, 0);
            expiryButton = Button("保存期限：永久", ButtonKind.Ghost, 190); expiryButton.Dock = DockStyle.Fill;
            expiryButton.Font = Theme.Font(8.5F, FontStyle.Regular); expiryButton.AccessibleName = "首次保存的到期时间";
            expiryButton.Click += delegate { using (ExpirationDialog dialog = new ExpirationDialog(newRetention, null, false, delegate(RetentionPeriod value) { newRetention = value; RefreshExpiryButton(); })) dialog.ShowDialog(this); };
            heading.Controls.Add(expiryButton, 1, 0); content.Controls.Add(heading, 0, 0);
            TableLayoutPanel options = Table(3, 1); options.Dock = DockStyle.Fill;
            options.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 240)); options.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 18));
            options.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            Card switcher = new Card { Dock = DockStyle.Fill, FillColor = Color.FromArgb(239, 243, 249), BorderColor = Color.FromArgb(239, 243, 249),
                Radius = 9, Padding = new Padding(3), Margin = new Padding(0, 0, 0, 6) };
            TableLayoutPanel switches = Table(2, 1); switches.Dock = DockStyle.Fill;
            switches.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50)); switches.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            autoMode = Button("智能识别", ButtonKind.Selected, 114); autoMode.Dock = DockStyle.Fill; autoMode.Height = 30;
            autoMode.AccessibleName = "智能识别输入和分隔符"; autoMode.Click += delegate { SetSeparatorMode(true); };
            manualMode = Button("手动设置", ButtonKind.Ghost, 114); manualMode.Dock = DockStyle.Fill; manualMode.Height = 30;
            manualMode.AccessibleName = "手动设置分隔符"; manualMode.Click += delegate { SetSeparatorMode(false); };
            switches.Controls.Add(autoMode, 0, 0); switches.Controls.Add(manualMode, 1, 0); switcher.Controls.Add(switches);
            options.Controls.Add(switcher, 0, 0);
            Panel modeDetails = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent };
            autoHint = Label("直接粘贴账号记录、纯 2FA 密钥或 Authenticator URI", 8.5F, FontStyle.Regular, Theme.Muted);
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
            placeholder = Label("账号----密码----密钥，或纯密钥 / otpauth://...", 11, FontStyle.Regular, Color.FromArgb(153, 165, 184));
            placeholder.Font = new Font("Consolas", 11); placeholder.BackColor = Theme.Surface; placeholder.TextAlign = ContentAlignment.TopLeft;
            placeholder.Click += delegate { input.Focus(); };
            inputFrame.Controls.Add(input); inputFrame.Controls.Add(placeholder); placeholder.BringToFront(); inputFrame.Watch(input);
            input.TextChanged += delegate { qrImportVersion++; placeholder.Visible = input.TextLength == 0; if (data != null) ResetResult(); SetIdleStatus(); };
            content.Controls.Add(inputFrame, 0, 2);
            TableLayoutPanel actions = Table(4, 1); actions.Dock = DockStyle.Fill; actions.Padding = new Padding(0, 12, 0, 0);
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 162)); actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 158));
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 146));
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            ActionButton parse = Button("识别并生成", ButtonKind.Primary, 150); parse.Glyph = ButtonGlyph.Arrow;
            parse.Click += delegate { ParseInput(); }; actions.Controls.Add(parse, 0, 0);
            ActionButton paste = Button("粘贴并解析", ButtonKind.Secondary, 146); paste.Glyph = ButtonGlyph.Clipboard;
            paste.Click += delegate { PasteAndParse(); }; actions.Controls.Add(paste, 1, 0);
            qrUpload = Button("上传二维码", ButtonKind.Secondary, 134); qrUpload.AccessibleName = "上传图片识别2FA二维码";
            qrUpload.Click += delegate { UploadQrImage(); }; actions.Controls.Add(qrUpload, 2, 0);
            ActionButton clear = Button("清空内容", ButtonKind.Ghost, 100); clear.Anchor = AnchorStyles.Right;
            clear.Click += delegate { ClearInput(); }; actions.Controls.Add(clear, 3, 0); content.Controls.Add(actions, 0, 3);
            card.Controls.Add(content); return card;
        }
        private Control CreateResultCard()
        {
            Card card = new Card { Dock = DockStyle.Fill, Padding = new Padding(20, 15, 20, 15) };
            TableLayoutPanel content = Table(1, 4); content.Dock = DockStyle.Fill; content.RowStyles.Add(new RowStyle(SizeType.Absolute, 36));
            for (int i = 0; i < 3; i++) content.RowStyles.Add(new RowStyle(SizeType.Percent, 33.333F));
            TableLayoutPanel header = Table(2, 1); header.Dock = DockStyle.Fill;
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); header.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 98));
            header.Controls.Add(Label("识别结果", 12.5F, FontStyle.Bold, Theme.Ink), 0, 0);
            hidePassword = new CheckBox { Text = "隐藏密码", AutoSize = false, Dock = DockStyle.Fill, ForeColor = Theme.Muted,
                Font = Theme.Font(8.5F, FontStyle.Regular), BackColor = Color.Transparent, TextAlign = ContentAlignment.MiddleLeft };
            hidePassword.CheckedChanged += delegate { if (password != null) password.UseSystemPasswordChar = hidePassword.Checked && data != null && data.Password.Length > 0; };
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
            TableLayoutPanel heading = Table(2, 1); heading.Dock = DockStyle.Fill;
            heading.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); heading.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 86));
            heading.Controls.Add(Label("2FA 验证码", 12.5F, FontStyle.Bold, Color.White), 0, 0);
            replaceOtp = Button("更换 2FA", ButtonKind.OnDark, 84); replaceOtp.Dock = DockStyle.Fill; replaceOtp.Font = Theme.Font(8, FontStyle.Regular);
            replaceOtp.AccessibleName = "保留账号密码更换2FA"; replaceOtp.Click += delegate { PromptReplaceOtp(); };
            heading.Controls.Add(replaceOtp, 1, 0); content.Controls.Add(heading, 0, 0);
            resultBadge = Label("解析后自动生成，每 30 秒刷新", 8.5F, FontStyle.Regular, Color.FromArgb(158, 177, 208)); content.Controls.Add(resultBadge, 0, 1);
            codeLabel = Label("--- ---", 41, FontStyle.Bold, Color.FromArgb(121, 151, 197)); codeLabel.Font = CodeFont(41);
            codeLabel.TextAlign = ContentAlignment.MiddleCenter; codeLabel.AccessibleName = "当前六位验证码，等待解析"; content.Controls.Add(codeLabel, 0, 2);
            codeLabel.SizeChanged += delegate { ScheduleCodeFont(); }; codeLabel.TextChanged += delegate { ScheduleCodeFont(); };
            counterOptions = Table(2, 1); counterOptions.Dock = DockStyle.Fill;
            counterOptions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); counterOptions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 0));
            countdown = Label("等待解析", 9, FontStyle.Regular, Color.FromArgb(174, 190, 216)); countdown.TextAlign = ContentAlignment.MiddleCenter; countdown.AutoEllipsis = true;
            counterOptions.Controls.Add(countdown, 0, 0);
            nextCode = Button("下一个", ButtonKind.OnDark, 92); nextCode.Dock = DockStyle.Fill; nextCode.Font = Theme.Font(8, FontStyle.Regular);
            nextCode.AccessibleName = "HOTP 计数器加一并保存"; nextCode.Visible = false;
            nextCode.Click += delegate { AdvanceHotp(); }; counterOptions.Controls.Add(nextCode, 1, 0);
            content.Controls.Add(counterOptions, 0, 3);
            progress = new ProgressTrack { Dock = DockStyle.Fill, Margin = new Padding(0, 4, 0, 10) }; content.Controls.Add(progress, 0, 4);
            codeCopy = Button("复制验证码", ButtonKind.OnDark, 240); codeCopy.Dock = DockStyle.Fill; codeCopy.Glyph = ButtonGlyph.Copy; codeCopy.Font = Theme.Font(10, FontStyle.Bold);
            codeCopy.Click += delegate {
                if (data == null) return;
                try { RefreshHotp(); UpdateCode(); Copy(data.Code(Totp.UnixNow()), "验证码", codeCopy); }
                catch (InvalidOperationException error) { SetStatus(error.Message, true); }
                catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("无法读取最新计数器，请重新打开历史记录。", true); }
            };
            content.Controls.Add(codeCopy, 0, 5); card.Controls.Add(content); return card;
        }
        private Control CreateFooter()
        {
            TableLayoutPanel footer = Table(3, 1); footer.Dock = DockStyle.Fill; footer.Padding = new Padding(2, 8, 0, 0);
            footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); footer.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 138)); footer.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 122));
            status = Label("", 8.5F, FontStyle.Regular, Theme.Muted); status.AutoEllipsis = true; footer.Controls.Add(status, 0, 0);
            Label shortcut = Label("Ctrl+Enter  解析", 8, FontStyle.Regular, Theme.Muted); shortcut.TextAlign = ContentAlignment.MiddleRight; shortcut.Padding = new Padding(0, 0, 14, 0); footer.Controls.Add(shortcut, 1, 0);
            allCopy = Button("复制全部", ButtonKind.Ghost, 120); allCopy.Dock = DockStyle.Fill; allCopy.Glyph = ButtonGlyph.Copy; allCopy.AccessibleName = "复制全部结果";
            allCopy.Click += delegate {
                if (data == null) return;
                try { RefreshHotp(); Copy(data.CopyText(Totp.UnixNow(), true), "全部结果", allCopy); }
                catch (InvalidOperationException error) { SetStatus(error.Message, true); }
                catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("无法读取最新计数器，请重新打开历史记录。", true); }
            };
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
        private void SeparatorOptionsChanged() { if (status == null || account == null) return; qrImportVersion++; ResetResult(); SetStatus("分隔符设置已更改，请重新解析。", false); }
        private void PasteAndParse()
        {
            try {
                if (!Clipboard.ContainsText()) { SetStatus("剪贴板中没有文本，请先复制一条账号记录。", true); return; }
                string text = Clipboard.GetText(); if (text.Length > input.MaxLength) { SetStatus("内容过长，请每次只粘贴一条账号记录。", true); return; }
                input.Text = text; ParseInput();
            } catch (ExternalException) { SetStatus("暂时无法读取剪贴板，请稍后重试。", true); }
        }
        private void UploadQrImage()
        {
            using (OpenFileDialog picker = new OpenFileDialog { Title = "选择含有 2FA 二维码的图片",
                Filter = "图片文件|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.tif;*.tiff|所有文件|*.*", CheckFileExists = true })
            {
                if (picker.ShowDialog(this) != DialogResult.OK) return;
                ResetResult(); long version = ++qrImportVersion; qrUpload.Enabled = false;
                SetStatus("正在识别图片中的 2FA 二维码…", false);
                string path = picker.FileName;
                Task.Factory.StartNew(() => QrImageReader.ReadFile(path)).ContinueWith(task => {
                    if (IsDisposed || !IsHandleCreated) return;
                    try { BeginInvoke((Action)delegate {
                        qrUpload.Enabled = true;
                        if (version != qrImportVersion) return;
                        if (task.IsFaulted) {
                            Exception error = task.Exception.GetBaseException();
                            SetStatus(error is FormatException ? error.Message : "无法读取图片，请选择有效的 PNG、JPEG 或其他常见图片。", true);
                            return;
                        }
                        IList<string> choices = task.Result;
                        string chosen = choices.Count == 1 ? choices[0] : ChooseQr(choices);
                        if (chosen != null) ImportQrText(chosen);
                        else SetStatus("已取消二维码选择。", false);
                    }); } catch (InvalidOperationException) { }
                }, TaskScheduler.Default);
            }
        }
        private string ChooseQr(IList<string> choices)
        {
            using (Form dialog = new Form { Text = "选择要导入的 2FA 二维码", FormBorderStyle = FormBorderStyle.FixedDialog,
                StartPosition = FormStartPosition.CenterParent, ClientSize = new Size(460, 150), MaximizeBox = false, MinimizeBox = false, Font = Font })
            {
                Label caption = new Label { Text = "图片中有多个 2FA 二维码，请选择一条：", AutoSize = true, Location = new Point(20, 18) };
                ComboBox list = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Location = new Point(20, 48), Width = 420 };
                for (int i = 0; i < choices.Count; i++) list.Items.Add((i + 1) + ". " + QrImageReader.Describe(choices[i]));
                list.SelectedIndex = 0;
                System.Windows.Forms.Button accept = new System.Windows.Forms.Button { Text = "识别并生成", DialogResult = DialogResult.OK, Location = new Point(220, 100), Width = 110 };
                System.Windows.Forms.Button cancel = new System.Windows.Forms.Button { Text = "取消", DialogResult = DialogResult.Cancel, Location = new Point(340, 100), Width = 100 };
                dialog.Controls.AddRange(new Control[] { caption, list, accept, cancel }); dialog.AcceptButton = accept; dialog.CancelButton = cancel;
                return dialog.ShowDialog(this) == DialogResult.OK ? choices[list.SelectedIndex] : null;
            }
        }
        private void ImportQrText(string text)
        {
            SetSeparatorMode(true); input.Text = text; ParseInput();
        }
        private void ParseInput()
        {
            qrImportVersion++;
            placeholder.Visible = input.TextLength == 0;
            ResetResult();
            selectedHistory = Guid.Empty;
            try {
                data = SmartParser.Parse(input.Text, automatic ? null : manualSeparator.Text);
                ShowResult();
                string kind = data.InputKind == InputKind.Secret ? "纯 2FA 密钥" : data.InputKind == InputKind.AuthenticatorUri ? "Authenticator URI" : data.UsesUri ? "账号记录 + Authenticator URI" : "账号记录";
                SetStatus("已识别 " + kind + (data.InputKind == InputKind.Record ? " · 分隔符 " + SeparatorPreview(data.FirstSeparator) + (data.FirstSeparator == data.LastSeparator ? "" : " / " + SeparatorPreview(data.LastSeparator)) : ""), false);
                try {
                    HistoryEntry saved = history.RecordWithRetention(data, automatic, newRetention); selectedHistory = saved.Id; newRetention = null; RefreshExpiryButton(); replaceOtp.Enabled = true; RefreshHistory();
                    nextCode.Enabled = data.OtpKind == OtpKind.Hotp && data.Counter < UInt64.MaxValue;
                } catch (InvalidOperationException error) { SetStatus(error.Message, true); }
                catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus(error is FormatException ? error.Message : "解析成功，但历史记录保存失败；请检查本机历史文件。", true); }
            } catch (FormatException error) { SetStatus(error.Message, true); }
        }
        private static string SeparatorPreview(string value) { value = value.Replace("\t", "\\t"); return "「" + (value.Length > 12 ? value.Substring(0, 12) + "…" : value) + "」"; }
        private void SetCopyEnabled(bool enabled)
        {
            accountCopy.Enabled = enabled && data != null && data.Account.Length > 0;
            passwordCopy.Enabled = enabled && data != null && data.Password.Length > 0;
            secretCopy.Enabled = codeCopy.Enabled = allCopy.Enabled = enabled;
            replaceOtp.Enabled = enabled && selectedHistory != Guid.Empty;
        }
        private void ClearInput() { selectedHistory = Guid.Empty; newRetention = null; RefreshExpiryButton(); qrImportVersion++; input.Clear(); ResetResult(); SetStatus("已清空当前内容", false); input.Focus(); }
        private void ResetResult()
        {
            if (data != null) { data.Dispose(); data = null; } lastStep = -1;
            account.Text = password.Text = secret.Text = "等待解析"; account.ForeColor = password.ForeColor = secret.ForeColor = Color.FromArgb(160, 172, 190);
            password.UseSystemPasswordChar = false;
            codeLabel.Text = "--- ---"; codeLabel.ForeColor = Color.FromArgb(121, 151, 197); codeLabel.AccessibleName = "当前六位验证码，等待解析";
            resultBadge.Text = "解析后自动生成，每 30 秒刷新"; countdown.Text = "等待解析"; countdown.ForeColor = Color.FromArgb(174, 190, 216); progress.Fraction = 0; SetCopyEnabled(false);
            nextCode.Visible = false; nextCode.Enabled = false; counterOptions.ColumnStyles[1].Width = 0; progress.Visible = true;
        }
        private void UpdateCode()
        {
            if (data == null) return; long now = Totp.UnixNow();
            long step = data.OtpKind == OtpKind.Hotp ? 0 : now / data.Period;
            if (step != lastStep) { string code = data.Code(now); int group = code.Length / 2; codeLabel.Text = code.Substring(0, group) + " " + code.Substring(group); codeLabel.ForeColor = Color.White; codeLabel.AccessibleName = "当前验证码 " + code; lastStep = step; }
            if (data.OtpKind == OtpKind.Hotp) { countdown.Text = "计数器 " + data.Counter; return; }
            int remaining = data.Period - (int)(now % data.Period); countdown.Text = remaining + " 秒后更新";
            countdown.ForeColor = remaining <= 5 ? Color.FromArgb(255, 199, 112) : Color.FromArgb(174, 190, 216);
            progress.BarColor = remaining <= 5 ? Color.FromArgb(255, 194, 99) : Color.FromArgb(123, 164, 255); progress.Fraction = remaining / (float)data.Period;
        }
        private void Copy(string value, string label, ActionButton button)
        {
            if (!EnsureCurrentRecord()) return;
            try { Clipboard.SetText(value, TextDataFormat.UnicodeText); button.ShowCopied(); SetStatus(label + "已复制", false); }
            catch (ExternalException) { SetStatus("剪贴板正被其他程序占用，请重新点击复制。", true); }
        }
        private void SetIdleStatus() {
            status.Text = "●  本地离线运行 · 最近 10 条记录加密保存"; status.ForeColor = Theme.Muted; transientStatus = false;
            if (historyStatus != null) { historyStatus.Text = "名称与记录在本机加密保存"; historyStatus.ForeColor = Theme.Muted; }
        }
        private void SetStatus(string text, bool error) {
            status.Text = text; status.ForeColor = error ? Color.FromArgb(197, 64, 77) : Color.FromArgb(32, 137, 105);
            if (historyStatus != null) { historyStatus.Text = text; historyStatus.ForeColor = status.ForeColor; }
            transientStatus = !error; statusUntil = DateTime.UtcNow.AddSeconds(7);
        }
        private Font CodeFont(int size)
        {
            Font font;
            if (!codeFonts.TryGetValue(size, out font)) { font = new Font("Consolas", size, FontStyle.Bold); codeFonts.Add(size, font); }
            return font;
        }
        private void ScheduleCodeFont()
        {
            if (codeFontPending || !IsHandleCreated || IsDisposed) return;
            codeFontPending = true;
            BeginInvoke((MethodInvoker)delegate { codeFontPending = false; if (!IsDisposed) FitCodeFont(); });
        }
        private void FitCodeFont()
        {
            if (codeLabel == null || codeLabel.ClientSize.Width < 1 || codeLabel.ClientSize.Height < 1) return;
            float size = data != null && data.Digits == 8 ? 34 : 41;
            while (size > 20) {
                using (Font candidate = new Font("Consolas", size, FontStyle.Bold)) {
                    Size measured = TextRenderer.MeasureText(codeLabel.Text, candidate, new Size(Int32.MaxValue, Int32.MaxValue), TextFormatFlags.NoPadding | TextFormatFlags.SingleLine);
                    if (measured.Width <= codeLabel.ClientSize.Width - 8 && measured.Height <= codeLabel.ClientSize.Height - 2) break;
                }
                size--;
            }
            if (Math.Abs(codeLabel.Font.Size - size) > .1F) codeLabel.Font = CodeFont((int)size);
        }

        protected override void Dispose(bool disposing)
        {
            base.Dispose(disposing);
            if (disposing) { foreach (Font font in codeFonts.Values) font.Dispose(); codeFonts.Clear(); }
        }

        private void ShowResult()
        {
            account.Text = data.Account.Length > 0 ? data.Account : "未提供账号";
            password.Text = data.Password.Length > 0 ? data.Password : "未提供密码"; secret.Text = data.Secret;
            account.ForeColor = data.Account.Length > 0 ? Theme.Ink : Theme.Muted;
            password.ForeColor = data.Password.Length > 0 ? Theme.Ink : Theme.Muted; secret.ForeColor = Theme.Ink;
            password.UseSystemPasswordChar = hidePassword.Checked && data.Password.Length > 0;
            resultBadge.Text = (data.Issuer.Length > 0 ? data.Issuer + " · " : "") + data.Algorithm + " · " + data.Digits + " 位 · " + (data.OtpKind == OtpKind.Hotp ? "HOTP" : data.Period + " 秒");
            resultBadge.AutoEllipsis = true;
            bool hotp = data.OtpKind == OtpKind.Hotp; nextCode.Visible = hotp;
            nextCode.Enabled = false; counterOptions.ColumnStyles[1].Width = hotp ? 92 : 0; progress.Visible = !hotp;
            SetCopyEnabled(true); lastStep = -1; UpdateCode(); ScheduleCodeFont();
        }

        private Card CreateHistoryCard()
        {
            Card card = new Card { Dock = DockStyle.Fill, Margin = new Padding(0), Padding = new Padding(16) };
            TableLayoutPanel layout = Table(1, 5); layout.Dock = DockStyle.Fill;
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
            layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 26));
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 32));
            TableLayoutPanel heading = Table(3, 1); heading.Dock = DockStyle.Fill;
            heading.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); heading.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 108)); heading.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 108));
            heading.Controls.Add(Label("历史记录管理", 12.5F, FontStyle.Bold, Theme.Ink), 0, 0);
            ActionButton import = Button("加密导入", ButtonKind.Ghost, 104), export = Button("多选导出", ButtonKind.Ghost, 104);
            import.Click += delegate { ImportHistory(); }; export.Click += delegate { ExportHistory(); };
            heading.Controls.Add(import, 1, 0); heading.Controls.Add(export, 2, 0); layout.Controls.Add(heading, 0, 0);
            historyCount = Label("0 / 10 条 · 点击记录恢复", 8.5F, FontStyle.Regular, Theme.Muted);
            layout.Controls.Add(historyCount, 0, 1);
            historyList = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoScroll = true, WrapContents = true,
                FlowDirection = FlowDirection.LeftToRight, BackColor = Color.White, Margin = new Padding(0) };
            historyList.SizeChanged += delegate { ResizeHistoryItems(); };
            layout.Controls.Add(historyList, 0, 2);
            historyStatus = Label("名称与记录在本机加密保存", 8.5F, FontStyle.Regular, Theme.Muted); historyStatus.AutoEllipsis = true;
            layout.Controls.Add(historyStatus, 0, 3);
            clearHistory = Button("清空历史记录", ButtonKind.Ghost, 180); clearHistory.Dock = DockStyle.Fill;
            clearHistory.Click += delegate {
                if (MessageBox.Show(this, "清空所有历史记录？已恢复的当前记录也会清除。", "账号验证管家",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
                try { history.Clear(); if (selectedHistory != Guid.Empty) InvalidateSavedResult(); RefreshHistory(); SetStatus("历史记录已清空", false); }
                catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("无法清空历史记录，请检查文件是否可写。", true); }
            };
            layout.Controls.Add(clearHistory, 0, 4); card.Controls.Add(layout); return card;
        }

        private void ToggleHistory()
        {
            SetHistoryPage(!historyExpanded);
            if (historyExpanded) {
                try { history.Load(); RefreshHistory(); }
                catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("历史记录读取失败，原文件已保留。", true); }
            }
        }

        private void SetHistoryPage(bool visible)
        {
            historyExpanded = visible; mainPage.Visible = !visible; historyCard.Visible = visible;
            if (visible) { historyCard.BringToFront(); contentHost.PerformLayout(); ResizeHistoryItems(); }
            else { mainPage.BringToFront(); input.Focus(); ScheduleCodeFont(); }
            historyToggle.Text = visible ? "返回解析" : "历史记录 " + history.Entries.Count + "/10";
            historyToggle.AccessibleName = visible ? "返回解析页" : "查看历史记录";
        }

        private void RefreshHistory()
        {
            historyList.SuspendLayout();
            while (historyList.Controls.Count > 0) { Control old = historyList.Controls[0]; historyList.Controls.RemoveAt(0); old.Dispose(); }
            historyCount.Text = history.Entries.Count + " / 10 条 · 点击记录恢复";
            historyToggle.Text = historyExpanded ? "返回解析" : "历史记录 " + history.Entries.Count + "/10";
            historyToggle.AccessibleName = historyExpanded ? "返回解析页" : "查看历史记录";
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
            int available = Math.Max(180, historyList.ClientSize.Width - SystemInformation.VerticalScrollBarWidth - 2);
            int columns = available >= 660 ? 2 : 1;
            for (int i = 0; i < historyList.Controls.Count; i++) {
                Control item = historyList.Controls[i];
                bool empty = item is Label;
                item.Width = empty ? available : (available - (columns - 1) * 8) / columns;
                item.Margin = new Padding(0, 0, !empty && columns == 2 && i % 2 == 0 ? 8 : 0, 8);
            }
        }

        private Control CreateHistoryItem(HistoryEntry entry)
        {
            bool named = entry.Name.Length > 0;
            Card card = new Card { Height = named ? 158 : 136, Padding = new Padding(6), Margin = new Padding(0, 0, 0, 8), Radius = 9,
                BorderColor = entry.Id == selectedHistory ? Color.FromArgb(139, 168, 250) : Theme.Line };
            card.Tag = entry.Id;
            TableLayoutPanel content = Table(1, named ? 5 : 4); content.Dock = DockStyle.Fill;
            content.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
            if (named) content.RowStyles.Add(new RowStyle(SizeType.Absolute, 22));
            content.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            content.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
            content.RowStyles.Add(new RowStyle(SizeType.Absolute, 32));
            TableLayoutPanel heading = Table(2, 1); heading.Dock = DockStyle.Fill;
            heading.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); heading.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 44));
            ActionButton open = Button(entry.DisplayName, ButtonKind.Ghost, 200); open.Dock = DockStyle.Fill; open.AlignLeft = true;
            open.Font = Theme.Font(9, FontStyle.Bold); open.AccessibleName = "打开历史记录 " + entry.DisplayName;
            open.Click += delegate { RestoreHistory(entry); }; heading.Controls.Add(open, 0, 0);
            ActionButton rename = Button(named ? "改名" : "命名", ButtonKind.Ghost, 44); rename.Dock = DockStyle.Fill;
            rename.Font = Theme.Font(8, FontStyle.Regular); rename.AccessibleName = "命名历史记录 " + entry.DisplayName;
            rename.Click += delegate { PromptHistoryName(entry.Id); }; heading.Controls.Add(rename, 1, 0); content.Controls.Add(heading, 0, 0);
            int row = 1;
            if (named) {
                Label identity = Label(entry.DefaultDisplayName, 8, FontStyle.Regular, Theme.Muted); identity.AutoEllipsis = true;
                identity.Padding = new Padding(8, 0, 0, 0); identity.Click += delegate { RestoreHistory(entry); }; content.Controls.Add(identity, 0, row++);
            }
            Label time = Label(entry.RecognizedAtUtc.ToLocalTime().ToString("MM-dd HH:mm") + "  ·  已加密保存", 8, FontStyle.Regular, Theme.Muted);
            time.Padding = new Padding(8, 0, 0, 0); time.Click += delegate { RestoreHistory(entry); }; content.Controls.Add(time, 0, row++);
            TableLayoutPanel actions = Table(4, 1); actions.Dock = DockStyle.Fill;
            for (int i = 0; i < 4; i++) actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 25));
            string[] captions = { "账号", "密码", "验证码", "整组" };
            for (int i = 0; i < captions.Length; i++) {
                int kind = i; ActionButton copy = Button(captions[i], ButtonKind.Copy, 48); copy.Dock = DockStyle.Fill;
                copy.Font = Theme.Font(8, FontStyle.Regular); copy.Margin = new Padding(1, 0, 1, 0);
                copy.AccessibleName = "历史记录 " + entry.Account + " 复制" + captions[i];
                copy.Enabled = kind == 0 ? entry.Account.Length > 0 : kind == 1 ? entry.Password.Length > 0 : true;
                copy.Click += delegate { CopyHistory(entry.Id, kind, captions[kind], copy); };
                actions.Controls.Add(copy, i, 0);
            }
            content.Controls.Add(actions, 0, row++);
            ActionButton manage = Button((entry.Pinned ? "已置顶 · " : "") + (entry.ExpiresAtUtc.HasValue ? "到期 " + entry.ExpiresAtUtc.Value.ToLocalTime().ToString("yyyy-MM-dd HH:mm") : "永久保留") + "  ·  管理", ButtonKind.Ghost, 300);
            manage.Dock = DockStyle.Fill; manage.Font = Theme.Font(8, FontStyle.Regular); manage.AlignLeft = true;
            manage.AccessibleName = "管理历史记录 " + entry.DisplayName;
            manage.Click += delegate { PromptManagement(entry.Id, manage); }; content.Controls.Add(manage, 0, row);
            card.Click += delegate { RestoreHistory(entry); };
            card.Controls.Add(content); return card;
        }

        private void TransferError(Exception error)
        {
            SetStatus(error is FormatException || error is InvalidOperationException ? error.Message : "文件操作失败，请检查文件或存储位置后重试。", true);
        }
        private void ExportHistory()
        {
            if (transferBusy) { SetStatus("正在处理文件，请稍候。", false); return; }
            try {
                history.Load(); List<HistoryEntry> selected;
                using (TransferSelectionDialog selection = new TransferSelectionDialog("选择导出的历史记录", history.Entries)) { if (selection.ShowDialog(this) != DialogResult.OK) return; selected = selection.Selected; }
                string password;
                using (TransferPasswordDialog prompt = new TransferPasswordDialog(true)) { if (prompt.ShowDialog(this) != DialogResult.OK) return; password = prompt.Password; }
                string path;
                using (SaveFileDialog picker = new SaveFileDialog { Title = "保存加密历史文件", Filter = "加密历史文件|*.avmx", FileName = "账号验证管家-" + DateTime.Now.ToString("yyyyMMdd-HHmm") + ".avmx", DefaultExt = "avmx" }) { if (picker.ShowDialog(this) != DialogResult.OK) return; path = picker.FileName; }
                // Re-read the chosen IDs after modal interaction; deleted or expired records cannot be exported.
                for (int i = 0; i < selected.Count; i++) { selected[i] = history.Find(selected[i].Id); if (selected[i] == null) throw new FormatException("所选记录已到期或被删除，请重新选择。"); }
                transferBusy = true; SetStatus("正在加密导出…", false);
                Task.Factory.StartNew(delegate {
                    byte[] encrypted = HistoryTransfer.Encrypt(selected, password); string temporary = path + ".tmp." + Guid.NewGuid().ToString("N");
                    try { File.WriteAllBytes(temporary, encrypted); if (File.Exists(path)) File.Replace(temporary, path, null); else File.Move(temporary, path); }
                    finally { if (File.Exists(temporary)) File.Delete(temporary); }
                }).ContinueWith(task => { transferBusy = false; if (IsDisposed) return; if (task.IsFaulted) TransferError(task.Exception.GetBaseException()); else SetStatus("已导出 " + selected.Count + " 条记录到密码加密文件。", false); }, TaskScheduler.FromCurrentSynchronizationContext());
            } catch (Exception error) { if (!HistoryFailure(error) && !(error is InvalidOperationException)) throw; TransferError(error); }
        }
        private void ImportHistory()
        {
            if (transferBusy) { SetStatus("正在处理文件，请稍候。", false); return; }
            string path, password;
            using (OpenFileDialog picker = new OpenFileDialog { Title = "选择加密历史文件", Filter = "加密历史文件|*.avmx" }) { if (picker.ShowDialog(this) != DialogResult.OK) return; path = picker.FileName; }
            using (TransferPasswordDialog prompt = new TransferPasswordDialog(false)) { if (prompt.ShowDialog(this) != DialogResult.OK) return; password = prompt.Password; }
            transferBusy = true; SetStatus("正在解密文件…", false);
            Task.Factory.StartNew(delegate {
                using (FileStream file = File.OpenRead(path)) { if (file.Length > HistoryTransfer.MaxBytes) throw new FormatException("导入文件超过 2 MB。"); byte[] bytes = new byte[(int)file.Length]; int offset = 0, read; while (offset < bytes.Length && (read = file.Read(bytes, offset, bytes.Length - offset)) > 0) offset += read; if (offset != bytes.Length) throw new FormatException("文件内容不完整。"); return HistoryTransfer.Decrypt(bytes, password); }
            }).ContinueWith(task => {
                transferBusy = false; if (IsDisposed) return;
                if (task.IsFaulted) { TransferError(task.Exception.GetBaseException()); return; }
                using (TransferSelectionDialog selection = new TransferSelectionDialog("选择导入的记录", task.Result)) {
                    if (selection.ShowDialog(this) != DialogResult.OK) return;
                    try { int added = history.Import(selection.Selected); SynchronizeHistory(true); SetStatus("已导入 " + added + " 条；重复或已到期记录已跳过。", false); }
                    catch (Exception error) { if (!HistoryFailure(error) && !(error is InvalidOperationException)) throw; TransferError(error); }
                }
            }, TaskScheduler.FromCurrentSynchronizationContext());
        }

        private void RefreshExpiryButton()
        {
            if (expiryButton != null) expiryButton.Text = "保存期限：" + (newRetention == null ? "永久" : newRetention.Label);
        }
        private bool EnsureCurrentRecord()
        {
            if (data == null || selectedHistory == Guid.Empty) return true;
            try {
                if (history.Find(selectedHistory) != null) return true;
                InvalidateSavedResult(); return false;
            } catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("无法确认历史状态，请重试。", true); return false; }
        }
        private void InvalidateSavedResult()
        {
            selectedHistory = Guid.Empty; ResetResult(); input.Clear();
            foreach (Form dialog in OwnedForms) if (dialog is OtpReplaceDialog) dialog.Close();
            SetStatus("当前记录已到期或被删除，内容已清除。", true);
        }
        private void SynchronizeHistory() { SynchronizeHistory(false); }
        private void SynchronizeHistory(bool force)
        {
            if (!force && DateTime.UtcNow < nextHistoryRefresh) return;
            nextHistoryRefresh = DateTime.UtcNow.AddSeconds(1);
            try {
                history.Load();
                bool selectedExists = false; foreach (HistoryEntry e in history.Entries) if (e.Id == selectedHistory) selectedExists = true;
                if (selectedHistory != Guid.Empty && data != null && !selectedExists) InvalidateSavedResult();
                string stamp = "";
                foreach (HistoryEntry e in history.Entries) stamp += e.Id + ":" + e.Name + ":" + e.ExpiresAtUtc + ":" + e.Pinned + ":" + e.RecognizedAtUtc.Ticks + ":" + e.Counter + ";";
                if (force || stamp != historyStamp) { historyStamp = stamp; RefreshHistory(); }
            } catch (Exception error) {
                if (!HistoryFailure(error)) throw;
                foreach (HistoryEntry e in history.Entries) if (e.Id == selectedHistory && e.IsExpired(DateTime.UtcNow)) { InvalidateSavedResult(); break; }
                SetStatus("历史清理失败，原文件已保留。", true);
            }
        }
        private void ManageHistory(Action action)
        {
            try { action(); SynchronizeHistory(true); }
            catch (InvalidOperationException error) { SetStatus(error.Message, true); }
            catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("保存失败，原历史文件已保留。", true); }
        }
        private void PromptManagement(Guid id, Control anchor)
        {
            try {
                HistoryEntry entry = history.Find(id);
                if (entry == null) { SynchronizeHistory(true); return; }
                DeferredContextMenu menu = managementMenu; menu.ResetActions();
                menu.AddAction("管理到期时间", delegate {
                    using (ExpirationDialog dialog = new ExpirationDialog(null, entry.ExpiresAtUtc, true, delegate(RetentionPeriod value) { history.SetExpiration(id, value == null ? (DateTime?)null : value.ExpiresAt(DateTime.UtcNow)); SynchronizeHistory(true); })) dialog.ShowDialog(this);
                });
                menu.AddAction(entry.Pinned ? "取消置顶" : "置顶", delegate { ManageHistory(delegate { history.SetPinned(id, !entry.Pinned); }); });
                menu.AddAction("删除记录", delegate {
                    if (MessageBox.Show(this, "删除这条记录及其账号、密码和 2FA 密钥？", "删除记录", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning) == DialogResult.OK)
                        ManageHistory(delegate { history.Delete(id); });
                });
                menu.Show(anchor, new Point(0, anchor.Height));
            } catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("历史读取失败，请重试。", true); }
        }

        private void PromptReplaceOtp()
        {
            try {
                HistoryEntry entry = history.Find(selectedHistory);
                if (entry == null) { SetStatus("请先识别或打开一条已保存的历史记录。", true); return; }
                using (OtpReplaceDialog dialog = new OtpReplaceDialog(entry, delegate(string text) {
                    HistoryEntry updated = history.ReplaceOtp(entry, text); RestoreHistory(updated);
                    SetStatus("2FA 已更换，账号、密码和名称已保留。", false);
                }, delegate { try { HistoryEntry current = history.Find(entry.Id); return current != null && current.OriginalText() == entry.OriginalText(); } catch (Exception error) { if (!HistoryFailure(error)) throw; return false; } })) dialog.ShowDialog(this);
            } catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("历史记录无法读取，原文件已保留。", true); }
        }

        private void PromptHistoryName(Guid id)
        {
            try {
                HistoryEntry entry = history.Find(id);
                if (entry == null) { SetStatus("记录已被移除，请刷新历史后重试。", true); return; }
                using (HistoryNameDialog dialog = new HistoryNameDialog(entry.DefaultDisplayName, entry.Name, delegate(string value) { RenameHistory(id, value); }))
                    dialog.ShowDialog(this);
            } catch (Exception e) { if (!HistoryFailure(e)) throw; SetStatus("无法读取历史记录，原文件已保留。", true); }
        }

        private void RenameHistory(Guid id, string name)
        {
            HistoryEntry renamed = history.Rename(id, name); RefreshHistory();
            SetStatus(renamed.Name.Length > 0 ? "已保存记录名称。" : "已恢复默认名称。", false);
        }

        private void RestoreHistory(HistoryEntry entry)
        {
            try { entry = history.Find(entry.Id); }
            catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("历史读取失败，原文件已保留。", true); return; }
            if (entry == null) { SynchronizeHistory(true); SetStatus("记录已到期或被移除。", true); return; }
            if (historyExpanded) SetHistoryPage(false);
            ResetResult(); SetSeparatorMode(entry.Automatic);
            if (!entry.Automatic) manualSeparator.Text = entry.FirstSeparator;
            input.Text = entry.OriginalText(); placeholder.Visible = false;
            data = entry.Open(); selectedHistory = entry.Id; ShowResult(); RefreshHistory();
            nextCode.Enabled = data.OtpKind == OtpKind.Hotp && data.Counter < UInt64.MaxValue;
            SetStatus("已恢复历史记录，可复制账号、密码和当前验证码。", false);
        }

        internal static bool HistoryFailure(Exception error)
        {
            return error is IOException || error is UnauthorizedAccessException || error is CryptographicException ||
                error is FormatException || error is System.Security.SecurityException;
        }

        private void AdvanceHotp()
        {
            if (data == null || data.OtpKind != OtpKind.Hotp) return;
            try { HistoryEntry entry = history.AdvanceCounter(selectedHistory, data.Counter); RestoreHistory(entry); SetStatus("HOTP 计数器已加一并保存，复制不会再次递增。", false); }
            catch (InvalidOperationException error) { SetStatus(error.Message, true); }
            catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("计数器保存失败，当前验证码未改变。", true); }
        }

        private void RefreshHotp()
        {
            if (data == null || data.OtpKind != OtpKind.Hotp || selectedHistory == Guid.Empty) return;
            HistoryEntry current = history.Find(selectedHistory);
            if (current == null) throw new InvalidOperationException("历史记录已移除，请重新识别当前 URI。");
            if (current.Counter != data.Counter) RestoreHistory(current);
        }

        private void CopyHistory(Guid id, int kind, string caption, ActionButton button)
        {
            try {
                HistoryEntry current = history.Find(id);
                if (current == null) throw new InvalidOperationException("历史记录已更新，请重新展开历史面板。");
                string value = kind == 0 ? current.Account : kind == 1 ? current.Password :
                    kind == 2 ? current.CurrentCode(Totp.UnixNow()) : current.CopyGroup(Totp.UnixNow());
                Clipboard.SetText(value, TextDataFormat.UnicodeText); button.ShowCopied(); SetStatus("历史" + caption + "已复制", false);
            }
            catch (ExternalException) { SetStatus("剪贴板暂时不可用，请重试。", true); }
            catch (InvalidOperationException error) { SetStatus(error.Message, true); }
            catch (Exception error) { if (!HistoryFailure(error)) throw; SetStatus("无法读取历史记录，请稍后重试。", true); }
        }
    }
    // A dropdown still performs handle/owner bookkeeping after Closed is raised.
    // Keep it alive until its form is disposed; run actions after the click unwinds,
    // so modal dialogs and history-row replacement cannot interrupt that bookkeeping.
    internal sealed class DeferredContextMenu : ContextMenuStrip
    {
        private readonly Form owner;
        public DeferredContextMenu(Form owner) { this.owner = owner; owner.Disposed += OwnerDisposed; }
        private void OwnerDisposed(object sender, EventArgs e) { Dispose(); }
        public void ResetActions()
        {
            if (Visible) Close();
            while (Items.Count > 0) { ToolStripItem item = Items[0]; Items.RemoveAt(0); item.Dispose(); }
        }
        public void AddAction(string label, Action action)
        {
            Items.Add(label, null, delegate {
                if (owner.IsDisposed || owner.Disposing || !owner.IsHandleCreated) return;
                owner.BeginInvoke((Action)delegate {
                    if (!owner.IsDisposed && !owner.Disposing) action();
                });
            });
        }
        protected override void Dispose(bool disposing)
        {
            if (disposing) owner.Disposed -= OwnerDisposed;
            base.Dispose(disposing);
        }
    }
    internal sealed class ExpirationDialog : Form
    {
        private readonly CheckBox enabled;
        private readonly TextBox amount;
        private readonly ComboBox unit;
        private readonly Label feedback;
        private readonly Action<RetentionPeriod> commit;
        private const string Help = "不勾选表示永久保留。1天=24时，1周=7天，1月=30天。\n到期自动删除；关闭期间到期，下次启动清理。";
        public ExpirationDialog(RetentionPeriod current, DateTime? existing, bool managing, Action<RetentionPeriod> commit)
        {
            this.commit = commit; Text = managing ? "管理到期时间" : "保存期限"; ClientSize = new Size(450, 310); Font = Theme.Font(10, FontStyle.Regular);
            AutoScaleDimensions = new SizeF(96, 96); AutoScaleMode = AutoScaleMode.Dpi;
            BackColor = Theme.Background; StartPosition = FormStartPosition.CenterParent; FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = MinimizeBox = false;
            Label note = new Label { Text = managing ? "当前到期：" + (existing.HasValue ? existing.Value.ToLocalTime().ToString("yyyy-MM-dd HH:mm") : "永久保留") + "\n保存后从现在起按新期限重新计时。" : "输入数字并选择单位，如 30 分、2 时、7 天。\n首次保存成功时开始计时，重复解析保留原期限。", Bounds = new Rectangle(24, 20, 402, 68), ForeColor = Theme.Muted };
            enabled = new CheckBox { Text = "设置保存期限", Bounds = new Rectangle(24, 94, 390, 28), Checked = current != null || existing.HasValue };
            amount = new TextBox { Text = current == null ? "1" : current.Amount.ToString(), MaxLength = 32, AccessibleName = "保存期限数字", Bounds = new Rectangle(24, 134, 266, 30) };
            unit = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, AccessibleName = "保存期限单位", Bounds = new Rectangle(306, 134, 120, 30) };
            unit.Items.AddRange(new object[] { "分", "时", "天", "周", "月" }); unit.SelectedIndex = current == null ? 2 : current.Unit;
            amount.Enabled = unit.Enabled = enabled.Checked;
            feedback = new Label { Text = Help, Bounds = new Rectangle(24, 183, 402, 60), ForeColor = Theme.Muted };
            enabled.CheckedChanged += delegate { amount.Enabled = unit.Enabled = enabled.Checked; ResetFeedback(); if (enabled.Checked) { amount.Focus(); amount.SelectAll(); } };
            amount.TextChanged += delegate { ResetFeedback(); }; unit.SelectedIndexChanged += delegate { ResetFeedback(); };
            ActionButton save = new ActionButton { Text = "保存", AccessibleName = "保存到期时间", Kind = ButtonKind.Primary, Bounds = new Rectangle(308, 254, 118, 36) };
            ActionButton cancel = new ActionButton { Text = "取消", Kind = ButtonKind.Ghost, Bounds = new Rectangle(182, 254, 118, 36) };
            save.Click += delegate { Save(); }; cancel.Click += delegate { DialogResult = DialogResult.Cancel; Close(); };
            Controls.AddRange(new Control[] { note, enabled, amount, unit, feedback, save, cancel });
            Shown += delegate { if (enabled.Checked) { amount.Focus(); amount.SelectAll(); } };
        }
        private void ResetFeedback() { feedback.Text = Help; feedback.ForeColor = Theme.Muted; }
        private void Save()
        {
            try {
                RetentionPeriod value = enabled.Checked ? RetentionPeriod.Parse(amount.Text, unit.SelectedIndex) : null;
                if (value != null) value.ExpiresAt(DateTime.UtcNow);
                commit(value); DialogResult = DialogResult.OK; Close();
            } catch (InvalidOperationException error) { feedback.Text = error.Message; feedback.ForeColor = Color.Firebrick; }
            catch (Exception error) { if (!MainForm.HistoryFailure(error)) throw; feedback.Text = error is FormatException ? error.Message : "保存失败，原记录已保留，请重试。"; feedback.ForeColor = Color.Firebrick; }
        }
        protected override bool ProcessDialogKey(Keys key) { if (key == Keys.Escape) { DialogResult = DialogResult.Cancel; Close(); return true; } if (key == Keys.Enter) { Save(); return true; } return base.ProcessDialogKey(key); }
    }
    internal sealed class HistoryNameDialog : Form
    {
        private readonly TextBox nameInput;
        private readonly Label characterCount, feedback, placeholder;
        private readonly ActionButton save, cancel;
        private readonly Action<string> saveName;

        public HistoryNameDialog(string identity, string currentName, Action<string> saveName)
        {
            this.saveName = saveName;
            Text = "历史记录命名"; FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterParent; ShowInTaskbar = false;
            MaximizeBox = false; MinimizeBox = false; BackColor = Theme.Background;
            Font = Theme.Font(10, FontStyle.Regular); AutoScaleDimensions = new SizeF(96, 96); AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(460, 334); Padding = new Padding(24, 22, 24, 22); DoubleBuffered = true;

            TableLayoutPanel layout = Grid(1, 10); layout.Dock = DockStyle.Fill;
            foreach (int height in new int[] { 32, 24, 12, 54, 12, 26, 44, 34, 12, 40 }) layout.RowStyles.Add(new RowStyle(SizeType.Absolute, height));
            layout.Controls.Add(Caption(currentName.Length > 0 ? "修改记录名称" : "给记录命名", 18, FontStyle.Bold, Theme.Ink), 0, 0);
            layout.Controls.Add(Caption("方便在历史记录中快速找到它。", 9, FontStyle.Regular, Theme.Muted), 0, 1);

            Card record = new Card { Dock = DockStyle.Fill, Radius = 10, Padding = new Padding(12, 7, 12, 7) };
            TableLayoutPanel recordText = Grid(1, 2); recordText.Dock = DockStyle.Fill;
            recordText.RowStyles.Add(new RowStyle(SizeType.Absolute, 17)); recordText.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            recordText.Controls.Add(Caption("当前记录", 8, FontStyle.Regular, Theme.Muted), 0, 0);
            Label recordIdentity = Caption(identity, 10, FontStyle.Bold, Theme.Ink); recordIdentity.AutoEllipsis = true;
            recordText.Controls.Add(recordIdentity, 0, 1); record.Controls.Add(recordText); layout.Controls.Add(record, 0, 3);

            TableLayoutPanel fieldTitle = Grid(2, 1); fieldTitle.Dock = DockStyle.Fill;
            fieldTitle.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); fieldTitle.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 72));
            fieldTitle.Controls.Add(Caption("记录名称", 9, FontStyle.Bold, Theme.Ink), 0, 0);
            characterCount = Caption("", 8.5F, FontStyle.Regular, Theme.Muted); characterCount.TextAlign = ContentAlignment.MiddleRight;
            fieldTitle.Controls.Add(characterCount, 1, 0); layout.Controls.Add(fieldTitle, 0, 5);

            InputFrame field = new InputFrame { Dock = DockStyle.Fill, FillColor = Color.White, Padding = new Padding(12, 10, 12, 9) };
            nameInput = new TextBox { Text = currentName, MaxLength = HistoryEntry.MaxNameLength, BorderStyle = BorderStyle.None,
                Dock = DockStyle.Fill, Font = Theme.Font(11, FontStyle.Regular), ForeColor = Theme.Ink, BackColor = Color.White,
                AccessibleName = "历史记录名称", TabIndex = 0 };
            placeholder = Caption("例如：工作邮箱、常用账号", 10, FontStyle.Regular, Theme.Muted); placeholder.BackColor = Color.White;
            placeholder.Click += delegate { nameInput.Focus(); };
            field.Controls.Add(nameInput); field.Controls.Add(placeholder); placeholder.BringToFront(); field.Watch(nameInput);
            layout.Controls.Add(field, 0, 6);
            feedback = Caption("", 8.5F, FontStyle.Regular, Theme.Muted); feedback.AutoEllipsis = true; layout.Controls.Add(feedback, 0, 7);

            TableLayoutPanel actions = Grid(4, 1); actions.Dock = DockStyle.Fill;
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 88));
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 10)); actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 112));
            actions.Controls.Add(Caption("Enter 保存 · Esc 取消", 8, FontStyle.Regular, Theme.Muted), 0, 0);
            cancel = new ActionButton { Text = "取消", Kind = ButtonKind.Secondary, Font = Font, Dock = DockStyle.Fill, Margin = new Padding(0), AccessibleName = "取消命名", TabIndex = 1 };
            save = new ActionButton { Kind = ButtonKind.Primary, Font = Theme.Font(10, FontStyle.Bold), Dock = DockStyle.Fill, Margin = new Padding(0), AccessibleName = "保存记录名称", TabIndex = 2 };
            cancel.Click += delegate { CancelEdit(); }; save.Click += delegate { SaveEdit(); };
            actions.Controls.Add(cancel, 1, 0); actions.Controls.Add(save, 3, 0); layout.Controls.Add(actions, 0, 9); Controls.Add(layout);
            nameInput.TextChanged += delegate { UpdateHint(); }; UpdateHint();
            Shown += delegate { nameInput.Focus(); nameInput.SelectAll(); };
        }

        private void UpdateHint()
        {
            characterCount.Text = nameInput.TextLength + " / " + HistoryEntry.MaxNameLength;
            characterCount.ForeColor = nameInput.TextLength == HistoryEntry.MaxNameLength ? Theme.Blue : Theme.Muted;
            placeholder.Visible = nameInput.TextLength == 0;
            feedback.Text = "留空保存可恢复默认名称。"; feedback.ForeColor = Theme.Muted;
            save.Text = nameInput.Text.Trim().Length == 0 ? "恢复默认" : "保存名称";
        }
        private void SaveEdit()
        {
            try { string value = HistoryEntry.NormalizeName(nameInput.Text); saveName(value); DialogResult = DialogResult.OK; Close(); }
            catch (FormatException error) { ShowError(error.Message); }
            catch (InvalidOperationException error) { ShowError(error.Message); }
            catch (Exception error) { if (!MainForm.HistoryFailure(error)) throw; ShowError("名称保存失败，原历史文件已保留。请重试。"); }
        }
        private void ShowError(string message) { feedback.Text = message; feedback.ForeColor = Color.FromArgb(197, 64, 77); nameInput.Focus(); }
        private void CancelEdit() { DialogResult = DialogResult.Cancel; Close(); }
        protected override bool ProcessDialogKey(Keys keyData)
        {
            if (keyData == Keys.Escape) { CancelEdit(); return true; }
            if (keyData == Keys.Enter) { if (cancel.ContainsFocus) CancelEdit(); else SaveEdit(); return true; }
            return base.ProcessDialogKey(keyData);
        }
        private static TableLayoutPanel Grid(int columns, int rows)
        {
            TableLayoutPanel result = new TableLayoutPanel { ColumnCount = columns, RowCount = rows, BackColor = Color.Transparent, Margin = new Padding(0) };
            if (columns == 1) result.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            if (rows == 1) result.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            return result;
        }
        private static Label Caption(string text, float size, FontStyle style, Color color)
        {
            return new Label { Text = text, Font = Theme.Font(size, style), ForeColor = color, Dock = DockStyle.Fill, Margin = new Padding(0),
                TextAlign = ContentAlignment.MiddleLeft, BackColor = Color.Transparent, UseMnemonic = false };
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
