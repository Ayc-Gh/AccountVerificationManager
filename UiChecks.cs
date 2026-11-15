using System;
using System.IO;
using System.Reflection;
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
                        Check(Fits(Field<Control>(form, control)), control + " fits at window width " + width);
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
                Check(qrAction.Width >= 120 && qrAction.Right <= qrAction.Parent.ClientSize.Width, "QR upload action fits the desktop layout");
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
