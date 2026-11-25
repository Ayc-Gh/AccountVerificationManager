using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccountSplit2FA
{
    internal sealed class LoginDialog : Form
    {
        private readonly Func<HistoryEntry> current;
        private readonly ComboBox provider, mode;
        private readonly Label feedback;
        private readonly TextBox callback;
        private readonly ActionButton start, finish, cpa, sub;
        private readonly Timer expiry;
        private OAuthLogin login;
        private LoginCredential credential;
        private LoginCallbackServer server;
        private LoginBrowser browser;
        private bool exchanging;
        private int generation;
        public LoginDialog(Func<HistoryEntry> current) {
            this.current = delegate { try { return current(); } catch (Exception e) { if (!MainForm.HistoryFailure(e) && !(e is InvalidOperationException)) throw; return null; } };
            Text = "登录并生成 CPA / sub2api"; ClientSize = new Size(590, 480); Font = Theme.Font(10, FontStyle.Regular); BackColor = Theme.Background;
            StartPosition = FormStartPosition.CenterParent; FormBorderStyle = FormBorderStyle.FixedDialog; MinimizeBox = MaximizeBox = false;
            Controls.Add(new Label { Text = "选择服务与登录方式", Bounds = new Rectangle(22, 20, 546, 26), ForeColor = Theme.Ink });
            provider = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Bounds = new Rectangle(22, 54, 210, 32), AccessibleName = "登录服务" }; provider.Items.AddRange(new object[] {"ChatGPT / OpenAI（Codex）", "Claude / Anthropic"}); provider.SelectedIndex = 0;
            mode = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Bounds = new Rectangle(246, 54, 322, 32), AccessibleName = "登录方式" }; mode.Items.AddRange(new object[] {"历史账号自动填充（Edge / Chrome）", "官方网页手动登录与授权"}); mode.SelectedIndex = 0;
            Controls.AddRange(new Control[] {provider, mode});
            Controls.Add(new Label { Text = "自动模式只填写选中历史的账号、密码与验证器 2FA。\n请在网页点击继续并完成邮箱验证码、人机验证和授权。\n登录结果仅在此窗口暂存，关闭即清除；导出的 JSON 含登录令牌。", Bounds = new Rectangle(22, 99, 546, 83), ForeColor = Theme.Muted });
            start = new ActionButton { Text = "打开官方网页", Kind = ButtonKind.Primary, Bounds = new Rectangle(22, 188, 180, 36) }; start.Click += async delegate { await StartLogin(); }; Controls.Add(start);
            Controls.Add(new Label { Text = "网页未自动返回时，粘贴地址栏中的完整 localhost 回调地址：", Bounds = new Rectangle(22, 242, 546, 28), ForeColor = Theme.Muted });
            callback = new TextBox { Bounds = new Rectangle(22, 275, 416, 30), MaxLength = 16384, AccessibleName = "授权回调地址", UseSystemPasswordChar = true };
            finish = new ActionButton { Text = "完成登录", Kind = ButtonKind.Secondary, Bounds = new Rectangle(450, 272, 118, 36), Enabled = false }; finish.Click += async delegate { await Complete(callback.Text); }; Controls.AddRange(new Control[] {callback, finish});
            feedback = new Label { Text = "从历史选择的账号必须与授权结果一致。", Bounds = new Rectangle(22, 326, 546, 71), ForeColor = Theme.Muted }; Controls.Add(feedback);
            cpa = new ActionButton { Text = "生成 CPA 文件", Kind = ButtonKind.Primary, Bounds = new Rectangle(22, 417, 230, 38), Enabled = false }; cpa.Click += delegate { Export(false); };
            sub = new ActionButton { Text = "生成 sub2api 文件", Kind = ButtonKind.Secondary, Bounds = new Rectangle(267, 417, 230, 38), Enabled = false }; sub.Click += delegate { Export(true); }; Controls.AddRange(new Control[] {cpa, sub});
            expiry = new Timer { Interval = 1000 }; expiry.Tick += delegate { if (login != null && (this.current() == null || DateTime.UtcNow >= login.Deadline || (credential != null && DateTime.UtcNow >= credential.Expires))) { StopLogin(); SetNotice("记录失效或登录超时，请重新开始。", true); } }; expiry.Start();
            FormClosed += delegate { expiry.Stop(); expiry.Dispose(); StopLogin(); callback.Clear(); };
        }
        private void SetNotice(string text, bool error) { if (!IsDisposed) { feedback.Text = text; feedback.ForeColor = error ? Color.Firebrick : Theme.Muted; } }
        private void StopLogin() { generation++; login = null; credential = null; exchanging = false; cpa.Enabled = sub.Enabled = finish.Enabled = false; start.Enabled = provider.Enabled = mode.Enabled = true; if (server != null) { server.Dispose(); server = null; } if (browser != null) { browser.Dispose(); browser = null; } callback.Clear(); }
        private async Task StartLogin() {
            StopLogin(); HistoryEntry entry = current(); if (entry == null || String.IsNullOrWhiteSpace(entry.Account)) { SetNotice("请先选择包含账号的未到期历史记录。", true); return; }
            if (mode.SelectedIndex == 0 && entry.Password.Length == 0) { SetNotice("这条记录没有密码，请选择手动登录。", true); return; }
            login = new OAuthLogin((LoginProvider)provider.SelectedIndex, entry.Account); int stamp = generation;
            try {
                server = new LoginCallbackServer(login, address => { if (!IsDisposed && IsHandleCreated) BeginInvoke((Action)(async delegate { if (stamp == generation) await Complete(address); })); });
                finish.Enabled = true; start.Text = "重新开始登录"; provider.Enabled = mode.Enabled = false;
                if (mode.SelectedIndex == 1) { Process.Start(new ProcessStartInfo(login.AuthorizationUrl) { UseShellExecute = true }); SetNotice("请在官方网页完成登录与授权，返回账号须与选中的历史账号一致。", false); }
                else { browser = new LoginBrowser(); await browser.Run(login, current, address => { if (!IsDisposed && IsHandleCreated) BeginInvoke((Action)(async delegate { if (stamp == generation) await Complete(address); })); }, text => { if (stamp == generation) SetNotice(text, false); }); }
            } catch (Exception e) { if (stamp != generation || IsDisposed || exchanging || credential != null) return; StopLogin(); SetNotice(e is FormatException ? e.Message : e is SocketException ? "本机回调端口已被占用，请关闭其他登录窗口后重试。" : "无法打开或连接浏览器，请重新开始或选择手动登录。", true); }
        }
        private async Task Complete(string address) {
            if (login == null || exchanging || credential != null) return;
            try { login.ValidateCallback(address); } catch (FormatException e) { if (login.IsDenial(address)) StopLogin(); SetNotice(e.Message, true); return; }
            int stamp = generation; OAuthLogin flow = login; exchanging = true; start.Enabled = finish.Enabled = false; callback.Clear(); SetNotice("正在向官方授权服务交换凭据…", false);
            try {
                LoginCredential result = await Task.Run(() => flow.Exchange(address)); if (IsDisposed || stamp != generation) return;
                if (current() == null) { StopLogin(); SetNotice("历史记录已失效，未保留授权结果。", true); return; }
                credential = result; cpa.Enabled = sub.Enabled = true; start.Enabled = true;
                if (server != null) { server.Dispose(); server = null; } if (browser != null) { browser.Dispose(); browser = null; }
                SetNotice("登录成功：" + result.Email + "\n可以分别生成两种文件；JSON 含可用登录令牌，请妥善保管。", false);
            } catch (Exception e) { if (!IsDisposed && stamp == generation) { StopLogin(); SetNotice(e is FormatException ? e.Message : "登录失败，请检查网络后重新开始。", true); } }
            finally { if (!IsDisposed && stamp == generation) exchanging = false; }
        }
        private void Export(bool sub2api) {
            if (credential == null || current() == null) { StopLogin(); return; }
            try {
                using (SaveFileDialog save = new SaveFileDialog { Title = "保存登录凭据（含明文令牌）", Filter = "JSON 文件 (*.json)|*.json", FileName = (sub2api ? "sub2api-" : credential.Provider == LoginProvider.OpenAI ? "codex-" : "claude-") + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".json" }) {
                    if (save.ShowDialog(this) != DialogResult.OK) return;
                    if (credential == null || current() == null) throw new FormatException("记录已失效，请重新登录。");
                    string json = credential.Export(sub2api), temporary = save.FileName + ".tmp." + Guid.NewGuid().ToString("N");
                    try { File.WriteAllText(temporary, json, new UTF8Encoding(false)); if (File.Exists(save.FileName)) File.Replace(temporary, save.FileName, null); else File.Move(temporary, save.FileName); } finally { if (File.Exists(temporary)) File.Delete(temporary); }
                }
                SetNotice((sub2api ? "sub2api" : "CPA") + " 文件已生成，可继续生成另一种格式。", false);
            } catch (Exception e) { SetNotice(e is FormatException ? e.Message : "文件保存失败，请重试。", true); }
        }
    }
}
