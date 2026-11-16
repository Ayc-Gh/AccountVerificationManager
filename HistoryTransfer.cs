using System;
using System.IO;
using System.Text;
using System.Collections.Generic;
using System.Security.Cryptography;

namespace AccountSplit2FA
{
    // AVMX0001: magic(8), salt(16), IV(16), AES-CBC ciphertext, HMAC-SHA256(32).
    // PBKDF2-HMAC-SHA256, 210000 iterations, UTF-8 password, 64 bytes (AES key then MAC key).
    public static class HistoryTransfer
    {
        public const int MaxBytes = 2 * 1024 * 1024;
        private static readonly byte[] Magic = Encoding.ASCII.GetBytes("AVMX0001");
        private static readonly DateTime Epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        public static byte[] Encrypt(IList<HistoryEntry> entries, string password)
        {
            if (String.IsNullOrEmpty(password)) throw new FormatException("请输入文件密码。");
            byte[] plain = Encode(entries), salt = new byte[16], iv = new byte[16], keys = null;
            try {
                using (RandomNumberGenerator rng = RandomNumberGenerator.Create()) { rng.GetBytes(salt); rng.GetBytes(iv); }
                keys = Derive(password, salt);
                byte[] encrypted;
                using (Aes aes = Aes.Create()) { aes.Key = Part(keys, 0, 32); aes.IV = iv; using (ICryptoTransform transform = aes.CreateEncryptor()) encrypted = transform.TransformFinalBlock(plain, 0, plain.Length); }
                byte[] result = new byte[40 + encrypted.Length + 32];
                Buffer.BlockCopy(Magic, 0, result, 0, 8); Buffer.BlockCopy(salt, 0, result, 8, 16); Buffer.BlockCopy(iv, 0, result, 24, 16); Buffer.BlockCopy(encrypted, 0, result, 40, encrypted.Length);
                using (HMACSHA256 mac = new HMACSHA256(Part(keys, 32, 32))) { byte[] tag = mac.ComputeHash(result, 0, result.Length - 32); Buffer.BlockCopy(tag, 0, result, result.Length - 32, 32); }
                if (result.Length > MaxBytes) throw new FormatException("导出内容过大。"); return result;
            } finally { Array.Clear(plain, 0, plain.Length); if (keys != null) Array.Clear(keys, 0, keys.Length); }
        }
        public static List<HistoryEntry> Decrypt(byte[] file, string password)
        {
            if (String.IsNullOrEmpty(password)) throw new FormatException("请输入文件密码。");
            if (file.Length < 88 || file.Length > MaxBytes || (file.Length - 72) % 16 != 0 || Encoding.ASCII.GetString(file, 0, 8) != "AVMX0001") throw new FormatException("文件格式无效，请选择 .avmx 加密文件。");
            byte[] keys = Derive(password, Part(file, 8, 16)), plain = null;
            try {
                byte[] tag; using (HMACSHA256 mac = new HMACSHA256(Part(keys, 32, 32))) tag = mac.ComputeHash(file, 0, file.Length - 32);
                int different = 0; for (int i = 0; i < 32; i++) different |= tag[i] ^ file[file.Length - 32 + i];
                if (different != 0) throw new FormatException("密码错误或文件已损坏。");
                using (Aes aes = Aes.Create()) { aes.Key = Part(keys, 0, 32); aes.IV = Part(file, 24, 16); using (ICryptoTransform transform = aes.CreateDecryptor()) plain = transform.TransformFinalBlock(file, 40, file.Length - 72); }
                return Decode(plain);
            } catch (CryptographicException) { throw new FormatException("密码错误或文件已损坏。"); }
            finally { Array.Clear(keys, 0, keys.Length); if (plain != null) Array.Clear(plain, 0, plain.Length); }
        }
        private static byte[] Derive(string password, byte[] salt)
        {
            byte[] input = Encoding.UTF8.GetBytes(password);
            try { using (Rfc2898DeriveBytes kdf = new Rfc2898DeriveBytes(input, salt, 210000, HashAlgorithmName.SHA256)) return kdf.GetBytes(64); }
            finally { Array.Clear(input, 0, input.Length); }
        }
        private static byte[] Part(byte[] bytes, int offset, int count) { byte[] part = new byte[count]; Buffer.BlockCopy(bytes, offset, part, 0, count); return part; }
        private static void Long(BinaryWriter writer, long value) { for (int i = 7; i >= 0; i--) writer.Write((byte)(value >> (i * 8))); }
        private static long Long(BinaryReader reader) { ulong value = 0; for (int i = 0; i < 8; i++) value = (value << 8) | reader.ReadByte(); return unchecked((long)value); }
        private static void Text(BinaryWriter writer, string value) { byte[] bytes = Encoding.UTF8.GetBytes(value); if (bytes.Length > 131072) throw new FormatException("字段过长。"); Long(writer, bytes.Length); writer.Write(bytes); }
        private static string Text(BinaryReader reader) { long n = Long(reader); if (n < 0 || n > 131072 || n > reader.BaseStream.Length - reader.BaseStream.Position) throw new FormatException("文件字段无效。"); return new UTF8Encoding(false, true).GetString(reader.ReadBytes((int)n)); }
        private static byte[] Encode(IList<HistoryEntry> entries)
        {
            if (entries.Count < 1 || entries.Count > HistoryStore.Limit) throw new FormatException("请选择 1 至 10 条记录。");
            using (MemoryStream stream = new MemoryStream()) {
                using (BinaryWriter writer = new BinaryWriter(stream, Encoding.UTF8, true)) {
                    Long(writer, entries.Count);
                    foreach (HistoryEntry e in entries) {
                        if (e.IsExpired(DateTime.UtcNow)) throw new FormatException("选中的记录已到期，请刷新后重新选择。");
                        Text(writer, e.InputKind == InputKind.Record ? "RECORD" : e.InputKind == InputKind.Secret ? "SECRET" : "URI");
                        Text(writer, e.Account); Text(writer, e.Password); using (AccountData data = e.Open()) Text(writer, data.ToUri());
                        Text(writer, e.Name); Text(writer, e.FirstSeparator); Text(writer, e.LastSeparator);
                        Long(writer, (long)(e.RecognizedAtUtc - Epoch).TotalMilliseconds); Long(writer, e.ExpiresAtUtc.HasValue ? (long)(e.ExpiresAtUtc.Value - Epoch).TotalMilliseconds : 0);
                        writer.Write(e.Automatic); writer.Write(e.Pinned);
                    }
                }
                byte[] bytes = stream.ToArray(); Array.Clear(stream.GetBuffer(), 0, (int)stream.Length); return bytes;
            }
        }
        private static List<HistoryEntry> Decode(byte[] plain)
        {
            try {
                using (BinaryReader reader = new BinaryReader(new MemoryStream(plain, false), Encoding.UTF8)) {
                    long count = Long(reader); if (count < 1 || count > HistoryStore.Limit) throw new FormatException("文件记录数无效。");
                    List<HistoryEntry> entries = new List<HistoryEntry>();
                    for (int i = 0; i < count; i++) {
                        string kind = Text(reader), account = Text(reader), password = Text(reader), uri = Text(reader), name = Text(reader), first = Text(reader), last = Text(reader);
                        long recognized = Long(reader), expires = Long(reader); bool automatic = reader.ReadBoolean(), pinned = reader.ReadBoolean();
                        if (recognized < 0 || expires < 0 || (kind != "RECORD" && kind != "SECRET" && kind != "URI")) throw new FormatException("文件记录字段无效。");
                        if (kind == "RECORD" && (account.Length == 0 || password.Length == 0 || first.Length == 0 || last.Length == 0)) throw new FormatException("账号记录字段不完整。");
                        using (AccountData otp = SmartParser.ParseUri(uri)) {
                            InputKind type = kind == "RECORD" ? InputKind.Record : kind == "SECRET" ? InputKind.Secret : InputKind.AuthenticatorUri;
                            if (type != InputKind.Record && (password.Length > 0 || first.Length > 0 || last.Length > 0)) throw new FormatException("文件字段不一致。");
                            if (type == InputKind.Secret && (account.Length > 0 || otp.Issuer.Length > 0 || otp.OtpKind != OtpKind.Totp || otp.Algorithm != "SHA1" || otp.Digits != 6 || otp.Period != 30)) throw new FormatException("独立密钥参数无效。");
                            entries.Add(new HistoryEntry(Guid.NewGuid(), account, password, otp.Secret, first, last, automatic, Epoch.AddMilliseconds(recognized), type, otp.OtpKind, otp.Issuer, otp.Algorithm, otp.Digits, otp.Period, otp.Counter, type != InputKind.Secret, name, expires == 0 ? (DateTime?)null : Epoch.AddMilliseconds(expires), pinned));
                        }
                    }
                    if (reader.BaseStream.Position != reader.BaseStream.Length) throw new FormatException("文件包含多余数据。"); return entries;
                }
            } catch (EndOfStreamException) { throw new FormatException("文件内容不完整。"); }
            catch (ArgumentException) { throw new FormatException("文件字段无效。"); }
        }
    }
}
