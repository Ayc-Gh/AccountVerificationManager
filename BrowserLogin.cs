using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AccountSplit2FA
{
    internal sealed class LoginCallbackServer : IDisposable
    {
        private readonly TcpListener listener;
        private readonly CancellationTokenSource stop = new CancellationTokenSource();
        public LoginCallbackServer(OAuthLogin login, Action<string> callback) {
            listener = new TcpListener(IPAddress.Loopback, login.Port); listener.Start(4);
            Task.Run(async delegate {
                while (!stop.IsCancellationRequested) {
                    try {
                        using (TcpClient client = await listener.AcceptTcpClientAsync()) {
                            client.ReceiveTimeout = client.SendTimeout = 2000;
                            using (NetworkStream stream = client.GetStream()) {
                                byte[] b = new byte[16384]; int n = 0;
                                while (n < b.Length) { int count = stream.Read(b, n, b.Length - n); if (count == 0) break; n += count; if (Encoding.ASCII.GetString(b, 0, n).Contains("\r\n\r\n")) break; }
                                string line = Encoding.ASCII.GetString(b, 0, n).Split('\r')[0]; string[] parts = line.Split(' ');
                                string address = parts.Length == 3 && parts[0] == "GET" && parts[1].StartsWith("/") && !parts[1].StartsWith("//") ? "http://localhost:" + login.Port + parts[1] : "";
                                bool valid = login.IsDenial(address); try { login.ValidateCallback(address); valid = true; } catch (FormatException) { }
                                byte[] body = Encoding.UTF8.GetBytes(valid ? "<!doctype html><meta charset=utf-8><title>账号验证管家</title>已收到授权回调，请回到账号验证管家查看结果。" : "Invalid or expired callback.");
                                byte[] header = Encoding.ASCII.GetBytes("HTTP/1.1 " + (valid ? "200 OK" : "400 Bad Request") + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\nContent-Length: " + body.Length + "\r\n\r\n");
                                stream.Write(header, 0, header.Length); stream.Write(body, 0, body.Length);
                                if (valid && !stop.IsCancellationRequested) callback(address);
                            }
                        }
                    } catch (Exception e) { if (!(e is SocketException || e is IOException || e is ObjectDisposedException || e is InvalidOperationException)) throw; }
                }
            });
        }
        public void Dispose() { stop.Cancel(); listener.Stop(); }
    }

    // Only a dedicated, temporary Chromium profile is automated. Existing browser
    // profiles, tabs, cookies and credentials are never inspected.
    internal sealed class LoginBrowser : IDisposable
    {
        private readonly CancellationTokenSource stop = new CancellationTokenSource();
        private string profile;
        private string browserEndpoint;
        private ClientWebSocket socket;
        private int sequence;
        private static string BrowserPath() {
            string[] roots = { Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData) };
            foreach (string root in roots) foreach (string relative in new string[] { "Microsoft\\Edge\\Application\\msedge.exe", "Google\\Chrome\\Application\\chrome.exe" }) { string p = Path.Combine(root, relative); if (File.Exists(p)) return p; }
            throw new FormatException("自动填充需要已安装的 Edge 或 Chrome。也可以选择官方网页手动登录。");
        }
        private static string ReadLocal(string address) {
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create(address); request.Proxy = null; request.Timeout = request.ReadWriteTimeout = 2000;
            using (WebResponse r = request.GetResponse()) using (StreamReader reader = new StreamReader(r.GetResponseStream())) { string s = reader.ReadToEnd(); if (s.Length > 1048576) throw new FormatException("浏览器响应过大。"); return s; }
        }
        public async Task Run(OAuthLogin login, Func<HistoryEntry> current, Action<string> callback, Action<string> notice) {
            string executable = BrowserPath(); profile = Path.Combine(Path.GetTempPath(), "avm-login-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(profile);
            using (Process process = Process.Start(new ProcessStartInfo(executable, "--remote-debugging-address=127.0.0.1 --remote-debugging-port=0 --user-data-dir=\"" + profile + "\" --incognito --no-first-run --no-default-browser-check --disable-sync --disable-extensions about:blank") { UseShellExecute = false })) { }
            string portFile = Path.Combine(profile, "DevToolsActivePort");
            for (int i = 0; i < 100 && !File.Exists(portFile); i++) await Task.Delay(150, stop.Token);
            stop.Token.ThrowIfCancellationRequested();
            if (!File.Exists(portFile)) throw new FormatException("无法连接专用浏览器，请关闭此窗口后重试或选择手动登录。");
            int port; if (!Int32.TryParse(File.ReadAllLines(portFile)[0], out port) || port < 1 || port > 65535) throw new FormatException("浏览器端口无效。");
            string root = "http://127.0.0.1:" + port;
            browserEndpoint = OAuthLogin.Str(OAuthLogin.Json().DeserializeObject(ReadLocal(root + "/json/version")) as Dictionary<string, object>, "webSocketDebuggerUrl");
            Uri browserUri; if (!Uri.TryCreate(browserEndpoint, UriKind.Absolute, out browserUri) || browserUri.Scheme != "ws" || browserUri.Host != "127.0.0.1" || browserUri.Port != port) throw new FormatException("专用浏览器地址无效。");
            object[] pages = OAuthLogin.Json().DeserializeObject(ReadLocal(root + "/json/list")) as object[];
            string target = ""; if (pages != null) foreach (object obj in pages) { Dictionary<string, object> p = obj as Dictionary<string, object>; if (OAuthLogin.Str(p, "type") == "page" && OAuthLogin.Str(p, "url") == "about:blank") { target = OAuthLogin.Str(p, "webSocketDebuggerUrl"); break; } }
            Uri endpoint; if (!Uri.TryCreate(target, UriKind.Absolute, out endpoint) || endpoint.Scheme != "ws" || endpoint.Host != "127.0.0.1" || endpoint.Port != port) throw new FormatException("无法连接专用浏览器页面。");
            socket = new ClientWebSocket(); socket.Options.Proxy = null; await socket.ConnectAsync(endpoint, stop.Token);
            await Command("Page.navigate", new Dictionary<string, object> { {"url", login.AuthorizationUrl} });
            notice("已打开专用浏览器，账号、密码和验证器 2FA 会自动填入；请在网页点击继续并确认授权。");
            while (!stop.IsCancellationRequested && DateTime.UtcNow < login.Deadline) {
                HistoryEntry entry = current(); if (entry == null || entry.IsExpired(DateTime.UtcNow)) throw new FormatException("历史记录已到期、被删除或更换，登录已停止。");
                Dictionary<string, object> result = await Command("Runtime.evaluate", new Dictionary<string, object> { {"expression", "location.href"}, {"returnByValue", true} });
                string url = OAuthLogin.Str(OAuthLogin.Obj(OAuthLogin.Obj(result, "result"), "result"), "value");
                if (url.StartsWith(login.Redirect + "?", StringComparison.Ordinal)) { login.ValidateCallback(url); callback(url); return; }
                Uri uri; if (Uri.TryCreate(url, UriKind.Absolute, out uri) && OAuthLogin.AllowsFill(login.Provider, uri)) {
                    string code = ""; if (entry.OtpKind == OtpKind.Totp) using (AccountData data = entry.Open()) code = data.Code((long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalSeconds);
                    await Command("Runtime.evaluate", new Dictionary<string, object> { {"expression", FillScript(login.Provider, entry.Account, entry.Password, code)}, {"returnByValue", true} });
                }
                await Task.Delay(750, stop.Token);
            }
            stop.Token.ThrowIfCancellationRequested(); throw new FormatException("登录已超时，请重新开始。");
        }
        internal static string FillScript(LoginProvider provider, string account, string password, string otp) {
            string hosts = provider == LoginProvider.OpenAI ? "['auth.openai.com']" : "['claude.ai','platform.claude.com','console.anthropic.com']";
            return @"(()=>{
                'use strict';
                if(window!==top||location.protocol!=='https:'||location.port&&location.port!=='443'||!" + hosts + @".includes(location.hostname))return false;
                const values=" + OAuthLogin.Json().Serialize(new string[] { account, password, otp }) + @";
                const text=(document.body?.innerText||'').toLowerCase();
                const totp=/authenticator|authentication app|身份验证器|身份验证应用|验证器/.test(text)&&!/email.{0,30}code|code.{0,30}email|邮箱验证码|邮件验证码/.test(text);
                const inputs=[...document.querySelectorAll('input')].filter(e=>!e.disabled&&!e.readOnly&&e.getClientRects().length);
                const otpInputs=inputs.filter(e=>e.autocomplete==='one-time-code'||/totp|otp|code/.test(e.name+' '+e.id)||e.maxLength===1);
                const split=totp&&otpInputs.length===values[2].length&&otpInputs.every(e=>e.maxLength===1);
                for(const e of inputs){
                    const a=(e.autocomplete||'').toLowerCase(),n=(e.name+' '+e.id).toLowerCase();let v='';
                    const isOtp=totp&&otpInputs.includes(e);
                    if(isOtp)v=split?values[2][otpInputs.indexOf(e)]:e.maxLength===1?'':values[2];
                    else if(e.type==='password'&&!/new-password/.test(a))v=values[1];
                    else if(e.type==='email'||a==='username'||a==='email'||/^(email|username|identifier)$/.test(e.name))v=values[0];
                    if(!v||e.value&&(!isOtp||e.dataset.avmOtpValue!==e.value))continue;
                    if(isOtp)e.dataset.avmOtpValue=v;
                    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v);
                    e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
                }return true;
            })()";
        }
        private async Task<Dictionary<string, object>> Command(string method, Dictionary<string, object> parameters) {
            int id = ++sequence; byte[] bytes = Encoding.UTF8.GetBytes(OAuthLogin.Json().Serialize(new Dictionary<string, object> { {"id", id}, {"method", method}, {"params", parameters} }));
            using (CancellationTokenSource timeout = CancellationTokenSource.CreateLinkedTokenSource(stop.Token)) {
                timeout.CancelAfter(5000); await socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, timeout.Token); Array.Clear(bytes, 0, bytes.Length);
                while (true) {
                    using (MemoryStream body = new MemoryStream()) {
                        byte[] b = new byte[8192]; WebSocketReceiveResult part;
                        do { part = await socket.ReceiveAsync(new ArraySegment<byte>(b), timeout.Token); if (part.MessageType == WebSocketMessageType.Close) throw new FormatException("专用浏览器已关闭。"); if (body.Length + part.Count > 1048576) throw new FormatException("浏览器响应过大。"); body.Write(b, 0, part.Count); } while (!part.EndOfMessage);
                        Dictionary<string, object> p = OAuthLogin.Json().DeserializeObject(Encoding.UTF8.GetString(body.ToArray())) as Dictionary<string, object>; object responseId;
                        if (p != null && p.TryGetValue("id", out responseId) && Convert.ToInt32(responseId) == id) { if (p.ContainsKey("error")) throw new FormatException("浏览器连接中断，请重新登录。"); return p; }
                    }
                }
            }
        }
        public void Dispose() {
            if (stop.IsCancellationRequested) return;
            stop.Cancel(); if (socket != null) { socket.Abort(); socket.Dispose(); }
            // Incognito avoids persisting the login session. Remove only this
            // randomly created profile, never an installed browser's profile.
            string cleanupProfile = profile, endpoint = browserEndpoint;
            Task.Run(async delegate {
                try {
                    if (endpoint != null) using (ClientWebSocket control = new ClientWebSocket()) using (CancellationTokenSource timeout = new CancellationTokenSource(2000)) {
                        control.Options.Proxy = null; await control.ConnectAsync(new Uri(endpoint), timeout.Token);
                        byte[] close = Encoding.UTF8.GetBytes("{\"id\":1,\"method\":\"Browser.close\"}"); await control.SendAsync(new ArraySegment<byte>(close), WebSocketMessageType.Text, true, timeout.Token);
                    }
                } catch (Exception e) { if (!(e is WebSocketException || e is OperationCanceledException || e is IOException)) throw; }
                if (cleanupProfile == null) return;
                string full = Path.GetFullPath(cleanupProfile), root = Path.GetFullPath(Path.GetTempPath());
                if (!full.StartsWith(root, StringComparison.OrdinalIgnoreCase) || !Path.GetFileName(full).StartsWith("avm-login-", StringComparison.Ordinal)) return;
                for (int i = 0; i < 10; i++) { try { if (Directory.Exists(full)) Directory.Delete(full, true); return; } catch (IOException) { } catch (UnauthorizedAccessException) { } await Task.Delay(500); }
            });
        }
    }
}
