using System;
using System.IO;
using System.Reflection;
using System.Windows.Forms;
using AccountSplit2FA;

// Integration checks instantiate only this test process's unshown form, using synthetic data.
internal static class UiChecks
{
    private static int passed;
    private const string Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static void Check(bool condition, string label) { if (!condition) throw new Exception("FAIL: " + label); passed++; }
    private static T Field<T>(MainForm form, string name) { return (T)typeof(MainForm).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic).GetValue(form); }
    private static void Call(MainForm form, string name, params object[] args) { typeof(MainForm).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic).Invoke(form, args); }
    private static void Layout(Control control) { control.PerformLayout(); foreach (Control child in control.Controls) Layout(child); }
    [STAThread]
    private static void Main()
    {
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
                Check(form.Text.StartsWith("账号验证管家"), "new window name");
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
                if (Field<bool>(form, "historyExpanded"))
                {
                    int width = form.Width;
                    Call(form, "ToggleHistory"); Check(!Field<bool>(form, "historyExpanded"), "history panel collapses");
                    Call(form, "ToggleHistory"); Check(form.Width == width, "history panel reopens without repeated width growth");
                }
                Call(form, "RestoreHistory", store.Entries[1]); Layout(form);
                Control historyCard = Field<Control>(form, "historyCard");
                Check(historyCard.Width >= 230 && historyCard.Height > 400, "history panel has room for record actions and scrolling");
                FlowLayoutPanel list = Field<FlowLayoutPanel>(form, "historyList");
                foreach (Control row in list.Controls)
                    Check(row.Width <= list.ClientSize.Width && row.Height >= 90, "record buttons fit within the history panel");
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
                form.Close();
            }
            Console.WriteLine("PASS: " + passed + " UI integration checks; startup, history restore, live OTP, failed input, panel toggle and record layout.");
        }
        finally
        {
            if (Directory.Exists(folder)) { foreach (string file in Directory.GetFiles(folder)) File.Delete(file); Directory.Delete(folder); }
        }
    }
}
