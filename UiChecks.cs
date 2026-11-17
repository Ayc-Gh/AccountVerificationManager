using System;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;
using AccountSplit2FA;

// Integration checks use only this test process's form and synthetic data.
internal static class UiChecks
{
    private static int passed;
    private const string Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static void Check(bool condition, string label) { if (!condition) throw new Exception("FAIL: " + label); passed++; }
    private static T Field<T>(Form form, string name) { return (T)form.GetType().GetField(name, BindingFlags.Instance | BindingFlags.NonPublic).GetValue(form); }
    private static void Call(Form form, string name, params object[] args) { form.GetType().GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic).Invoke(form, args); }
    private static void Layout(Control control) { control.PerformLayout(); foreach (Control child in control.Controls) Layout(child); }
    private static bool Fits(Control control) { return control.Width > 0 && control.Height > 0 && control.Left >= 0 && control.Top >= 0 && control.Right <= control.Parent.ClientSize.Width && control.Bottom <= control.Parent.ClientSize.Height; }
    private static void Preview(Form form, string checks, string name) {
        using (System.Drawing.Bitmap preview = new System.Drawing.Bitmap(form.Width, form.Height)) {
            form.DrawToBitmap(preview, new System.Drawing.Rectangle(0, 0, form.Width, form.Height));
            preview.Save(Path.Combine(checks, name), System.Drawing.Imaging.ImageFormat.Png);
        }
    }
    private static void ShowOffscreen(Form form) {
        form.StartPosition = FormStartPosition.Manual; form.Location = new System.Drawing.Point(-20000, -20000);
        form.Show(); Application.DoEvents(); Layout(form);
    }
    private static void CheckLoginDialog(string checks, HistoryStore store)
    {
        using (AccountData d = AccountParser.Parse("alice@example.com|SyntheticPassword|" + Secret, "|")) store.Record(d, false);
        HistoryEntry entry = store.Entries[0]; bool available = true;
        using (LoginDialog dialog = new LoginDialog(delegate { return available ? entry : null; })) {
            ShowOffscreen(dialog);
            Check(Field<ComboBox>(dialog, "provider").Items.Count == 2 && Field<ComboBox>(dialog, "mode").Items.Count == 2, "both providers and both login modes");
            Check(!Field<Control>(dialog, "cpa").Enabled && !Field<Control>(dialog, "sub").Enabled && !Field<Control>(dialog, "finish").Enabled, "cannot export without successful login");
            Check(Field<TextBox>(dialog, "callback").UseSystemPasswordChar, "callback code masked in editor");
            foreach (string name in new string[] {"provider", "mode", "start", "finish", "callback", "cpa", "sub", "feedback"}) Check(Fits(Field<Control>(dialog, name)), "login control fits: " + name);
            Field<ComboBox>(dialog, "provider").SelectedIndex = 1; Field<ComboBox>(dialog, "mode").SelectedIndex = 1;
            Check(Field<ComboBox>(dialog, "provider").SelectedIndex == 1 && Field<ComboBox>(dialog, "mode").SelectedIndex == 1, "manual Claude login selectable");
            Preview(dialog, checks, "windows-v1.10.0-login.png");
            OAuthLogin flow = new OAuthLogin(LoginProvider.Claude, entry.Account);
            typeof(LoginDialog).GetField("login", BindingFlags.Instance | BindingFlags.NonPublic).SetValue(dialog, flow);
            typeof(LoginDialog).GetField("credential", BindingFlags.Instance | BindingFlags.NonPublic).SetValue(dialog, new LoginCredential(LoginProvider.Claude, entry.Account, "synthetic-access", "synthetic-refresh", "", "acct-demo", "", "", "org-demo", DateTime.UtcNow, DateTime.UtcNow.AddHours(1), flow.ClientId));
            Field<Control>(dialog, "cpa").Enabled = true; Field<Control>(dialog, "sub").Enabled = true;
            available = false;
            typeof(Timer).GetMethod("OnTick", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(Field<Timer>(dialog, "expiry"), new object[] {EventArgs.Empty});
            Check(Field<LoginCredential>(dialog, "credential") == null && Field<OAuthLogin>(dialog, "login") == null && !Field<Control>(dialog, "cpa").Enabled && !Field<Control>(dialog, "sub").Enabled, "expired or deleted record clears login and export state");
            Check(Field<ComboBox>(dialog, "provider").Enabled && Field<ComboBox>(dialog, "mode").Enabled, "invalidated login can restart or change mode");
            dialog.Close();
        }
        using (LoginDialog dialog = new LoginDialog(delegate { throw new IOException("synthetic history read failure"); })) {
            ShowOffscreen(dialog);
            typeof(LoginDialog).GetField("login", BindingFlags.Instance | BindingFlags.NonPublic).SetValue(dialog, new OAuthLogin(LoginProvider.OpenAI, entry.Account));
            typeof(Timer).GetMethod("OnTick", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(Field<Timer>(dialog, "expiry"), new object[] {EventArgs.Empty});
            Check(Field<OAuthLogin>(dialog, "login") == null && !Field<Control>(dialog, "cpa").Enabled, "history read failure clears login without an unhandled timer exception");
            Check(Field<Label>(dialog, "feedback").Text.Contains("失效"), "history read failure explains disabled login state");
            dialog.Close();
        }
    }
    private static void CheckNameDialog(string checks, HistoryStore store)
    {
        store.Load(); HistoryEntry entry = store.Entries[0];
        using (HistoryNameDialog dialog = new HistoryNameDialog(entry.DefaultDisplayName, entry.Name, delegate(string value) { store.Rename(entry.Id, value); })) {
            ShowOffscreen(dialog); TextBox name = Field<TextBox>(dialog, "nameInput");
            Check(name.Focused && name.SelectionLength == name.TextLength, "rename dialog focuses and selects current name");
            name.Text = "  工作邮箱  "; Application.DoEvents();
            Check(Field<Label>(dialog, "characterCount").Text == "8 / 40", "name counter follows actual edited text");
            foreach (string control in new string[] { "nameInput", "save", "cancel", "feedback" })
                Check(Fits(Field<Control>(dialog, control)), "rename " + control + " fits within its parent");
            Preview(dialog, checks, "windows-v1.7.2-rename.png");
            Call(dialog, "ProcessDialogKey", Keys.Enter); store.Load();
            Check(dialog.DialogResult == DialogResult.OK && store.Find(entry.Id).Name == "工作邮箱", "Enter saves trimmed name through encrypted history");
        }
        bool saved = false;
        using (HistoryNameDialog dialog = new HistoryNameDialog(entry.DefaultDisplayName, "工作邮箱", delegate(string value) { saved = true; })) {
            ShowOffscreen(dialog); Field<TextBox>(dialog, "nameInput").Text = "未保存的修改";
            Call(dialog, "ProcessDialogKey", Keys.Escape);
            Check(dialog.DialogResult == DialogResult.Cancel && !saved && store.Find(entry.Id).Name == "工作邮箱", "Escape cancels edited name without saving");
        }
        bool fail = true;
        using (HistoryNameDialog dialog = new HistoryNameDialog(entry.DefaultDisplayName, "工作邮箱", delegate(string value) { if (fail) throw new IOException("synthetic write failure"); store.Rename(entry.Id, value); })) {
            ShowOffscreen(dialog); Field<TextBox>(dialog, "nameInput").Text = "测试名称";
            Call(dialog, "ProcessDialogKey", Keys.Enter); store.Load();
            Check(dialog.Visible && Field<Label>(dialog, "feedback").Text.Contains("保存失败") && Field<TextBox>(dialog, "nameInput").Text == "测试名称" && store.Find(entry.Id).Name == "工作邮箱", "write failure stays open with edited text and original history intact");
            Preview(dialog, checks, "windows-v1.7.2-rename-error.png");
            Field<TextBox>(dialog, "nameInput").Text = "";
            Check(!Field<Label>(dialog, "feedback").Text.Contains("失败") && Field<Control>(dialog, "save").Text == "恢复默认" && Field<Control>(dialog, "placeholder").Visible, "empty edit clears old error and explains restore action");
            fail = false; Call(dialog, "ProcessDialogKey", Keys.Enter); store.Load();
            Check(dialog.DialogResult == DialogResult.OK && store.Find(entry.Id).Name == "", "retry with empty name restores default display");
        }
    }
    private static void CheckReplacementDialog(string checks, HistoryStore store)
    {
        using (AccountData data = AccountParser.Parse("replace@example.com| *Demo|Password* |" + Secret, "|")) store.Record(data, false);
        HistoryEntry entry = store.Rename(store.Entries[0].Id, "工作邮箱");
        bool fail = true;
        using (OtpReplaceDialog dialog = new OtpReplaceDialog(entry, delegate(string text) { if (fail) throw new IOException("synthetic failure"); store.ReplaceOtp(entry, text); })) {
            ShowOffscreen(dialog);
            Check(!Field<Control>(dialog, "save").Enabled && !Field<Control>(dialog, "copyNew").Enabled, "empty replacement cannot be saved or copied");
            TextBox input = Field<TextBox>(dialog, "replacementInput");
            input.Text = "invalid-key";
            Check(!Field<Control>(dialog, "save").Enabled && Field<AccountData>(dialog, "preview") == null && store.Find(entry.Id).Secret == Secret, "invalid draft leaves old history and disables replacement");
            input.Text = "otpauth://totp/New:ignored@example.com?secret=JBSWY3DPEHPK3PXP&issuer=New&algorithm=SHA256&digits=8&period=60";
            Application.DoEvents();
            Check(Field<Control>(dialog, "save").Enabled && Field<Label>(dialog, "newCode").Text.Replace(" ", "").Length == 8, "URI replacement previews eight digit code before saving");
            Check(Field<AccountData>(dialog, "preview").Account == entry.Account && Field<AccountData>(dialog, "preview").Password == entry.Password, "preview preserves exact original credentials");
            foreach (string control in new string[] { "save", "copyNew", "upload", "newCode", "replacementInput" }) Check(Fits(Field<Control>(dialog, control)), "replacement " + control + " fits dialog");
            Preview(dialog, checks, "windows-v1.8.0-replace.png");
            Call(dialog, "Save");
            Check(dialog.Visible && store.Find(entry.Id).Secret == Secret && Field<Label>(dialog, "feedback").Text.Contains("失败"), "failed replacement stays editable and keeps original record");
            fail = false; Call(dialog, "Save");
            HistoryEntry updated = store.Find(entry.Id);
            Check(dialog.DialogResult == DialogResult.OK && updated.Name == entry.Name && updated.Secret == "JBSWY3DPEHPK3PXP" && updated.Digits == 8, "retry replaces original record while preserving name");
        }
        entry = store.Find(entry.Id); bool saved = false;
        using (OtpReplaceDialog dialog = new OtpReplaceDialog(entry, delegate(string text) { saved = true; })) {
            ShowOffscreen(dialog); Field<TextBox>(dialog, "replacementInput").Text = Secret; Call(dialog, "ProcessDialogKey", Keys.Escape);
            Check(!saved && store.Find(entry.Id).Secret == "JBSWY3DPEHPK3PXP", "cancel replacement never commits the draft");
        }
    }
    private static void CheckTransferDialogs(string checks, HistoryStore store)
    {
        store.Load();
        using (TransferSelectionDialog dialog = new TransferSelectionDialog("选择导出的记录", store.Entries)) {
            ShowOffscreen(dialog); CheckedListBox list = Field<CheckedListBox>(dialog, "list");
            Check(Fits(list) && list.Items.Count == store.Entries.Count, "transfer list fits and contains active history entries");
            list.SetItemChecked(0, true); Preview(dialog, checks, "windows-v1.9.0-export.png");
            foreach (Control control in dialog.Controls) if (control.Text == "继续") { typeof(Control).GetMethod("OnClick", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(control, new object[]{EventArgs.Empty}); break; }
            Check(dialog.Selected.Count == 1 && dialog.Selected[0].Id == store.Entries[0].Id, "transfer selection returns only checked records");
        }
        using (TransferPasswordDialog dialog = new TransferPasswordDialog(true)) {
            ShowOffscreen(dialog); TextBox password = Field<TextBox>(dialog, "password"), confirm = Field<TextBox>(dialog, "confirmation");
            Check(password.UseSystemPasswordChar && confirm.UseSystemPasswordChar, "export password fields are masked");
            password.Text = "Synthetic password"; confirm.Text = "mismatch"; Call(dialog, "Save");
            Check(dialog.Visible && dialog.Password == null, "password mismatch keeps editor open without export");
            confirm.Text = password.Text; Call(dialog, "Save");
            Check(dialog.Password == "Synthetic password" && password.TextLength == 0 && confirm.TextLength == 0, "confirmed export password clears editor content");
        }
    }
    private static void CheckHistoryManagement(string checks, string path)
    {
        HistoryStore store = new HistoryStore(path); store.Clear();
        using (AccountData value = AccountParser.Parse("pinned@example.com----Demo----" + Secret)) store.SetPinned(store.Record(value, true).Id, true);
        using (MainForm form = new MainForm(path)) {
            ShowOffscreen(form);
            TextBox input = Field<TextBox>(form, "input"); input.Text = "new@example.com----Demo----" + Secret;
            typeof(MainForm).GetField("newRetention", BindingFlags.Instance | BindingFlags.NonPublic).SetValue(form, RetentionPeriod.Parse("1", 1));
            Call(form, "ParseInput"); store.Load();
            Guid selected = Field<Guid>(form, "selectedHistory");
            Check(store.Entries[0].Account == "pinned@example.com" && store.Find(selected).Account == "new@example.com", "parsing selects saved ID instead of pinned first entry");
            Check(store.Find(selected).ExpiresAtUtc == store.Find(selected).RecognizedAtUtc.AddHours(1) && Field<RetentionPeriod>(form, "newRetention") == null, "first parse starts duration at save time and resets input default");
            for (int width = 780; width <= 876; width += 96) {
                form.Width = width; Layout(form); Check(Fits(Field<Control>(form, "expiryButton")), "expiry control fits compact width " + width);
            }
            form.ClientSize = new System.Drawing.Size(860, 660); Call(form, "ToggleHistory"); Layout(form);
            Preview(form, checks, "windows-v1.9.0-history.png");
            store.Delete(selected); Field<HistoryStore>(form, "history").Load();
            typeof(MainForm).GetMethod("SynchronizeHistory", BindingFlags.Instance | BindingFlags.NonPublic, null, new Type[]{typeof(bool)}, null).Invoke(form, new object[]{true});
            Check(Field<AccountData>(form, "data") == null && input.TextLength == 0 && !Field<Control>(form, "codeCopy").Enabled, "deletion in another window invalidates selected credentials and copy buttons");
            form.Close();
        }
        for (int selectedUnit = 0; selectedUnit < 5; selectedUnit++) {
            RetentionPeriod saved = null;
            using (ExpirationDialog dialog = new ExpirationDialog(null, null, false, value => saved = value)) {
                ShowOffscreen(dialog); TextBox amount = Field<TextBox>(dialog, "amount"); ComboBox unit = Field<ComboBox>(dialog, "unit");
                Check(!amount.Enabled && !unit.Enabled, "expiry dialog defaults to permanent " + selectedUnit);
                Field<CheckBox>(dialog, "enabled").Checked = true;
                if (selectedUnit == 0) {
                    foreach (string invalid in new string[] { "", "0", "-1", "1.5", "abc" }) {
                        amount.Text = invalid; Call(dialog, "Save");
                        Check(dialog.Visible && saved == null && Field<Label>(dialog, "feedback").Text.Contains("整数"), "invalid duration stays editable: " + invalid);
                    }
                    amount.Text = "999999"; unit.SelectedIndex = 4; Call(dialog, "Save");
                    Check(dialog.Visible && saved == null && Field<Label>(dialog, "feedback").Text.Contains("过长"), "excessive duration cannot save");
                }
                amount.Text = "2"; unit.SelectedIndex = selectedUnit;
                Check(Fits(amount) && Fits(unit) && amount.Right < unit.Left, "duration number and adjacent unit fit " + selectedUnit);
                if (selectedUnit == 2) Preview(dialog, checks, "windows-v1.9.2-expiry.png");
                Call(dialog, "Save");
                Check(saved != null && saved.Amount == 2 && saved.Unit == selectedUnit && dialog.DialogResult == DialogResult.OK, "chosen duration saved " + selectedUnit);
            }
        }
        bool called = false;
        using (ExpirationDialog dialog = new ExpirationDialog(RetentionPeriod.Parse("3", 3), null, false, value => called = true)) {
            ShowOffscreen(dialog);
            Check(Field<TextBox>(dialog, "amount").Text == "3" && Field<ComboBox>(dialog, "unit").SelectedIndex == 3, "reopened duration preserves number and unit");
            Call(dialog, "ProcessDialogKey", Keys.Escape); Check(!called, "cancel duration does not change settings");
        }
        using (ExpirationDialog dialog = new ExpirationDialog(null, DateTime.UtcNow.AddDays(5), true, value => { called = true; Check(value == null, "disable expiry chooses permanent"); })) {
            ShowOffscreen(dialog); Field<CheckBox>(dialog, "enabled").Checked = false; Field<TextBox>(dialog, "amount").Text = "invalid";
            Call(dialog, "Save"); Check(called && dialog.DialogResult == DialogResult.OK, "permanent choice ignores disabled numeric field");
        }
        bool fail = true;
        using (ExpirationDialog dialog = new ExpirationDialog(RetentionPeriod.Parse("4", 1), null, true, value => { if (fail) throw new IOException("synthetic save failure"); })) {
            ShowOffscreen(dialog); Call(dialog, "Save");
            Check(dialog.Visible && Field<TextBox>(dialog, "amount").Text == "4" && Field<Label>(dialog, "feedback").Text.Contains("失败"), "duration write failure preserves editable draft");
            fail = false; Call(dialog, "Save"); Check(dialog.DialogResult == DialogResult.OK, "duration write can retry");
        }
    }
    [DllImport("user32.dll")] private static extern IntPtr GetWindow(IntPtr window, uint command);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr window, StringBuilder value, int capacity);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr window, StringBuilder value, int capacity);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr window, uint message, IntPtr value, IntPtr extra);

    private static void ClickDelete(MainForm form, DeferredContextMenu menu, bool confirm)
    {
        bool answered = false; DateTime deadline = DateTime.UtcNow.AddSeconds(5);
        using (Timer answer = new Timer { Interval = 20 }) {
            answer.Tick += delegate {
                IntPtr popup = GetWindow(form.Handle, 6); // GW_ENABLEDPOPUP, restricted to this test form/thread.
                StringBuilder title = new StringBuilder(100), kind = new StringBuilder(100); uint process;
                GetWindowText(popup, title, title.Capacity); GetClassName(popup, kind, kind.Capacity);
                if (GetWindowThreadProcessId(popup, out process) == GetCurrentThreadId() && kind.ToString() == "#32770" && title.ToString() == "删除记录") {
                    answer.Stop(); answered = true;
                    SendMessage(popup, 0x0111, new IntPtr(confirm ? 1 : 2), IntPtr.Zero); // WM_COMMAND: IDOK / IDCANCEL.
                } else if (DateTime.UtcNow > deadline) throw new Exception("Delete confirmation did not appear in the test thread.");
            };
            answer.Start(); menu.Items[2].PerformClick(); Application.DoEvents();
        }
        Check(answered && !menu.IsDisposed && !menu.Visible, "delete confirmation closes safely: " + confirm);
    }
    private static void CheckContextMenuLifetime(string path)
    {
        HistoryStore store = new HistoryStore(path); store.Clear(); HistoryEntry entry;
        using (AccountData data = AccountParser.Parse("menu@example.com|SyntheticPassword|" + Secret, "|")) entry = store.Record(data, false);
        DeferredContextMenu menu;
        using (MainForm form = new MainForm(path)) {
            ShowOffscreen(form); Call(form, "ToggleHistory");
            menu = Field<DeferredContextMenu>(form, "managementMenu");
            for (int i = 0; i < 4; i++) {
                Control anchor = Field<FlowLayoutPanel>(form, "historyList").Controls[0];
                Call(form, "PromptManagement", entry.Id, anchor);
                Check(menu.Visible && menu.Items[1].Text == (i % 2 == 0 ? "置顶" : "取消置顶"), "management menu reopens with current pin state " + i);
                menu.Items[1].PerformClick();
                Check(!menu.Visible && !menu.IsDisposed && store.Find(entry.Id).Pinned == (i % 2 != 0), "click closes menu before changing history " + i);
                Application.DoEvents();
                Check(store.Find(entry.Id).Pinned == (i % 2 == 0), "queued pin action updates history safely " + i);
            }
            Call(form, "PromptManagement", entry.Id, form); menu.Close(); Application.DoEvents();
            Check(!menu.IsDisposed && !store.Find(entry.Id).Pinned, "dismissing management menu keeps it reusable and changes nothing");
            bool edited = false;
            using (Timer answer = new Timer { Interval = 20 }) {
                DateTime deadline = DateTime.UtcNow.AddSeconds(5);
                answer.Tick += delegate {
                    foreach (Form owned in form.OwnedForms) if (owned is ExpirationDialog) {
                        answer.Stop(); edited = true;
                        Field<CheckBox>(owned, "enabled").Checked = true;
                        Field<TextBox>(owned, "amount").Text = "2"; Field<ComboBox>(owned, "unit").SelectedIndex = 2;
                        Call(owned, "Save"); return;
                    }
                    if (DateTime.UtcNow > deadline) throw new Exception("Expiration editor did not appear.");
                };
                Call(form, "PromptManagement", entry.Id, form); answer.Start(); menu.Items[0].PerformClick(); Application.DoEvents();
            }
            Check(edited && store.Find(entry.Id).ExpiresAtUtc.HasValue && !menu.IsDisposed, "expiry menu opens modal editor and saves after dropdown closes");
            Call(form, "PromptManagement", entry.Id, form); ClickDelete(form, menu, false);
            Check(store.Find(entry.Id) != null, "cancel from delete menu preserves history");
            Call(form, "PromptManagement", entry.Id, form); ClickDelete(form, menu, true);
            Check(store.Find(entry.Id) == null, "confirm from delete menu removes selected synthetic history");
            form.Close();
        }
        Check(menu.IsDisposed, "management menu is disposed with its form");
        using (OtpReplaceDialog dialog = new OtpReplaceDialog(entry, delegate(string text) { throw new Exception("QR choice must not commit replacement."); })) {
            ShowOffscreen(dialog); menu = Field<DeferredContextMenu>(dialog, "qrChoices");
            string[] choices = { Secret, "JBSWY3DPEHPK3PXP" };
            for (int i = 0; i < 3; i++) {
                Call(dialog, "ShowQrChoices", (object)choices); menu.Items[i % 2].PerformClick();
                Check(!menu.IsDisposed && !menu.Visible, "QR choice closes without disposing menu " + i);
                Application.DoEvents();
                Check(Field<TextBox>(dialog, "replacementInput").Text == choices[i % 2], "QR selection updates replacement draft " + i);
            }
            Call(dialog, "ShowQrChoices", (object)choices); menu.Close();
            Check(!menu.IsDisposed, "cancelled QR chooser remains reusable");
            dialog.Close();
        }
        Check(menu.IsDisposed, "QR menu is disposed with replacement dialog");
        bool ran = false;
        using (Form owner = new Form()) {
            ShowOffscreen(owner); menu = new DeferredContextMenu(owner); menu.AddAction("close race", delegate { ran = true; });
            menu.Show(owner, new System.Drawing.Point(0, 0)); menu.Items[0].PerformClick(); owner.Close(); Application.DoEvents();
        }
        Check(!ran && menu.IsDisposed, "closing owner discards pending action and disposes its menu");
    }
    [STAThread]
    private static void Main()
    {
        QrDependencies.Register();
        string checks = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
        string folder = Path.Combine(checks, "ui-history-" + Guid.NewGuid().ToString("N"));
        string path = Path.Combine(folder, "history.bin");
        try
        {
            HistoryStore store = new HistoryStore(path);
            for (int i = 1; i <= 12; i++)
                using (AccountData data = AccountParser.Parse("demo" + i + "@example.com------Test%_Password*=====" + Secret)) store.Record(data, true);
            using (AccountData data = AccountParser.Parse("manual@example.com|***Keep|Symbols***|" + Secret, "|")) store.Record(data, false);
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.ThrowException);
            Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
            using (MainForm form = new MainForm(path))
            {
                form.CreateControl(); Layout(form);
                form.StartPosition = FormStartPosition.Manual; form.Location = new System.Drawing.Point(-20000, -20000);
                form.Show(); Application.DoEvents(); Layout(form);
                Check(form.Text.StartsWith("账号验证管家"), "new window name");
                Check(form.ClientSize.Width <= 900 && form.MinimumSize.Width <= 800 && !Field<bool>(form, "historyExpanded"), "compact startup size with history on a separate page");
                Check(Field<FlowLayoutPanel>(form, "historyList").Controls.Count == 10, "history panel loads ten records at startup");
                Check(Field<AccountData>(form, "data") == null, "startup does not reveal saved credentials");
                HistoryEntry selected = store.Entries[0];
                Call(form, "RestoreHistory", selected);
                Check(Field<TextBox>(form, "account").Text == selected.Account, "history click restores correct account");
                Check(Field<TextBox>(form, "password").Text == "***Keep|Symbols***", "history click preserves password delimiters and edge symbols");
                Check(Field<TextBox>(form, "secret").Text == Secret, "history click restores correct key");
                Check(!Field<bool>(form, "automatic") && Field<TextBox>(form, "manualSeparator").Text == "|", "history click restores parsing mode");
                string code = Field<Label>(form, "codeLabel").Text.Replace(" ", "");
                long now = Totp.UnixNow();
                Check(code == selected.CurrentCode(now) || code == selected.CurrentCode(now - 1), "restored OTP reflects current time");
                store.Load(); Check(store.Entries.Count == 10 && store.Entries[0].Id == selected.Id, "history selection does not add a record");
                Field<TextBox>(form, "input").Text = "invalid input"; Call(form, "ParseInput");
                store.Load(); Check(store.Entries.Count == 10, "failed parsing does not save history");
                Check(!Field<Control>(form, "codeCopy").Enabled, "failed parsing disables stale OTP copying");
                int initialWidth = form.Width;
                Call(form, "ToggleHistory"); Layout(form);
                Check(Field<bool>(form, "historyExpanded") && form.Width == initialWidth && !Field<Control>(form, "mainPage").Visible, "history page opens without widening the window");
                Call(form, "ToggleHistory"); Check(!Field<bool>(form, "historyExpanded") && form.Width == initialWidth, "returning to parse preserves width");
                Call(form, "RestoreHistory", store.Entries[1]); Layout(form);
                Call(form, "ToggleHistory"); Layout(form);
                Control historyCard = Field<Control>(form, "historyCard");
                Check(historyCard.Width >= 230 && historyCard.Height > 400, "history panel has room for record actions and scrolling");
                FlowLayoutPanel list = Field<FlowLayoutPanel>(form, "historyList");
                foreach (Control row in list.Controls)
                    Check(row.Width <= list.ClientSize.Width && row.Height >= 90, "record buttons fit within the history panel");
                Check(!list.HorizontalScroll.Visible, "history grid never requires horizontal scrolling");
                Call(form, "ToggleHistory");
                Field<TextBox>(form, "input").Text = Secret; Call(form, "ParseInput");
                Check(Field<AccountData>(form, "data").InputKind == InputKind.Secret, "UI detects standalone key");
                Check(!Field<Control>(form, "accountCopy").Enabled && !Field<Control>(form, "passwordCopy").Enabled && Field<Control>(form, "codeCopy").Enabled, "missing credentials are not copyable but key OTP is");
                HistoryStore smart = new HistoryStore(path); smart.Load();
                Call(form, "RestoreHistory", smart.Entries[0]);
                Check(Field<TextBox>(form, "input").Text == Secret && Field<TextBox>(form, "password").Text == "未提供密码", "pure key history restores its own input format");
                Field<TextBox>(form, "input").Text = "otpauth://totp/Service:uri@example.com?secret=" + Secret + "&algorithm=SHA256&digits=8&period=60";
                Call(form, "ParseInput");
                AccountData uriData = Field<AccountData>(form, "data");
                Check(uriData.Period == 60 && uriData.Digits == 8 && uriData.Algorithm == "SHA256", "UI preserves URI parameters");
                Check(Field<Label>(form, "codeLabel").Text.Replace(" ", "").Length == 8 && Field<Control>(form, "accountCopy").Enabled && !Field<Control>(form, "passwordCopy").Enabled, "8 digit URI display and missing password");
                Field<TextBox>(form, "input").Text = "otpauth://hotp/hotp@example.com?secret=" + Secret + "&counter=1";
                Call(form, "ParseInput");
                Check(Field<Control>(form, "nextCode").Enabled && Field<AccountData>(form, "data").Counter == 1, "HOTP next action enabled after persisted recognition");
                Call(form, "AdvanceHotp");
                Check(Field<AccountData>(form, "data").Counter == 2 && Field<Label>(form, "codeLabel").Text == "359 152", "HOTP next action advances and restores correct result");
                smart.Load(); HistoryEntry current = smart.Entries[0]; smart.AdvanceCounter(current.Id, 2);
                Call(form, "RefreshHotp");
                Check(Field<AccountData>(form, "data").Counter == 3 && Field<Label>(form, "codeLabel").Text == "969 429", "copy preparation refreshes counter changed in another window");
                Field<TextBox>(form, "input").Text = "otpauth://totp/demo?secret=" + Secret + "&digits=7"; Call(form, "ParseInput");
                Check(!Field<Control>(form, "codeCopy").Enabled && Field<AccountData>(form, "data") == null, "invalid URI settings do not leave a stale usable code");
                Check(Field<Control>(form, "qrUpload").Enabled, "QR image upload action exists");
                Call(form, "SetSeparatorMode", false);
                Call(form, "ImportQrText", "otpauth://totp/QR:photo@example.com?secret=" + Secret + "&issuer=QR&digits=8&period=60");
                Check(Field<bool>(form, "automatic") && Field<AccountData>(form, "data").Account == "photo@example.com", "QR import switches from manual mode and shows selected account");
                Check(Field<AccountData>(form, "data").Digits == 8 && Field<AccountData>(form, "data").Period == 60, "QR import preserves OTP settings");
                smart.Load(); Check(smart.Entries[0].Account == "photo@example.com", "QR import uses encrypted history workflow");
                Guid namedId = smart.Entries[0].Id;
                Call(form, "RenameHistory", namedId, "工作邮箱"); smart.Load();
                Check(smart.Find(namedId).Name == "工作邮箱" && Field<AccountData>(form, "data").Account == "photo@example.com", "UI naming saves a label without replacing account");
                Check(Field<FlowLayoutPanel>(form, "historyList").Controls[0].Height >= 120, "named record has room for original identity");
                Call(form, "RestoreHistory", smart.Find(namedId));
                Check(Field<TextBox>(form, "account").Text == "photo@example.com" && Field<AccountData>(form, "data").Digits == 8, "named record restores original credentials and parameters");
                Field<TextBox>(form, "input").Text = smart.Find(namedId).OriginalText(); Call(form, "ParseInput"); smart.Load();
                Check(smart.Find(namedId).Name == "工作邮箱", "UI re-recognition keeps name");
                foreach (int width in new int[] { 780, 876, 1024 }) {
                    form.Size = new System.Drawing.Size(width, width == 780 ? 660 : 700); Layout(form); Application.DoEvents();
                    foreach (string control in new string[] { "qrUpload", "allCopy", "accountCopy", "passwordCopy", "secretCopy", "codeCopy", "replaceOtp" })
                        Check(Fits(Field<Control>(form, control)), control + " fits at window width " + width + " (bounds " + Field<Control>(form, control).Bounds + ", parent " + Field<Control>(form, control).Parent.ClientSize + ")");
                    Label otp = Field<Label>(form, "codeLabel");
                    System.Drawing.Size measured = TextRenderer.MeasureText(otp.Text, otp.Font, new System.Drawing.Size(Int32.MaxValue, Int32.MaxValue), TextFormatFlags.NoPadding | TextFormatFlags.SingleLine);
                    Check(measured.Width <= otp.Width && measured.Height <= otp.Height, "eight-digit code fits at window width " + width + " (measured " + measured + ", label " + otp.Size + ", font " + otp.Font.Size + ")");
                    int before = form.Width;
                    Call(form, "ToggleHistory"); Layout(form); Application.DoEvents();
                    Check(form.Width == before && !Field<FlowLayoutPanel>(form, "historyList").HorizontalScroll.Visible, "history fits without widening at " + width);
                    Call(form, "RestoreHistory", smart.Find(namedId)); Layout(form);
                    Check(!Field<bool>(form, "historyExpanded") && Field<Control>(form, "mainPage").Visible && form.Width == before, "history restore returns to parse without resizing at " + width);
                }
                form.ClientSize = new System.Drawing.Size(860, 660); Layout(form); Application.DoEvents();
                Layout(form);
                Control qrAction = Field<Control>(form, "qrUpload");
                Check(qrAction.Width >= 120 && qrAction.Right <= qrAction.Parent.ClientSize.Width, "QR upload action fits the desktop layout (bounds " + qrAction.Bounds + ", parent " + qrAction.Parent.ClientSize + ", form " + form.ClientSize + ")");
                Preview(form, checks, "windows-v1.7.1-main.png");
                Call(form, "ToggleHistory"); Layout(form); Application.DoEvents();
                Preview(form, checks, "windows-v1.7.1-history.png");
                string retainedInput = Field<TextBox>(form, "input").Text;
                MethodInfo keyDown = typeof(Control).GetMethod("OnKeyDown", BindingFlags.Instance | BindingFlags.NonPublic);
                keyDown.Invoke(form, new object[] { new KeyEventArgs(Keys.Escape) });
                Check(!Field<bool>(form, "historyExpanded") && Field<TextBox>(form, "input").Text == retainedInput && Field<AccountData>(form, "data") != null, "Escape returns from history without clearing input or results");
                keyDown.Invoke(form, new object[] { new KeyEventArgs(Keys.Escape) });
                Check(Field<TextBox>(form, "input").TextLength == 0 && Field<AccountData>(form, "data") == null && !Field<Control>(form, "codeCopy").Enabled, "Escape on parse page clears input and stale results");
                form.Hide();
                form.Close();
            }
            CheckNameDialog(checks, store);
            CheckReplacementDialog(checks, store);
            CheckTransferDialogs(checks, store);
            CheckHistoryManagement(checks, path);
            CheckContextMenuLifetime(path);
            CheckLoginDialog(checks, store);
            Console.WriteLine("PASS: " + passed + " UI integration checks; compact layout, history navigation, naming, live OTP, QR import, minimum size and Escape behavior.");
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error.GetType().FullName + ": " + error.Message);
            Console.Error.WriteLine(error.StackTrace);
            Environment.ExitCode = 1;
        }
        finally
        {
            if (Directory.Exists(folder)) { foreach (string file in Directory.GetFiles(folder)) File.Delete(file); Directory.Delete(folder); }
        }
    }
}
