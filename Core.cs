using System;
using System.Collections.Generic;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace AccountSplit2FA
{
    public sealed class AccountData : IDisposable
    {
        public string Account { get; private set; }
        public string Password { get; private set; }
        public string Secret { get; private set; }
        public string FirstSeparator { get; private set; }
        public string LastSeparator { get; private set; }
        private byte[] key;

        public AccountData(string account, string password, string secret, byte[] keyBytes,
            string firstSeparator, string lastSeparator)
        {
            Account = account; Password = password; Secret = secret; key = keyBytes;
            FirstSeparator = firstSeparator; LastSeparator = lastSeparator;
        }

        public string Code(long unixSeconds)
        {
            if (key == null) throw new ObjectDisposedException("AccountData");
            return Totp.Generate(key, unixSeconds, 6);
        }

        public void Dispose()
        {
            if (key != null) { Array.Clear(key, 0, key.Length); key = null; }
            Account = Password = Secret = String.Empty;
            FirstSeparator = LastSeparator = String.Empty;
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

            SeparatorSpan first, last;
            if (manualSeparator == null)
            {
                List<SeparatorSpan> matches = FindAutomaticSeparators(text);
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
                int scanEnd = SeparatorScanEnd(text);
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
            string secret = Base32.Normalize(text.Substring(last.Index + last.Length));
            if (account.Length == 0) throw new FormatException("账号不能为空。");
            if (password.Length == 0) throw new FormatException("密码不能为空。");
            byte[] key = Base32.Decode(secret);
            return new AccountData(account, password, secret.TrimEnd('='), key,
                text.Substring(first.Index, first.Length), text.Substring(last.Index, last.Length));
        }

        private static List<SeparatorSpan> FindAutomaticSeparators(string text)
        {
            List<SeparatorSpan> matches = new List<SeparatorSpan>();
            // Standard Base32 padding belongs to the secret, not the last delimiter.
            int scanEnd = SeparatorScanEnd(text);
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
            if (unixSeconds < 0) throw new ArgumentOutOfRangeException("unixSeconds");
            if (digits != 6 && digits != 8) throw new ArgumentOutOfRangeException("digits");
            ulong counter = (ulong)(unixSeconds / 30);
            byte[] message = new byte[8];
            for (int i = 7; i >= 0; i--) { message[i] = (byte)(counter & 255); counter >>= 8; }
            byte[] hash;
            using (HMACSHA1 hmac = new HMACSHA1(secret)) { hash = hmac.ComputeHash(message); }
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
