using System;
using System.Collections.Generic;
using System.Net;
using System.IO;
using System.Text;
using System.Threading;
using AccountSplit2FA;

internal static class OAuthTests
{
    private static int passed;
    private static void Check(bool value, string label) { if (!value) throw new Exception("FAIL: " + label); passed++; }
    private static void Reject(Action action, string label) { try { action(); } catch (FormatException) { passed++; return; } throw new Exception("FAIL: " + label); }
    private static string Jwt(object claims) { return "eyJhbGciOiJub25lIn0." + OAuthLogin.Base64Url(Encoding.UTF8.GetBytes(OAuthLogin.Json().Serialize(claims))) + ".synthetic"; }
    internal static string Tokens(LoginProvider provider) {
        return OAuthLogin.Json().Serialize(provider == LoginProvider.OpenAI ? new Dictionary<string, object> { {"access_token", "synthetic-access"}, {"refresh_token", "synthetic-refresh"}, {"expires_in", 3600}, {"id_token", Jwt(new Dictionary<string, object> { {"email", "alice@example.com"}, {"https://api.openai.com/auth", new Dictionary<string, object> { {"chatgpt_account_id", "acct-demo"}, {"chatgpt_user_id", "user-demo"}, {"chatgpt_plan_type", "plus"} }} })} } : new Dictionary<string, object> { {"access_token", "synthetic-claude-access"}, {"refresh_token", "synthetic-claude-refresh"}, {"expires_in", 3600}, {"account", new Dictionary<string, object> { {"email_address", "alice@example.com"}, {"uuid", "account-demo"} }}, {"organization", new Dictionary<string, object> { {"uuid", "org-demo"} }} });
    }
    public static void Main() {
        try {
            string fixtureRoot = AppDomain.CurrentDomain.BaseDirectory;
            File.WriteAllText(Path.Combine(fixtureRoot, "fill-openai.js"), LoginBrowser.FillScript(LoginProvider.OpenAI, "alice@example.com", " Synthetic\\\"Password ", "123456"), new UTF8Encoding(false));
            File.WriteAllText(Path.Combine(fixtureRoot, "fill-claude.js"), LoginBrowser.FillScript(LoginProvider.Claude, "alice@example.com", " Synthetic\\\"Password ", "123456"), new UTF8Encoding(false));
            foreach (LoginProvider provider in new LoginProvider[] {LoginProvider.OpenAI, LoginProvider.Claude}) {
                OAuthLogin flow = new OAuthLogin(provider, "alice@example.com"), other = new OAuthLogin(provider, "alice@example.com");
                Check(flow.State.Length == 43 && flow.Verifier.Length == 43 && flow.State != other.State && flow.Verifier != other.Verifier, "independent cryptographic state/verifier");
                Uri authorize = new Uri(flow.AuthorizationUrl); Dictionary<string, string> query = OAuthLogin.Query(authorize.Query);
                Check(authorize.Scheme == "https" && query["redirect_uri"] == flow.Redirect && query["code_challenge_method"] == "S256" && query["state"] == flow.State && !flow.AuthorizationUrl.Contains(flow.Verifier), "authorization URL PKCE");
                string callback = flow.Redirect + "?code=demo%2Bcode&state=" + flow.State;
                Check(flow.ValidateCallback(callback) == "demo+code", "encoded callback");
                foreach (string bad in new string[] {"https://localhost:" + flow.Port + new Uri(flow.Redirect).AbsolutePath + "?code=demo&state=" + flow.State, callback.Replace("localhost", "localhost.evil.invalid"), callback.Replace("localhost", "127.0.0.1"), callback.Replace("localhost", "user@localhost"), callback + "#fragment", callback + "&state=" + flow.State, callback + "&code=other", callback.Replace(flow.State, other.State), flow.Redirect + "?state=" + flow.State, flow.Redirect + "?code=&state=" + flow.State, callback.Replace(new Uri(flow.Redirect).AbsolutePath, "/other"), callback.Replace(":" + flow.Port, ":8888"), "demo", "", new string('x', 17000)}) Reject(() => flow.ValidateCallback(bad), "invalid callback rejected");
                Reject(() => flow.ValidateCallback(flow.Redirect + "?error=access_denied&state=" + flow.State), "denied authorization");
                Check(flow.IsDenial(flow.Redirect + "?error=access_denied&state=" + flow.State) && !flow.IsDenial(flow.Redirect + "?error=access_denied&state=other"), "only current authorization denial accepted");
                Dictionary<string, object> exchange = flow.TakeExchange(callback);
                Check((string)exchange["code_verifier"] == flow.Verifier && (string)exchange["code"] == "demo+code" && (string)exchange["client_id"] == flow.ClientId && (string)exchange["redirect_uri"] == flow.Redirect, "exchange tied to login");
                Check((provider == LoginProvider.Claude) == exchange.ContainsKey("state"), "Claude exchange carries state");
                Reject(() => flow.TakeExchange(callback), "one-time code");
                LoginCredential result = flow.ParseTokens(Tokens(provider), DateTime.UtcNow);
                Check(result.Email == "alice@example.com" && result.Refresh.Length > 0 && result.Expires > result.Created, "complete real-shape token response");
                Dictionary<string, object> cpa = OAuthLogin.Json().DeserializeObject(result.Export(false)) as Dictionary<string, object>;
                Check((string)cpa["type"] == (provider == LoginProvider.OpenAI ? "codex" : "claude") && (string)cpa["access_token"] == result.Access && (string)cpa["refresh_token"] == result.Refresh, "CPA upstream schema");
                Dictionary<string, object> sub = OAuthLogin.Json().DeserializeObject(result.Export(true)) as Dictionary<string, object>;
                object[] accounts = sub["accounts"] as object[]; Dictionary<string, object> account = accounts[0] as Dictionary<string, object>;
                Check((string)sub["type"] == "sub2api-data" && (int)sub["version"] == 1 && (sub["proxies"] as object[]).Length == 0 && accounts.Length == 1, "sub2api native bundle");
                Check((string)account["platform"] == (provider == LoginProvider.OpenAI ? "openai" : "anthropic") && (string)account["type"] == "oauth" && (int)account["concurrency"] == 1, "sub2api provider credentials");
                if (provider == LoginProvider.Claude) Check((string)(account["credentials"] as Dictionary<string, object>)["org_uuid"] == "org-demo" && (string)cpa["organization_uuid"] == "org-demo", "Claude organization uses each upstream field name");
                string all = result.Export(false) + result.Export(true); Check(!all.Contains("password") && !all.Contains("otpauth") && !all.Contains("code_verifier") && !all.Contains("SyntheticPassword"), "export excludes history credentials and PKCE");
                Reject(() => new OAuthLogin(provider, "bob@example.com").ParseTokens(Tokens(provider), DateTime.UtcNow), "wrong-account protection");
                Check(new OAuthLogin(provider, "ALICE@EXAMPLE.COM").ParseTokens(Tokens(provider), DateTime.UtcNow).Email == "alice@example.com", "email case-insensitive");
                foreach (string bad in new string[] {"{}", "[]", "null", "{", Tokens(provider).Replace("synthetic-refresh", "").Replace("synthetic-claude-refresh", ""), Tokens(provider).Replace("3600", "0"), Tokens(provider).Replace("3600", "-1"), Tokens(provider).Replace("3600", "31536001"), Tokens(provider).Replace("alice@example.com", "")}) {
                    // OpenAI email is in base64 JWT, so the last replacement is a no-op there.
                    if (bad == Tokens(provider)) continue; Reject(() => flow.ParseTokens(bad, DateTime.UtcNow), "invalid token response");
                }
                LoginCredential expired = flow.ParseTokens(Tokens(provider), DateTime.UtcNow.AddDays(-2)); Reject(() => expired.Export(false), "expired CPA refused"); Reject(() => expired.Export(true), "expired sub2api refused");
                Uri allowed = new Uri(provider == LoginProvider.OpenAI ? "https://auth.openai.com/login" : "https://claude.ai/login"); Check(OAuthLogin.AllowsFill(provider, allowed), "exact official fill origin");
                foreach (string bad in new string[] {allowed.ToString().Replace("https:", "http:"), allowed.ToString().Replace(allowed.Host, allowed.Host + ".evil.invalid"), allowed.ToString().Replace(allowed.Host, "user@" + allowed.Host), allowed.ToString().Replace(allowed.Host, allowed.Host + ":8443"), "https://accounts.google.com/", "file:///login"}) Check(!OAuthLogin.AllowsFill(provider, new Uri(bad)), "untrusted origin never filled");
                ManualResetEvent received = new ManualResetEvent(false); string captured = null;
                OAuthLogin callbackFlow = new OAuthLogin(provider, "alice@example.com");
                using (LoginCallbackServer server = new LoginCallbackServer(callbackFlow, value => { captured = value; received.Set(); })) using (WebClient client = new WebClient()) { client.Proxy = null; string ok = client.DownloadString(callbackFlow.Redirect.Replace("localhost", "127.0.0.1") + "?code=synthetic&state=" + callbackFlow.State); Check(received.WaitOne(3000) && callbackFlow.ValidateCallback(captured) == "synthetic" && !ok.Contains("synthetic"), "actual loopback HTTP callback and no code reflection"); }
                using (LoginCallbackServer reused = new LoginCallbackServer(new OAuthLogin(provider, ""), value => { })) Check(true, "callback port reusable after cancel");
            }
            Console.WriteLine("PASS: " + passed + " OAuth checks; PKCE, callbacks, identity, tokens, upstream exports, loopback and origin isolation.");
        } catch (Exception e) { Console.Error.WriteLine(e.GetType().Name + ": " + e.Message); Environment.ExitCode = 1; }
    }
}
