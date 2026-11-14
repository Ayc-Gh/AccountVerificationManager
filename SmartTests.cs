using System;
using System.Text;
using AccountSplit2FA;

internal static class SmartTests
{
    private const string Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static int passed;
    private static void Check(bool condition, string label) { if (!condition) throw new Exception("FAIL: " + label); passed++; }
    private static void Bad(string text)
    {
        try { using (AccountData data = SmartParser.Parse(text)) { } }
        catch (FormatException) { passed++; return; }
        throw new Exception("FAIL: accepted malformed smart input");
    }
    [STAThread] private static void Main()
    {
        // RFC 6238 Appendix B, including each algorithm's specified key length.
        long[] times = { 59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000 };
        string[] algorithms = { "SHA1", "SHA256", "SHA512" };
        string[] keys = { "12345678901234567890", "12345678901234567890123456789012", "1234567890123456789012345678901234567890123456789012345678901234" };
        string[,] expected = {
            { "94287082", "07081804", "14050471", "89005924", "69279037", "65353130" },
            { "46119246", "68084774", "67062674", "91819424", "90698825", "77737706" },
            { "90693936", "25091201", "99943326", "93441116", "38618901", "47863826" }
        };
        for (int a = 0; a < algorithms.Length; a++)
            for (int i = 0; i < times.Length; i++) Check(Totp.Generate(Encoding.ASCII.GetBytes(keys[a]), times[i], 8, 30, algorithms[a]) == expected[a, i], "RFC 6238 algorithms");
        string[] hotp = { "755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489" };
        for (int i = 0; i < hotp.Length; i++) Check(Totp.GenerateCounter(Encoding.ASCII.GetBytes(keys[0]), (ulong)i, 6, "SHA1") == hotp[i], "RFC 4226 counters");
        using (AccountData data = SmartParser.Parse("  gezd gnbv gy3t qojq\ngezd gnbv gy3t qojq  "))
        {
            Check(data.InputKind == InputKind.Secret && data.Account == "" && data.Password == "", "pure key has no invented credentials");
            Check(data.Secret == Secret && data.Code(59) == "287082", "normalize pure Base32");
            Check(data.Algorithm == "SHA1" && data.Digits == 6 && data.Period == 30, "pure key defaults");
            Check(data.CopyText(59, false) == "验证码：287082", "pure key group omits missing credentials");
            Check(data.OriginalText() == Secret, "pure key original format");
        }
        using (AccountData data = SmartParser.Parse("“" + Secret + "”", "")) Check(data.InputKind == InputKind.Secret, "smart key recognition is independent of manual separators");
        string uri = "otpauth://totp/Acme%20Co%3Ademo%2Btag%40example.com?secret=" + Secret + "&issuer=Acme%20Co&algorithm=SHA1&digits=8&period=60";
        using (AccountData data = SmartParser.Parse(uri))
        {
            Check(data.InputKind == InputKind.AuthenticatorUri && data.UsesUri, "URI detection");
            Check(data.Account == "demo+tag@example.com" && data.Issuer == "Acme Co" && data.Password == "", "URI decoded identity");
            Check(data.Digits == 8 && data.Period == 60 && data.Algorithm == "SHA1", "URI settings");
            Check(data.Code(59) == "84755224" && data.Code(60) == "94287082", "honor custom period and digit count");
            Check(data.CopyText(59, false) == "账号：demo+tag@example.com\r\n验证码：84755224", "URI group does not invent a password");
            using (AccountData again = SmartParser.Parse(data.OriginalText())) Check(again.Account == data.Account && again.Issuer == data.Issuer && again.Code(59) == data.Code(59), "canonical URI round trip");
        }
        using (AccountData data = SmartParser.Parse("OTPAUTH://TOTP/Service:demo+tag@example.com?secret=" + Secret))
            Check(data.Account == "demo+tag@example.com" && data.Issuer == "Service", "case insensitive type and label issuer fallback");
        using (AccountData data = SmartParser.Parse("otpauth://totp/?secret=" + Secret + "&issuer=Acme+Co"))
            Check(data.Account == "" && data.Issuer == "Acme Co", "issuer-only URI without invented account");
        using (AccountData data = SmartParser.Parse("otpauth://totp/服务%3A%E8%B4%A6%E5%8F%B7?secret=" + Secret))
            Check(data.Account == "账号" && data.Issuer == "服务", "UTF-8 labels");
        using (AccountData data = SmartParser.Parse("otpauth://totp/demo%252Btag?secret=" + Secret))
            Check(data.Account == "demo%2Btag", "percent decode exactly once");
        using (AccountData data = SmartParser.Parse("otpauth://totp/demo?secret=MY%3D%3D%3D%3D%3D%3D")) Check(data.Secret == "MY", "encoded padded secret");
        using (AccountData data = SmartParser.Parse("otpauth://totp/demo?secret=" + Secret + "&algorithm=SHA-256&digits=8"))
            Check(data.Algorithm == "SHA256" && data.Code(59) == Totp.Generate(Encoding.ASCII.GetBytes(keys[0]), 59, 8, 30, "SHA256"), "URI algorithm parameter reaches generator");
        using (AccountData data = SmartParser.Parse("outer@example.com------pass*=====" + uri))
        {
            Check(data.InputKind == InputKind.Record && data.Account == "outer@example.com" && data.Password == "pass*", "record with URI preserves outer credentials");
            Check(data.Period == 60 && data.Digits == 8 && data.Code(59) == "84755224", "record retains URI settings");
            using (AccountData again = SmartParser.Parse(data.OriginalText())) Check(again.Password == "pass*" && again.Code(59) == data.Code(59), "record URI round trip");
        }
        using (AccountData data = SmartParser.Parse("demo@example.com|***pass|inside***|" + uri, "|")) Check(data.Password == "***pass|inside***", "manual record with URI");
        using (AccountData data = SmartParser.Parse("otpauth://hotp/demo@example.com?secret=" + Secret + "&counter=1"))
        {
            Check(data.OtpKind == OtpKind.Hotp && data.Counter == 1, "HOTP detection");
            Check(data.Code(0) == "287082" && data.Code(2000000000) == "287082", "HOTP ignores clock and never increments on copy");
            using (AccountData again = SmartParser.Parse(data.ToUri())) Check(again.Counter == 1 && again.Code(0) == data.Code(0), "HOTP URI counter round trip");
        }
        using (AccountData data = SmartParser.Parse("otpauth://hotp/demo?secret=" + Secret + "&counter=18446744073709551615")) Check(data.Code(0).Length == 6, "full unsigned 64-bit counter");
        string prefix = "otpauth://totp/demo?secret=" + Secret;
        foreach (string suffix in new string[] { "&digits=7", "&digits=", "&period=0", "&period=-1", "&period=abc", "&period=2147483648", "&algorithm=MD5", "&algorithm=", "&secret=MY", "&SECRET=MY", "&%73ecret=MY", "#fragment" }) Bad(prefix + suffix);
        foreach (string bad in new string[] {
            "otpauth://totp/demo", "otpauth://totp/demo?secret=", "otpauth://totp/demo?secret=%ZZ", "otpauth://totp/demo%ZZ?secret=" + Secret,
            "otpauth://totp/demo%FF?secret=" + Secret, "otpauth://totp/demo?secret=" + Secret + "&issuer=%E8%80",
            "otpauth://other/demo?secret=" + Secret, "otpauth://user@totp/demo?secret=" + Secret, "otpauth://totp:123/demo?secret=" + Secret,
            "otpauth://hotp/demo?secret=" + Secret, "otpauth://hotp/demo?secret=" + Secret + "&counter=-1",
            "otpauth://hotp/demo?secret=" + Secret + "&counter=18446744073709551616", "https://example.com/?secret=" + Secret,
            "otpauth-migration://offline?data=abc", "", "A1", "AAA" }) Bad(bad);
        Console.WriteLine("PASS: " + passed + " smart recognition checks; pure keys, Authenticator URI, RFC algorithms, TOTP/HOTP settings and malformed input.");
    }
}
