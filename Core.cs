using System;
using System.Collections.Generic;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace AccountSplit2FA
{
    public enum InputKind { Record = 0, Secret = 1, AuthenticatorUri = 2 }
    public enum OtpKind { Totp = 0, Hotp = 1 }

    public sealed class AccountData : IDisposable
    {
        public string Account { get; private set; }
        public string Password { get; private set; }
        public string Secret { get; private set; }
        public string FirstSeparator { get; private set; }
        public string LastSeparator { get; private set; }
        public InputKind InputKind { get; private set; }
        public OtpKind OtpKind { get; private set; }
        public string Issuer { get; private set; }
        public string Algorithm { get; private set; }
        public int Digits { get; private set; }
        public int Period { get; private set; }
        public ulong Counter { get; private set; }
        public bool UsesUri { get; private set; }
        private byte[] key;

        public AccountData(string account, string password, string secret, byte[] keyBytes,
            string firstSeparator, string lastSeparator)
            : this(account, password, secret, keyBytes, firstSeparator, lastSeparator,
                InputKind.Record, OtpKind.Totp, "", "SHA1", 6, 30, 0, false) { }

        public AccountData(string account, string password, string secret, byte[] keyBytes,
            string firstSeparator, string lastSeparator, InputKind inputKind, OtpKind otpKind,
            string issuer, string algorithm, int digits, int period, ulong counter, bool usesUri)
        {
            if (!Enum.IsDefined(typeof(InputKind), inputKind) || !Enum.IsDefined(typeof(OtpKind), otpKind))
                throw new FormatException("无法识别的认证类型。");
            if (algorithm != "SHA1" && algorithm != "SHA256" && algorithm != "SHA512")
                throw new FormatException("算法仅支持 SHA1、SHA256、SHA512。");
            if (digits != 6 && digits != 8) throw new FormatException("验证码位数仅支持 6 位或 8 位。");
            if (period <= 0) throw new FormatException("刷新周期必须是正整数秒。");
            Account = account; Password = password; Secret = secret; key = keyBytes;
            FirstSeparator = firstSeparator; LastSeparator = lastSeparator;
            InputKind = inputKind; OtpKind = otpKind; Issuer = issuer; Algorithm = algorithm;
            Digits = digits; Period = period; Counter = counter; UsesUri = usesUri;
        }

        public string Code(long unixSeconds)
        {
            if (key == null) throw new ObjectDisposedException("AccountData");
            return OtpKind == OtpKind.Hotp ? Totp.GenerateCounter(key, Counter, Digits, Algorithm) :
                Totp.Generate(key, unixSeconds, Digits, Period, Algorithm);
        }

        public AccountData ReplaceOtp(string replacement)
        {
            using (AccountData incoming = SmartParser.Parse(replacement)) {
                if (incoming.InputKind == InputKind.Record) throw new FormatException("请只粘贴新 2FA 密钥或 Authenticator URI。");
                bool uri = incoming.InputKind == InputKind.AuthenticatorUri;
                if (!uri && OtpKind == OtpKind.Hotp) throw new FormatException("更换 HOTP 请使用包含 counter 的完整 Authenticator URI。");
                InputKind kind = InputKind == InputKind.Record ? InputKind.Record : uri || InputKind == InputKind.AuthenticatorUri ? InputKind.AuthenticatorUri : InputKind.Secret;
                byte[] nextKey = Base32.Decode(incoming.Secret);
                try { return new AccountData(Account, Password, incoming.Secret, nextKey, FirstSeparator, LastSeparator, kind,
                    uri ? incoming.OtpKind : OtpKind, uri ? incoming.Issuer : Issuer, uri ? incoming.Algorithm : Algorithm,
                    uri ? incoming.Digits : Digits, uri ? incoming.Period : Period, uri ? incoming.Counter : Counter, uri || UsesUri); }
                catch { Array.Clear(nextKey, 0, nextKey.Length); throw; }
            }
        }

        public string OriginalText()
        {
            if (InputKind == InputKind.Secret) return Secret;
            string otp = UsesUri ? ToUri() : Secret;
            return InputKind == InputKind.Record ? Account + FirstSeparator + Password + LastSeparator + otp : otp;
        }

        public string ToUri()
        {
            string label = (Issuer.Length > 0 ? Issuer + ":" : "") + Account;
            return "otpauth://" + (OtpKind == OtpKind.Hotp ? "hotp/" : "totp/") + Uri.EscapeDataString(label) +
                "?secret=" + Uri.EscapeDataString(Secret) + (Issuer.Length > 0 ? "&issuer=" + Uri.EscapeDataString(Issuer) : "") +
                "&algorithm=" + Algorithm + "&digits=" + Digits.ToString(CultureInfo.InvariantCulture) +
                (OtpKind == OtpKind.Hotp ? "&counter=" + Counter.ToString(CultureInfo.InvariantCulture) : "&period=" + Period.ToString(CultureInfo.InvariantCulture));
        }

        public string CopyText(long unixSeconds, bool includeSecret)
        {
            StringBuilder text = new StringBuilder();
            if (Account.Length > 0) text.Append("账号：").Append(Account).Append("\r\n");
            if (Password.Length > 0) text.Append("密码：").Append(Password).Append("\r\n");
            if (includeSecret) text.Append("2FA密钥：").Append(Secret).Append("\r\n");
            return text.Append("验证码：").Append(Code(unixSeconds)).ToString();
        }

        public void Dispose()
        {
            if (key != null) { Array.Clear(key, 0, key.Length); key = null; }
            Account = Password = Secret = String.Empty;
            FirstSeparator = LastSeparator = String.Empty;
            Issuer = String.Empty;
        }
    }

    public static class SmartParser
    {
        public static string Clean(string input)
        {
            if (String.IsNullOrWhiteSpace(input)) throw new FormatException("请粘贴账号记录、纯 2FA 密钥或 Authenticator URI。");
            string text = input.Trim();
            if (text.Length > 16384) throw new FormatException("输入过长，请一次粘贴一条记录。");
            if (text.Length >= 2 && ((text[0] == '“' && text[text.Length - 1] == '”') || (text[0] == '"' && text[text.Length - 1] == '"')))
                text = text.Substring(1, text.Length - 2).Trim();
            return text;
        }

        public static AccountData Parse(string input) { return Parse(input, null); }
        public static AccountData Parse(string input, string separator)
        {
            string text = Clean(input);
            if (text.StartsWith("otpauth:", StringComparison.OrdinalIgnoreCase)) return ParseUri(text);
            if (text.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || text.StartsWith("https://", StringComparison.OrdinalIgnoreCase) ||
                text.StartsWith("otpauth-migration:", StringComparison.OrdinalIgnoreCase))
                throw new FormatException("请使用 otpauth:// 开头的单个认证器 URI；不支持网页地址或批量迁移链接。");
            bool base32 = true;
            foreach (char c in text)
                if (!Char.IsWhiteSpace(c) && c != '=' && "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".IndexOf(Char.ToUpperInvariant(c)) < 0) { base32 = false; break; }
            if (base32) return ParseSecret(text);
            return AccountParser.Parse(text, separator);
        }

        public static AccountData ParseSecret(string secret)
        {
            secret = Base32.Normalize(secret);
            byte[] key = Base32.Decode(secret);
            return new AccountData("", "", secret.TrimEnd('='), key, "", "", InputKind.Secret,
                OtpKind.Totp, "", "SHA1", 6, 30, 0, false);
        }

        public static AccountData ParseUri(string text)
        {
            text = Clean(text);
            Uri uri;
            if (text.IndexOfAny(new char[] { '\r', '\n' }) >= 0 || !Uri.TryCreate(text, UriKind.Absolute, out uri) ||
                !uri.Scheme.Equals("otpauth", StringComparison.OrdinalIgnoreCase) || text.IndexOf("otpauth://", StringComparison.OrdinalIgnoreCase) != 0 ||
                uri.UserInfo.Length != 0 || uri.Port != -1 || uri.Fragment.Length != 0)
                throw new FormatException("Authenticator URI 格式无效，请粘贴完整的 otpauth:// 链接。");
            string type = uri.Host.ToLowerInvariant();
            if (type != "totp" && type != "hotp") throw new FormatException("Authenticator URI 类型仅支持 totp 或 hotp。");
            // Decode the original components once; malformed percent escapes must not be silently repaired by Uri.
            int queryIndex = text.IndexOf('?');
            int pathIndex = text.IndexOf('/', "otpauth://".Length);
            string rawLabel = pathIndex < 0 || (queryIndex >= 0 && pathIndex > queryIndex) ? "" :
                text.Substring(pathIndex + 1, (queryIndex < 0 ? text.Length : queryIndex) - pathIndex - 1);
            string label = Decode(rawLabel, false), issuerFromLabel = "", account = label.Trim();
            int colon = label.IndexOf(':');
            if (colon >= 0) { issuerFromLabel = label.Substring(0, colon).Trim(); account = label.Substring(colon + 1).Trim(); }
            Dictionary<string, string> parameters = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            if (queryIndex >= 0)
                foreach (string pair in text.Substring(queryIndex + 1).Split('&'))
                {
                    if (pair.Length == 0) continue;
                    int equals = pair.IndexOf('=');
                    string name = Decode(equals < 0 ? pair : pair.Substring(0, equals), true);
                    string value = Decode(equals < 0 ? "" : pair.Substring(equals + 1), true);
                    if (parameters.ContainsKey(name)) throw new FormatException("Authenticator URI 包含重复参数，请检查链接。");
                    parameters.Add(name, value);
                }
            string secret;
            if (!parameters.TryGetValue("secret", out secret) || String.IsNullOrWhiteSpace(secret)) throw new FormatException("Authenticator URI 缺少 secret 密钥参数。");
            string algorithm = parameters.ContainsKey("algorithm") ? parameters["algorithm"].Trim().ToUpperInvariant().Replace("-", "") : "SHA1";
            int digits = Number(parameters, "digits", 6), period = Number(parameters, "period", 30);
            ulong counter = 0;
            if (type == "hotp" && (!parameters.ContainsKey("counter") || !UInt64.TryParse(parameters["counter"], NumberStyles.None, CultureInfo.InvariantCulture, out counter)))
                throw new FormatException("HOTP URI 必须提供有效的非负 counter 计数器。");
            string issuer = parameters.ContainsKey("issuer") ? parameters["issuer"].Trim() : issuerFromLabel;
            secret = Base32.Normalize(secret);
            // Clear the decoded key if unsupported settings prevent construction.
            byte[] key = Base32.Decode(secret);
            try { return new AccountData(account, "", secret.TrimEnd('='), key, "", "", InputKind.AuthenticatorUri,
                type == "hotp" ? OtpKind.Hotp : OtpKind.Totp, issuer, algorithm, digits, period, counter, true); }
            catch { Array.Clear(key, 0, key.Length); throw; }
        }

        private static int Number(Dictionary<string, string> parameters, string name, int fallback)
        {
            string value; int result;
            if (!parameters.TryGetValue(name, out value)) return fallback;
            if (!Int32.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out result) || result <= 0)
                throw new FormatException("URI 的 " + name + " 参数必须是正整数。");
            return result;
        }

        private static string Decode(string value, bool query)
        {
            StringBuilder decoded = new StringBuilder();
            for (int i = 0; i < value.Length; i++)
                if (value[i] == '%')
                {
                    List<byte> bytes = new List<byte>();
                    while (i < value.Length && value[i] == '%')
                    {
                        if (i + 2 >= value.Length || !Uri.IsHexDigit(value[i + 1]) || !Uri.IsHexDigit(value[i + 2]))
                            throw new FormatException("Authenticator URI 包含无效的百分号编码。");
                        bytes.Add(Byte.Parse(value.Substring(i + 1, 2), NumberStyles.HexNumber, CultureInfo.InvariantCulture));
                        i += 3;
                    }
                    try { decoded.Append(new UTF8Encoding(false, true).GetString(bytes.ToArray())); }
                    catch (DecoderFallbackException) { throw new FormatException("Authenticator URI 包含无效的 UTF-8 编码。"); }
                    i--;
                }
                else decoded.Append(query && value[i] == '+' ? ' ' : value[i]);
            return decoded.ToString();
        }
    }

    public static class AccountParser
    {
        private sealed class SeparatorSpan
        {
            public int Index, Length;
            public SeparatorSpan(int index, int length) { Index = index; Length = length; }
        }

        public static AccountData Parse(string input)
        {
            return Parse(input, null);
        }

        // null means automatic detection; other values are exact, literal delimiters.
        public static AccountData Parse(string input, string manualSeparator)
        {
            if (String.IsNullOrWhiteSpace(input)) throw new FormatException("请先粘贴账号内容。");
            string text = input.Trim();
            if (text.Length >= 2 && ((text[0] == '“' && text[text.Length - 1] == '”') ||
                (text[0] == '"' && text[text.Length - 1] == '"')))
                text = text.Substring(1, text.Length - 2).Trim();
            if (text.IndexOfAny(new char[] { '\r', '\n' }) >= 0)
                throw new FormatException("每次请粘贴一条账号记录，不要包含多行内容。");

            // A URI in the last field contains punctuation such as ://; it is not a record delimiter.
            int uriIndex = text.IndexOf("otpauth://", StringComparison.OrdinalIgnoreCase);
            string separatorText = uriIndex > 0 ? text.Substring(0, uriIndex) : text;
            SeparatorSpan first, last;
            if (manualSeparator == null)
            {
                List<SeparatorSpan> matches = FindAutomaticSeparators(separatorText, uriIndex <= 0);
                if (matches.Count < 2)
                    throw new FormatException("未找到两处分隔符：自动模式需连续 3 个及以上符号，也可切换到手动模式指定。");
                first = matches[0]; last = matches[matches.Count - 1];
            }
            else
            {
                if (manualSeparator.Length == 0)
                    throw new FormatException("请填写手动分隔符。");
                if (manualSeparator.IndexOfAny(new char[] { '\r', '\n' }) >= 0)
                    throw new FormatException("手动分隔符不能包含换行。");
                int firstIndex = text.IndexOf(manualSeparator, StringComparison.Ordinal);
                int scanEnd = uriIndex > 0 ? separatorText.Length : SeparatorScanEnd(text);
                int lastIndex = scanEnd == 0 ? -1 : text.LastIndexOf(manualSeparator, scanEnd - 1, scanEnd, StringComparison.Ordinal);
                if (firstIndex < 0 || lastIndex < firstIndex + manualSeparator.Length)
                    throw new FormatException("未找到两处手动分隔符，请检查填写的内容与原始记录是否一致。");
                first = new SeparatorSpan(firstIndex, manualSeparator.Length);
                last = new SeparatorSpan(lastIndex, manualSeparator.Length);
            }
            string account = text.Substring(0, first.Index).Trim();
            // First and last runs delimit the fields; runs inside the password stay intact.
            int passwordStart = first.Index + first.Length;
            string password = text.Substring(passwordStart, last.Index - passwordStart);
            string secret = text.Substring(last.Index + last.Length).Trim();
            if (account.Length == 0) throw new FormatException("账号不能为空。");
            if (password.Length == 0) throw new FormatException("密码不能为空。");
            using (AccountData otp = secret.StartsWith("otpauth:", StringComparison.OrdinalIgnoreCase) ? SmartParser.ParseUri(secret) : SmartParser.ParseSecret(secret))
                return new AccountData(account, password, otp.Secret, Base32.Decode(otp.Secret),
                    text.Substring(first.Index, first.Length), text.Substring(last.Index, last.Length), InputKind.Record,
                    otp.OtpKind, otp.Issuer, otp.Algorithm, otp.Digits, otp.Period, otp.Counter, otp.UsesUri);
        }

        private static List<SeparatorSpan> FindAutomaticSeparators(string text, bool trimPadding)
        {
            List<SeparatorSpan> matches = new List<SeparatorSpan>();
            // Standard Base32 padding belongs to the secret, not the last delimiter.
            int scanEnd = trimPadding ? SeparatorScanEnd(text) : text.Length;
            for (int i = 0; i < scanEnd; )
            {
                int length = SymbolLength(text, i);
                if (length == 0) { i++; continue; }
                int start = i, count = 0;
                while (i < scanEnd && (length = SymbolLength(text, i)) > 0) { i += length; count++; }
                if (count < 3) continue;
                int end = i, repeatedStart = -1, repeatedEnd = -1;
                // Prefer repeated symbols within a run: pass*----- must preserve the *.
                // Runs with no repeated group (such as |#|) use the whole symbol run.
                for (int j = start; j < end; )
                {
                    int groupStart = j, symbolLength = SymbolLength(text, j), groupCount = 1;
                    j += symbolLength;
                    while (j < end && SameSymbol(text, groupStart, j))
                    { j += SymbolLength(text, j); groupCount++; }
                    if (groupCount >= 3)
                    {
                        if (repeatedStart < 0) repeatedStart = groupStart;
                        repeatedEnd = j;
                    }
                }
                if (repeatedStart >= 0) matches.Add(new SeparatorSpan(repeatedStart, repeatedEnd - repeatedStart));
                else matches.Add(new SeparatorSpan(start, end - start));
            }
            return matches;
        }

        private static int SeparatorScanEnd(string text)
        {
            int end = text.Length;
            while (end > 0 && (text[end - 1] == '=' || Char.IsWhiteSpace(text[end - 1]))) end--;
            return end;
        }

        private static int SymbolLength(string text, int index)
        {
            UnicodeCategory category = CharUnicodeInfo.GetUnicodeCategory(text, index);
            bool symbol = category == UnicodeCategory.ConnectorPunctuation || category == UnicodeCategory.DashPunctuation ||
                category == UnicodeCategory.OpenPunctuation || category == UnicodeCategory.ClosePunctuation ||
                category == UnicodeCategory.InitialQuotePunctuation || category == UnicodeCategory.FinalQuotePunctuation ||
                category == UnicodeCategory.OtherPunctuation || category == UnicodeCategory.MathSymbol ||
                category == UnicodeCategory.CurrencySymbol || category == UnicodeCategory.ModifierSymbol ||
                category == UnicodeCategory.OtherSymbol;
            if (!symbol) return 0;
            return Char.IsHighSurrogate(text[index]) && index + 1 < text.Length && Char.IsLowSurrogate(text[index + 1]) ? 2 : 1;
        }

        private static bool SameSymbol(string text, int first, int second)
        {
            int firstLength = SymbolLength(text, first), secondLength = SymbolLength(text, second);
            if (firstLength == 1 && secondLength == 1 && IsDash(text[first]) && IsDash(text[second])) return true;
            return firstLength == secondLength && String.CompareOrdinal(text, first, text, second, firstLength) == 0;
        }

        private static bool IsDash(char symbol)
        {
            return "-‐‑‒–—―−﹘﹣－".IndexOf(symbol) >= 0;
        }
    }

    public static class Base32
    {
        private const string Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        public static string Normalize(string input)
        {
            StringBuilder result = new StringBuilder();
            foreach (char c in input)
                if (!Char.IsWhiteSpace(c)) result.Append(Char.ToUpperInvariant(c));
            return result.ToString();
        }

        public static byte[] Decode(string input)
        {
            string value = Normalize(input);
            if (value.Length == 0) throw new FormatException("2FA 密钥不能为空。");
            int paddingStart = value.IndexOf('=');
            string raw = paddingStart < 0 ? value : value.Substring(0, paddingStart);
            if (paddingStart >= 0)
            {
                for (int i = paddingStart; i < value.Length; i++)
                    if (value[i] != '=') throw new FormatException("2FA 密钥的填充符号只能出现在末尾。");
                if (value.Length % 8 != 0 || value.Length - raw.Length > 6)
                    throw new FormatException("2FA 密钥的 Base32 填充格式不正确。");
            }
            int remainder = raw.Length % 8;
            if (remainder == 1 || remainder == 3 || remainder == 6 || raw.Length == 0)
                throw new FormatException("2FA 密钥长度不正确，请检查是否复制完整。");
            List<byte> bytes = new List<byte>();
            int buffer = 0, bits = 0;
            foreach (char c in raw)
            {
                int digit = Alphabet.IndexOf(c);
                if (digit < 0) throw new FormatException("2FA 密钥只能包含 A–Z 和 2–7；末尾可带 =。");
                buffer = (buffer << 5) | digit;
                bits += 5;
                if (bits >= 8)
                {
                    bits -= 8;
                    bytes.Add((byte)((buffer >> bits) & 255));
                    buffer &= (1 << bits) - 1;
                }
            }
            if (bits > 0 && buffer != 0)
                throw new FormatException("2FA 密钥末尾无效，请检查是否复制正确。");
            return bytes.ToArray();
        }
    }

    public static class Totp
    {
        public static long UnixNow()
        {
            return (long)Math.Floor((DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalSeconds);
        }

        public static string Generate(byte[] secret, long unixSeconds, int digits)
        {
            return Generate(secret, unixSeconds, digits, 30, "SHA1");
        }

        public static string Generate(byte[] secret, long unixSeconds, int digits, int period, string algorithm)
        {
            if (unixSeconds < 0) throw new ArgumentOutOfRangeException("unixSeconds");
            if (period <= 0) throw new ArgumentOutOfRangeException("period");
            return GenerateCounter(secret, (ulong)(unixSeconds / period), digits, algorithm);
        }

        public static string GenerateCounter(byte[] secret, ulong counter, int digits, string algorithm)
        {
            if (digits != 6 && digits != 8) throw new ArgumentOutOfRangeException("digits");
            byte[] message = new byte[8];
            for (int i = 7; i >= 0; i--) { message[i] = (byte)(counter & 255); counter >>= 8; }
            byte[] hash;
            HMAC hmac;
            if (algorithm == "SHA1") hmac = new HMACSHA1(secret);
            else if (algorithm == "SHA256") hmac = new HMACSHA256(secret);
            else if (algorithm == "SHA512") hmac = new HMACSHA512(secret);
            else throw new ArgumentException("Unsupported OTP algorithm", "algorithm");
            using (hmac) { hash = hmac.ComputeHash(message); }
            int offset = hash[hash.Length - 1] & 15;
            int binary = ((hash[offset] & 127) << 24) | (hash[offset + 1] << 16) |
                (hash[offset + 2] << 8) | hash[offset + 3];
            int modulus = digits == 6 ? 1000000 : 100000000;
            string code = (binary % modulus).ToString(new string('0', digits), CultureInfo.InvariantCulture);
            Array.Clear(hash, 0, hash.Length);
            return code;
        }
    }
}
