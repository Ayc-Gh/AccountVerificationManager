using System;
using System.Text;
using AccountSplit2FA;

internal static class Tests
{
    private static int passed;
    private static void Equal(string expected, string actual, string label)
    {
        if (expected != actual) throw new Exception("FAIL: " + label);
        passed++;
    }
    private static void Invalid(string input, string label)
    {
        try { using (AccountData data = AccountParser.Parse(input)) { } }
        catch (FormatException) { passed++; return; }
        throw new Exception("FAIL: accepted invalid " + label);
    }
    private static void BadBase32(string input)
    {
        try { Base32.Decode(input); }
        catch (FormatException) { passed++; return; }
        throw new Exception("FAIL: accepted invalid Base32");
    }
    private static void InvalidManual(string input, string separator, string label)
    {
        try { using (AccountData data = AccountParser.Parse(input, separator)) { } }
        catch (FormatException) { passed++; return; }
        throw new Exception("FAIL: accepted invalid manual input " + label);
    }
    private static void Main()
    {
        DateTime retentionStart = new DateTime(2028, 1, 31, 23, 59, 30, DateTimeKind.Utc);
        long[] retentionSeconds = { 60, 3600, 86400, 604800, 2592000 };
        string[] retentionUnits = { "分", "时", "天", "周", "月" };
        for (int unit = 0; unit < 5; unit++) {
            RetentionPeriod period = RetentionPeriod.Parse(" 2 ", unit);
            Equal(retentionStart.AddSeconds(2 * retentionSeconds[unit]).ToString("o"), period.ExpiresAt(retentionStart).ToString("o"), "retention exact duration " + unit);
            Equal("2" + retentionUnits[unit], period.Label, "retention display " + unit);
        }
        foreach (string invalid in new string[] { null, "", " ", "0", "000000", "-1", "+1", "1.5", "1e2", "1000000", "99999999999999", "1 0", "２", "abc" }) {
            try { RetentionPeriod.Parse(invalid, 0); throw new Exception("FAIL: invalid retention accepted"); }
            catch (FormatException) { passed++; }
        }
        foreach (int invalidUnit in new int[] { -1, 5 }) {
            try { RetentionPeriod.Parse("1", invalidUnit); throw new Exception("FAIL: invalid retention unit accepted"); }
            catch (FormatException) { passed++; }
        }
        foreach (DateTime start in new DateTime[] { retentionStart, DateTime.SpecifyKind(DateTime.MaxValue, DateTimeKind.Utc) }) {
            try { RetentionPeriod.Parse("999999", 4).ExpiresAt(start); throw new Exception("FAIL: retention overflow accepted"); }
            catch (FormatException) { passed++; }
        }
        Equal(retentionStart.AddMinutes(999999).ToString("o"), RetentionPeriod.Parse("999999", 0).ExpiresAt(retentionStart).ToString("o"), "maximum numeric retention supported when date fits");
        Equal("2028-03-01T23:59:30.0000000Z", RetentionPeriod.Parse("1", 4).ExpiresAt(retentionStart).ToString("o"), "month is exactly 30 days through leap February");
        // RFC 6238 Appendix B: SHA-1, 30-second step, 8 digits.
        long[] times = { 59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L };
        string[] codes = { "94287082", "07081804", "14050471", "89005924", "69279037", "65353130" };
        byte[] key = Encoding.ASCII.GetBytes("12345678901234567890");
        for (int i = 0; i < times.Length; i++)
        {
            Equal(codes[i], Totp.Generate(key, times[i], 8), "RFC TOTP vector " + i);
            Equal(codes[i].Substring(2), Totp.Generate(key, times[i], 6), "6-digit TOTP vector " + i);
        }
        Equal("287082", Totp.Generate(key, 30, 6), "interval start");
        Equal("287082", Totp.Generate(key, 59, 6), "interval end");
        Equal("359152", Totp.Generate(key, 60, 6), "interval rollover");
        Equal("755224", Totp.Generate(key, 0, 6), "leading zeros/time zero");
        Equal("12345678901234567890", Encoding.ASCII.GetString(Base32.Decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")), "Base32 RFC key");
        string[] encoded = { "MY======", "MZXQ====", "MZXW6===", "MZXW6YQ=", "MZXW6YTB", "MZXW6YTBOI======" };
        string[] decoded = { "f", "fo", "foo", "foob", "fooba", "foobar" };
        for (int i = 0; i < encoded.Length; i++)
        {
            Equal(decoded[i], Encoding.ASCII.GetString(Base32.Decode(encoded[i])), "padded Base32 " + i);
            Equal(decoded[i], Encoding.ASCII.GetString(Base32.Decode(encoded[i].TrimEnd('=').ToLowerInvariant())), "unpadded Base32 " + i);
        }
        using (AccountData data = AccountParser.Parse("demo@example.com---- x%_----y ----gezd gnbv gy3t qojq gezd gnbv gy3t qojq"))
        {
            Equal("demo@example.com", data.Account, "account");
            Equal(" x%_----y ", data.Password, "preserve password spaces and separators");
            Equal("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", data.Secret, "normalize secret");
            Equal("287082", data.Code(59), "parsed TOTP");
        }
        using (AccountData data = AccountParser.Parse("“demo@example.com----test----MY======”\r\n"))
            Equal("MY", data.Secret, "quoted line and padding");
        using (AccountData data = AccountParser.Parse("demo@example.com----test----JBSWY3DPEHPK3PXP"))
            Equal("6", data.Code(59).Length.ToString(), "sample secret format");
        // Regression: the two delimiter runs may have different lengths.
        // Synthetic credentials only; do not persist a user's pasted credentials.
        string testSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        using (AccountData data = AccountParser.Parse("demo@example.com------Demo%_Password*-----" + testSecret))
        {
            Equal("demo@example.com", data.Account, "6/5 separator account");
            Equal("Demo%_Password*", data.Password, "6/5 separator exact password");
            Equal(testSecret, data.Secret, "6/5 separator exact secret");
            Equal("287082", data.Code(59), "6/5 separator TOTP");
        }
        for (int left = 3; left <= 32; left++)
        {
            for (int right = 3; right <= 32; right++)
            {
                using (AccountData data = AccountParser.Parse("demo@example.com" + new string('-', left) +
                    "Keep-a%_Password*" + new string('-', right) + testSecret))
                {
                    Equal("demo@example.com", data.Account, "variable run account");
                    Equal("Keep-a%_Password*", data.Password, "variable run password");
                    Equal(testSecret, data.Secret, "variable run secret");
                    Equal("287082", data.Code(59), "variable run code");
                }
            }
        }
        foreach (char dash in "-‐‑‒–—―−﹘﹣－")
        {
            using (AccountData data = AccountParser.Parse("demo@example.com" + new string(dash, 6) +
                "pass-word--internal----still-password*" + new string(dash, 5) + testSecret))
            {
                Equal("pass-word--internal----still-password*", data.Password, "preserve internal runs / Unicode delimiters");
                Equal(testSecret, data.Secret, "Unicode delimiter secret");
            }
        }
        using (AccountData data = AccountParser.Parse("demo@example.com--－-－-pass-%*－—--—" + testSecret))
            Equal("pass-%*", data.Password, "mixed Unicode run");
        using (AccountData data = AccountParser.Parse(" demo@example.com \t------ x%_----y \t----- gezd gnbv gy3t qojq gezd gnbv gy3t qojq "))
        {
            Equal("demo@example.com", data.Account, "separator whitespace account");
            Equal(" x%_----y \t", data.Password, "variable separator preserves password whitespace");
            Equal(testSecret, data.Secret, "separator whitespace secret");
        }
        foreach (string separator in new string[] { "---", "===", "|||", "***", "___", ":::" , "###", "$$$", "~~~", "...", "///", "\\\\\\", ">>>", "+++", ";;;", "%%%", "@@@", "！！！", "￥￥￥", "😀😀😀", "|#|", "!@#", "---===", "-_=", "-_-" })
        {
            using (AccountData data = AccountParser.Parse("demo@example.com" + separator + "pass-word" + separator + testSecret))
            {
                Equal("demo@example.com", data.Account, "symbol run account");
                Equal("pass-word", data.Password, "symbol run password");
                Equal(testSecret, data.Secret, "symbol run secret");
                Equal(separator, data.FirstSeparator, "reported automatic separator");
                Equal("287082", data.Code(59), "symbol run code");
            }
        }
        using (AccountData data = AccountParser.Parse("demo@example.com|||p*%_Word%*=====MY======"))
        {
            Equal("p*%_Word%*", data.Password, "preserve punctuation at password boundaries");
            Equal("MY", data.Secret, "ignore Base32 padding during detection");
            Equal("=====", data.LastSeparator, "correct delimiter before padded secret");
        }
        using (AccountData data = AccountParser.Parse("demo@example.com###pass!!!inside|||" + testSecret))
        {
            Equal("pass!!!inside", data.Password, "preserve internal delimiter-like symbols");
            Equal("###", data.FirstSeparator, "different first separator");
            Equal("|||", data.LastSeparator, "different last separator");
        }
        using (AccountData data = AccountParser.Parse("demo@example.com====pass|||||MY== == =="))
            Equal("MY", data.Secret, "spaced Base32 padding");
        foreach (string separator in new string[] { "|", "--", "::", "[.*]", "<->", "SEP", "分隔", "\t", " " })
        {
            using (AccountData data = AccountParser.Parse("demo@example.com" + separator + "Keep###Symbols*" + separator + testSecret, separator))
            {
                Equal("Keep###Symbols*", data.Password, "manual literal password");
                Equal(testSecret, data.Secret, "manual literal secret");
                Equal(separator, data.FirstSeparator, "manual literal metadata");
            }
        }
        using (AccountData data = AccountParser.Parse("demo@example.com|***pass***|MY======", "|"))
            Equal("***pass***", data.Password, "manual overrides automatic symbols and preserves edges");
        using (AccountData data = AccountParser.Parse("demo@example.com=pass=MY======", "="))
            Equal("MY", data.Secret, "manual equals does not consume Base32 padding");
        using (AccountData data = AccountParser.Parse("demo@example.com----pass----inside----" + testSecret, "----"))
            Equal("pass----inside", data.Password, "manual first and last occurrences");
        Invalid("demo@example.com--pass--" + testSecret, "automatic minimum three");
        Invalid("demo@example.comaaaPASSaaa" + testSecret, "letters are not automatic symbols");
        Invalid("demo@example.com111PASS111" + testSecret, "numbers are not automatic symbols");
        Invalid("demo@example.com   PASS   " + testSecret, "whitespace is not automatic symbols");
        InvalidManual("demo@example.com|||pass|||" + testSecret, "", "empty separator");
        InvalidManual("demo@example.com|||pass|||" + testSecret, "::", "mismatch");
        InvalidManual("demo@example.com|||pass|||" + testSecret, "\n", "newline");
        InvalidManual("demo@example.com|||pass", "|||", "missing second separator");
        InvalidManual("demo@example.com||||||" + testSecret, "|||", "empty password");
        Invalid("", "empty input");
        Invalid("demo----password", "missing separator");
        Invalid("----password----MY", "empty account");
        Invalid("demo--------MY", "empty password");
        Invalid("demo----password----", "empty secret");
        Invalid("demo----password----A1", "invalid secret character");
        Invalid("demo----password----MY\nother----test----MY", "multiline");
        Invalid("demo-password-" + testSecret, "single dash is not a separator");
        Invalid("demo------password-----A1", "invalid secret with variable separators");
        foreach (string value in new string[] { "A", "AAA", "AAAAAA", "MZ", "MY=", "MY======A", "MY========", "A0", "A8", "A-", "========" }) BadBase32(value);
        AccountData disposed = AccountParser.Parse("demo----pass----MY");
        disposed.Dispose();
        Equal("", disposed.Password, "cleared password reference");
        try { disposed.Code(59); throw new Exception("FAIL: disposed key"); }
        catch (ObjectDisposedException) { passed++; }
        Console.WriteLine("PASS: " + passed + " checks; RFC TOTP vectors, rollover, Base32, parser, lifecycle.");
    }
}
