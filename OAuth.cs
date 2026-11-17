using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;

namespace AccountSplit2FA
{
    public enum LoginProvider { OpenAI, Claude }

    // Protocol values verified against the upstream providers' CPA implementations.
    // A login result lives only in memory until the user explicitly exports it.
    public sealed class OAuthLogin
    {
        public readonly LoginProvider Provider;
        public readonly string State, Verifier;
        public readonly DateTime Deadline;
        public readonly string ExpectedEmail;
        private bool used;
        public OAuthLogin(LoginProvider provider, string email) {
            if (!Enum.IsDefined(typeof(LoginProvider), provider)) throw new ArgumentException("服务类型无效。");
            Provider = provider; ExpectedEmail = email ?? ""; State = RandomText(); Verifier = RandomText(); Deadline = DateTime.UtcNow.AddMinutes(15);
        }
        private static string RandomText() { byte[] b = new byte[32]; using (RandomNumberGenerator r = RandomNumberGenerator.Create()) r.GetBytes(b); return Base64Url(b); }
        internal static string Base64Url(byte[] b) { return Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_'); }
        public int Port { get { return Provider == LoginProvider.OpenAI ? 1455 : 54545; } }
        public string Redirect { get { return "http://localhost:" + Port + (Provider == LoginProvider.OpenAI ? "/auth/callback" : "/callback"); } }
        public string ClientId { get { return Provider == LoginProvider.OpenAI ? "app_EMoamEEZ73f0CkXaXp7hrann" : "9d1c250a-e61b-44d9-88ed-5944d1962f5e"; } }
        public string TokenEndpoint { get { return Provider == LoginProvider.OpenAI ? "https://auth.openai.com/oauth/token" : "https://platform.claude.com/v1/oauth/token"; } }
        public string AuthorizationUrl {
            get {
                string challenge; using (SHA256 sha = SHA256.Create()) challenge = Base64Url(sha.ComputeHash(Encoding.ASCII.GetBytes(Verifier)));
                Dictionary<string, object> p = new Dictionary<string, object> { {"client_id", ClientId}, {"response_type", "code"}, {"redirect_uri", Redirect}, {"state", State}, {"code_challenge", challenge}, {"code_challenge_method", "S256"} };
                p["scope"] = Provider == LoginProvider.OpenAI ? "openid email profile offline_access" : "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload";
                if (Provider == LoginProvider.OpenAI) { p["prompt"] = "login"; p["id_token_add_organizations"] = "true"; p["codex_cli_simplified_flow"] = "true"; if (ExpectedEmail.Length > 0) p["login_hint"] = ExpectedEmail; }
                else p["code"] = "true";
                return (Provider == LoginProvider.OpenAI ? "https://auth.openai.com/oauth/authorize?" : "https://claude.ai/oauth/authorize?") + Form(p);
            }
        }
        private Dictionary<string, string> CallbackParameters(string text) {
            if (DateTime.UtcNow >= Deadline) throw new FormatException("登录已超时，请重新开始。");
            Uri uri, expected = new Uri(Redirect);
            if (text == null || text.Length > 16384 || !Uri.TryCreate(text.Trim(), UriKind.Absolute, out uri) || uri.Scheme != "http" || uri.Host != "localhost" || uri.Port != Port || uri.AbsolutePath != expected.AbsolutePath || uri.UserInfo.Length > 0 || uri.Fragment.Length > 0)
                throw new FormatException("请粘贴本次登录跳转的完整 localhost 回调地址。");
            Dictionary<string, string> p = Query(uri.Query);
            if (!p.ContainsKey("state") || !ConstantEquals(p["state"], State)) throw new FormatException("回调不属于本次登录，请重新授权。");
            return p;
        }
        public bool IsDenial(string text) { try { return CallbackParameters(text).ContainsKey("error"); } catch (FormatException) { return false; } }
        public string ValidateCallback(string text) {
            Dictionary<string, string> p = CallbackParameters(text);
            if (p.ContainsKey("error")) throw new FormatException("官方网页未授予授权，请重新开始登录。");
            string code; if (!p.TryGetValue("code", out code) || String.IsNullOrWhiteSpace(code) || code.Length > 8192) throw new FormatException("回调缺少授权码。");
            lock (this) if (used) throw new FormatException("本次授权码已处理，请重新登录。");
            return code;
        }
        public Dictionary<string, object> TakeExchange(string callback) {
            lock (this) {
                string code = ValidateCallback(callback); used = true;
                Dictionary<string, object> p = new Dictionary<string, object> { {"grant_type", "authorization_code"}, {"code", code}, {"redirect_uri", Redirect}, {"client_id", ClientId}, {"code_verifier", Verifier} };
                if (Provider == LoginProvider.Claude) p["state"] = State;
                return p;
            }
        }
        internal static bool ConstantEquals(string a, string b) { if (a.Length != b.Length) return false; int diff = 0; for (int i = 0; i < a.Length; i++) diff |= a[i] ^ b[i]; return diff == 0; }
        internal static Dictionary<string, string> Query(string query) {
            Dictionary<string, string> p = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (string part in query.TrimStart('?').Split('&')) {
                if (part.Length == 0) continue; int eq = part.IndexOf('=');
                string key = Uri.UnescapeDataString((eq < 0 ? part : part.Substring(0, eq)).Replace('+', ' '));
                string value = Uri.UnescapeDataString((eq < 0 ? "" : part.Substring(eq + 1)).Replace('+', ' '));
                if (p.ContainsKey(key)) throw new FormatException("回调包含重复参数。"); p.Add(key, value);
            } return p;
        }
        internal static string Form(IDictionary<string, object> p) { List<string> parts = new List<string>(); foreach (KeyValuePair<string, object> e in p) parts.Add(Uri.EscapeDataString(e.Key) + "=" + Uri.EscapeDataString(Convert.ToString(e.Value, CultureInfo.InvariantCulture))); return String.Join("&", parts.ToArray()); }
        internal static JavaScriptSerializer Json() { return new JavaScriptSerializer { MaxJsonLength = 1048576, RecursionLimit = 32 }; }
        internal static string Str(IDictionary<string, object> p, string k) { object v; return p != null && p.TryGetValue(k, out v) && v is string ? (string)v : ""; }
        internal static Dictionary<string, object> Obj(IDictionary<string, object> p, string k) { object v; return p != null && p.TryGetValue(k, out v) ? v as Dictionary<string, object> : null; }
        internal static Dictionary<string, object> Claims(string token) {
            try { string[] parts = token.Split('.'); if (parts.Length != 3 || parts[1].Length > 131072) return null; string payload = parts[1].Replace('-', '+').Replace('_', '/'); payload = payload.PadRight((payload.Length + 3) / 4 * 4, '='); return Json().DeserializeObject(Encoding.UTF8.GetString(Convert.FromBase64String(payload))) as Dictionary<string, object>; }
            catch (Exception e) { if (!(e is FormatException || e is ArgumentException || e is InvalidOperationException)) throw; return null; }
        }
        public LoginCredential ParseTokens(string json, DateTime now) {
            Dictionary<string, object> p;
            try { p = Json().DeserializeObject(json) as Dictionary<string, object>; } catch (ArgumentException) { throw new FormatException("授权服务返回了无效数据。"); }
            if (p == null) throw new FormatException("授权服务返回了无效数据。");
            string access = Str(p, "access_token"), refresh = Str(p, "refresh_token"), id = Str(p, "id_token"); long seconds; object raw;
            if (String.IsNullOrWhiteSpace(access) || String.IsNullOrWhiteSpace(refresh) || !p.TryGetValue("expires_in", out raw) || !Int64.TryParse(Convert.ToString(raw, CultureInfo.InvariantCulture), out seconds) || seconds < 1 || seconds > 31536000)
                throw new FormatException("授权服务未返回完整、可刷新的凭据。");
            Dictionary<string, object> claims = Claims(id), accessClaims = Claims(access), auth = Obj(claims, "https://api.openai.com/auth") ?? Obj(accessClaims, "https://api.openai.com/auth");
            string email = Str(claims, "email"), account = Str(auth, "chatgpt_account_id"), user = Str(auth, "chatgpt_user_id"), plan = Str(auth, "chatgpt_plan_type"), org = "";
            if (email.Length == 0) email = Str(Obj(accessClaims, "https://api.openai.com/profile"), "email");
            if (Provider == LoginProvider.Claude) { Dictionary<string, object> a = Obj(p, "account"), o = Obj(p, "organization"); email = Str(a, "email_address"); account = Str(a, "uuid"); org = Str(o, "uuid"); }
            if (email.Length == 0 || account.Length == 0 || (Provider == LoginProvider.OpenAI && id.Length == 0)) throw new FormatException("授权结果缺少账号身份信息，未生成文件。");
            if (ExpectedEmail.Length > 0 && !String.Equals(ExpectedEmail.Trim(), email.Trim(), StringComparison.OrdinalIgnoreCase)) throw new FormatException("登录账号与选择的历史账号不同，未保留此次凭据。请退出网页中的其他账号后重试。");
            return new LoginCredential(Provider, email, access, refresh, id, account, user, plan, org, now.ToUniversalTime(), now.ToUniversalTime().AddSeconds(seconds), ClientId);
        }
        public LoginCredential Exchange(string callback) {
            Dictionary<string, object> p = TakeExchange(callback);
            // Redirects are disabled: a token exchange never sends codes to another host.
            ServicePointManager.SecurityProtocol |= (SecurityProtocolType)3072;
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create(TokenEndpoint); request.Method = "POST"; request.AllowAutoRedirect = false; request.Timeout = 30000; request.ReadWriteTimeout = 30000;
            request.Accept = "application/json"; request.ContentType = Provider == LoginProvider.OpenAI ? "application/x-www-form-urlencoded" : "application/json";
            byte[] body = Encoding.UTF8.GetBytes(Provider == LoginProvider.OpenAI ? Form(p) : Json().Serialize(p)); request.ContentLength = body.Length;
            try {
                using (Stream output = request.GetRequestStream()) output.Write(body, 0, body.Length);
                using (HttpWebResponse response = (HttpWebResponse)request.GetResponse()) {
                    if (response.StatusCode != HttpStatusCode.OK) throw new FormatException("授权交换失败，请重新登录。");
                    using (Stream stream = response.GetResponseStream()) using (MemoryStream bytes = new MemoryStream()) { byte[] b = new byte[4096]; int n; while ((n = stream.Read(b, 0, b.Length)) > 0) { if (bytes.Length + n > 1048576) throw new FormatException("授权响应过大。"); bytes.Write(b, 0, n); } return ParseTokens(Encoding.UTF8.GetString(bytes.ToArray()), DateTime.UtcNow); }
                }
            } catch (WebException e) { HttpWebResponse response = e.Response as HttpWebResponse; string code = response == null ? "网络连接失败" : "HTTP " + (int)response.StatusCode; if (response != null) response.Dispose(); throw new FormatException("授权交换失败（" + code + "），请检查网络后重新登录。", null); }
            finally { Array.Clear(body, 0, body.Length); }
        }
        public static bool AllowsFill(LoginProvider provider, Uri uri) {
            if (uri == null || uri.Scheme != "https" || !uri.IsDefaultPort || uri.UserInfo.Length > 0) return false;
            return provider == LoginProvider.OpenAI ? uri.Host == "auth.openai.com" : uri.Host == "claude.ai" || uri.Host == "platform.claude.com" || uri.Host == "console.anthropic.com";
        }
    }

    public sealed class LoginCredential
    {
        public readonly LoginProvider Provider;
        public readonly string Email, Access, Refresh, IdToken, AccountId, UserId, Plan, Organization, ClientId;
        public readonly DateTime Created, Expires;
        public LoginCredential(LoginProvider provider, string email, string access, string refresh, string id, string account, string user, string plan, string organization, DateTime created, DateTime expires, string clientId) { Provider = provider; Email = email; Access = access; Refresh = refresh; IdToken = id; AccountId = account; UserId = user; Plan = plan; Organization = organization; Created = created; Expires = expires; ClientId = clientId; }
        private static string Stamp(DateTime d) { return d.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture); }
        public string Export(bool sub2api) {
            if (DateTime.UtcNow >= Expires) throw new FormatException("登录令牌已到期，请重新登录后导出。");
            Dictionary<string, object> p = new Dictionary<string, object> { {"access_token", Access}, {"refresh_token", Refresh}, {"id_token", IdToken}, {"email", Email} };
            if (!sub2api) {
                p["type"] = Provider == LoginProvider.OpenAI ? "codex" : "claude"; p["last_refresh"] = Stamp(Created); p["expired"] = Stamp(Expires);
                if (Provider == LoginProvider.OpenAI) { p["account_id"] = AccountId; if (Plan.Length > 0) p["plan_type"] = Plan; }
                else { p["account_uuid"] = AccountId; if (Organization.Length > 0) p["organization_uuid"] = Organization; }
                return OAuthLogin.Json().Serialize(p);
            }
            p["client_id"] = ClientId; p["expires_at"] = Stamp(Expires);
            if (Provider == LoginProvider.OpenAI) { p["chatgpt_account_id"] = AccountId; if (UserId.Length > 0) p["chatgpt_user_id"] = UserId; if (Plan.Length > 0) p["plan_type"] = Plan; }
            else { p["account_uuid"] = AccountId; if (Organization.Length > 0) p["org_uuid"] = Organization; }
            Dictionary<string, object> account = new Dictionary<string, object> { {"name", Email}, {"platform", Provider == LoginProvider.OpenAI ? "openai" : "anthropic"}, {"type", "oauth"}, {"credentials", p}, {"extra", new Dictionary<string, object> { {"email", Email} }}, {"concurrency", 1}, {"priority", 1}, {"rate_multiplier", 1}, {"auto_pause_on_expired", true} };
            return OAuthLogin.Json().Serialize(new Dictionary<string, object> { {"type", "sub2api-data"}, {"version", 1}, {"exported_at", Stamp(DateTime.UtcNow)}, {"proxies", new object[0]}, {"accounts", new object[] {account}} });
        }
    }
}
