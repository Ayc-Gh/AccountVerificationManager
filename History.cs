using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace AccountSplit2FA
{
    public sealed class HistoryEntry
    {
        public const int MaxNameLength = 40;
        public string Name { get; private set; }
        public Guid Id { get; private set; }
        public string Account { get; private set; }
        public string Password { get; private set; }
        public string Secret { get; private set; }
        public string FirstSeparator { get; private set; }
        public string LastSeparator { get; private set; }
        public bool Automatic { get; private set; }
        public DateTime RecognizedAtUtc { get; private set; }
        public InputKind InputKind { get; private set; }
        public OtpKind OtpKind { get; private set; }
        public string Issuer { get; private set; }
        public string Algorithm { get; private set; }
        public int Digits { get; private set; }
        public int Period { get; private set; }
        public ulong Counter { get; private set; }
        public bool UsesUri { get; private set; }

        internal HistoryEntry(Guid id, string account, string password, string secret,
            string firstSeparator, string lastSeparator, bool automatic, DateTime recognizedAtUtc,
            InputKind inputKind = InputKind.Record, OtpKind otpKind = OtpKind.Totp, string issuer = "",
            string algorithm = "SHA1", int digits = 6, int period = 30, ulong counter = 0, bool usesUri = false, string name = "")
        {
            Id = id; Account = account; Password = password; Secret = secret;
            FirstSeparator = firstSeparator; LastSeparator = lastSeparator;
            Automatic = automatic; RecognizedAtUtc = recognizedAtUtc;
            InputKind = inputKind; OtpKind = otpKind; Issuer = issuer; Algorithm = algorithm;
            Digits = digits; Period = period; Counter = counter; UsesUri = usesUri;
            Name = NormalizeName(name);
        }

        public static string NormalizeName(string value)
        {
            string name = (value ?? "").Trim();
            if (name.Length > MaxNameLength) throw new FormatException("记录名称最多 40 个字符。");
            foreach (char c in name) if (Char.IsControl(c)) throw new FormatException("记录名称不能包含换行或控制字符。");
            return name;
        }

        internal HistoryEntry WithName(string name)
        {
            return new HistoryEntry(Id, Account, Password, Secret, FirstSeparator, LastSeparator, Automatic, RecognizedAtUtc,
                InputKind, OtpKind, Issuer, Algorithm, Digits, Period, Counter, UsesUri, name);
        }

        public AccountData Open()
        {
            byte[] key = Base32.Decode(Secret);
            try { return new AccountData(Account, Password, Secret, key, FirstSeparator, LastSeparator,
                InputKind, OtpKind, Issuer, Algorithm, Digits, Period, Counter, UsesUri); }
            catch { Array.Clear(key, 0, key.Length); throw; }
        }

        public string CurrentCode(long unixSeconds)
        {
            using (AccountData item = Open()) return item.Code(unixSeconds);
        }

        public string CopyGroup(long unixSeconds)
        {
            using (AccountData item = Open()) return item.CopyText(unixSeconds, false);
        }

        public string OriginalText()
        {
            using (AccountData item = Open()) return item.OriginalText();
        }

        public string DisplayName
        {
            get { return Name.Length > 0 ? Name : DefaultDisplayName; }
        }

        public string DefaultDisplayName
        {
            get
            {
                if (Account.Length > 0) return Issuer.Length > 0 ? Issuer + " · " + Account : Account;
                string fingerprint;
                using (SHA256 sha = SHA256.Create()) fingerprint = BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(Secret))).Replace("-", "").Substring(0, 6);
                return (Issuer.Length > 0 ? Issuer : InputKind == InputKind.Secret ? "2FA 密钥" : "Authenticator URI") + " · " + fingerprint;
            }
        }
    }

    public sealed class HistoryStore
    {
        public const int Limit = 10;
        private const int Magic = 0x41564D48;
        private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("AccountVerificationManager.History.v1");
        private readonly string path, mutexName;
        private List<HistoryEntry> entries = new List<HistoryEntry>();
        public IList<HistoryEntry> Entries { get { return entries.AsReadOnly(); } }
        public string FilePath { get { return path; } }

        public HistoryStore(string filePath)
        {
            path = Path.GetFullPath(filePath ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "AccountVerificationManager", "history.bin"));
            using (SHA256 sha = SHA256.Create())
                mutexName = "Local\\AccountVerificationManager-" + BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(path.ToUpperInvariant()))).Replace("-", "");
        }

        public void Load()
        {
            Locked(delegate { entries = Read(); });
        }

        public void Record(AccountData data, bool automatic)
        {
            Locked(delegate {
                List<HistoryEntry> next = Read();
                Guid id = Guid.NewGuid();
                string name = "";
                for (int i = next.Count - 1; i >= 0; i--)
                {
                    HistoryEntry item = next[i];
                    if (item.Account == data.Account && item.Password == data.Password && item.Secret == data.Secret &&
                        item.InputKind == data.InputKind && item.OtpKind == data.OtpKind && item.Algorithm == data.Algorithm &&
                        item.Digits == data.Digits && item.Period == data.Period && item.Counter == data.Counter && item.Issuer == data.Issuer)
                    { id = item.Id; name = item.Name; next.RemoveAt(i); }
                }
                next.Insert(0, new HistoryEntry(id, data.Account, data.Password, data.Secret,
                    data.FirstSeparator, data.LastSeparator, automatic, DateTime.UtcNow,
                    data.InputKind, data.OtpKind, data.Issuer, data.Algorithm, data.Digits, data.Period, data.Counter, data.UsesUri, name));
                if (next.Count > Limit) next.RemoveRange(Limit, next.Count - Limit);
                Write(next); entries = next;
            });
        }

        public void Clear()
        {
            // Deliberately does not read first: explicit clearing can recover an unreadable history file.
            Locked(delegate { List<HistoryEntry> empty = new List<HistoryEntry>(); Write(empty); entries = empty; });
        }

        public HistoryEntry Find(Guid id)
        {
            HistoryEntry found = null;
            Locked(delegate { entries = Read(); found = entries.Find(delegate(HistoryEntry entry) { return entry.Id == id; }); });
            return found;
        }

        public HistoryEntry Rename(Guid id, string value)
        {
            string name = HistoryEntry.NormalizeName(value);
            HistoryEntry renamed = null;
            Locked(delegate {
                List<HistoryEntry> next = Read();
                int index = next.FindIndex(delegate(HistoryEntry item) { return item.Id == id; });
                if (index < 0) throw new InvalidOperationException("记录已不在最近 10 条历史中，请刷新后重试。");
                renamed = next[index].WithName(name); next[index] = renamed;
                Write(next); entries = next;
            });
            return renamed;
        }

        public HistoryEntry AdvanceCounter(Guid id, ulong expectedCounter)
        {
            HistoryEntry advanced = null;
            Locked(delegate {
                List<HistoryEntry> next = Read();
                int index = next.FindIndex(delegate(HistoryEntry candidate) { return candidate.Id == id; });
                if (index < 0) throw new InvalidOperationException("记录已被清空或移出最近 10 条，请重新识别。");
                HistoryEntry item = next[index];
                if (item.OtpKind != OtpKind.Hotp || item.Counter != expectedCounter)
                    throw new InvalidOperationException("计数器已在其他窗口更新，请重新打开历史记录。");
                if (item.Counter == UInt64.MaxValue) throw new InvalidOperationException("HOTP 计数器已达上限。");
                advanced = new HistoryEntry(item.Id, item.Account, item.Password, item.Secret, item.FirstSeparator, item.LastSeparator,
                    item.Automatic, item.RecognizedAtUtc, item.InputKind, item.OtpKind, item.Issuer, item.Algorithm, item.Digits, item.Period, item.Counter + 1, item.UsesUri, item.Name);
                next[index] = advanced; Write(next); entries = next;
            });
            return advanced;
        }

        public HistoryEntry ReplaceOtp(HistoryEntry expected, string replacement)
        {
            HistoryEntry updated = null;
            Locked(delegate {
                List<HistoryEntry> next = Read();
                int index = next.FindIndex(delegate(HistoryEntry item) { return item.Id == expected.Id; });
                if (index < 0) throw new InvalidOperationException("原记录已被移除，请刷新历史后重试。");
                HistoryEntry current = next[index];
                if (current.OriginalText() != expected.OriginalText()) throw new InvalidOperationException("原记录已在其他窗口更新，请重新打开更换窗口。");
                using (AccountData original = current.Open())
                using (AccountData changed = original.ReplaceOtp(replacement)) {
                    foreach (HistoryEntry item in next)
                        if (item.Id != current.Id && item.Account == changed.Account && item.Password == changed.Password && item.Secret == changed.Secret &&
                            item.InputKind == changed.InputKind && item.OtpKind == changed.OtpKind && item.Issuer == changed.Issuer && item.Algorithm == changed.Algorithm &&
                            item.Digits == changed.Digits && item.Period == changed.Period && item.Counter == changed.Counter)
                            throw new InvalidOperationException("更换结果与另一条历史记录相同，请直接使用那条记录。");
                    updated = new HistoryEntry(current.Id, changed.Account, changed.Password, changed.Secret, changed.FirstSeparator, changed.LastSeparator,
                        current.Automatic, current.RecognizedAtUtc, changed.InputKind, changed.OtpKind, changed.Issuer, changed.Algorithm, changed.Digits,
                        changed.Period, changed.Counter, changed.UsesUri, current.Name);
                }
                next[index] = updated; Write(next); entries = next;
            });
            return updated;
        }

        private void Locked(Action action)
        {
            using (Mutex mutex = new Mutex(false, mutexName))
            {
                bool taken = false;
                try
                {
                    try { taken = mutex.WaitOne(3000); } catch (AbandonedMutexException) { taken = true; }
                    if (!taken) throw new IOException("历史记录正在被另一个窗口使用，请稍后重试。");
                    action();
                }
                finally { if (taken) mutex.ReleaseMutex(); }
            }
        }

        private List<HistoryEntry> Read()
        {
            if (!File.Exists(path)) return new List<HistoryEntry>();
            if (new FileInfo(path).Length > 2 * 1024 * 1024) throw new InvalidDataException("历史记录文件大小异常。");
            byte[] encrypted = File.ReadAllBytes(path);
            byte[] plain = null;
            try
            {
                plain = ProtectedData.Unprotect(encrypted, Entropy, DataProtectionScope.CurrentUser);
                if (plain.Length > 1024 * 1024) throw new InvalidDataException("历史记录内容过大。");
                using (MemoryStream stream = new MemoryStream(plain, false))
                using (BinaryReader reader = new BinaryReader(stream, Encoding.UTF8))
                {
                    if (reader.ReadInt32() != Magic) throw new InvalidDataException("历史记录格式无效。");
                    int version = reader.ReadInt32();
                    if (version < 1 || version > 3) throw new InvalidDataException("历史记录版本不兼容。");
                    int count = reader.ReadInt32();
                    if (count < 0 || count > Limit) throw new InvalidDataException("历史记录数量异常。");
                    List<HistoryEntry> result = new List<HistoryEntry>();
                    HashSet<Guid> ids = new HashSet<Guid>();
                    for (int i = 0; i < count; i++)
                    {
                        Guid id;
                        if (!Guid.TryParse(reader.ReadString(), out id) || !ids.Add(id)) throw new InvalidDataException("历史记录标识无效。");
                        string account = ReadText(reader), password = ReadText(reader), secret = ReadText(reader);
                        string first = ReadText(reader), last = ReadText(reader);
                        bool automatic = reader.ReadBoolean();
                        long ticks = reader.ReadInt64();
                        InputKind inputKind = version == 1 ? InputKind.Record : (InputKind)reader.ReadByte();
                        string issuer = version == 1 ? "" : ReadText(reader);
                        string algorithm = version == 1 ? "SHA1" : ReadText(reader);
                        int digits = version == 1 ? 6 : reader.ReadInt32(), period = version == 1 ? 30 : reader.ReadInt32();
                        OtpKind otpKind = version == 1 ? OtpKind.Totp : (OtpKind)reader.ReadByte();
                        ulong counter = version == 1 ? 0 : reader.ReadUInt64();
                        bool usesUri = version != 1 && reader.ReadBoolean();
                        string name = version >= 3 ? ReadText(reader) : "";
                        if (ticks < DateTime.MinValue.Ticks || ticks > DateTime.MaxValue.Ticks ||
                            (inputKind == InputKind.Record && (account.Length == 0 || password.Length == 0 || first.Length == 0 || last.Length == 0)))
                            throw new InvalidDataException("历史记录字段无效。");
                        HistoryEntry entry = new HistoryEntry(id, account, password, secret, first, last, automatic, new DateTime(ticks, DateTimeKind.Utc),
                            inputKind, otpKind, issuer, algorithm, digits, period, counter, usesUri, name);
                        using (AccountData validated = entry.Open()) { }
                        result.Add(entry);
                    }
                    if (stream.Position != stream.Length) throw new InvalidDataException("历史记录包含无效内容。");
                    return result;
                }
            }
            finally
            {
                Array.Clear(encrypted, 0, encrypted.Length);
                if (plain != null) Array.Clear(plain, 0, plain.Length);
            }
        }

        private static string ReadText(BinaryReader reader)
        {
            string value = reader.ReadString();
            if (value.Length > 16384) throw new InvalidDataException("历史记录字段过长。");
            return value;
        }

        private void Write(List<HistoryEntry> next)
        {
            byte[] plain = null, encrypted = null;
            string temporary = path + ".tmp." + Guid.NewGuid().ToString("N");
            try
            {
                using (MemoryStream stream = new MemoryStream())
                {
                    using (BinaryWriter writer = new BinaryWriter(stream, Encoding.UTF8, true))
                    {
                        writer.Write(Magic); writer.Write(3); writer.Write(next.Count);
                        foreach (HistoryEntry item in next)
                        {
                            writer.Write(item.Id.ToString("N")); writer.Write(item.Account); writer.Write(item.Password); writer.Write(item.Secret);
                            writer.Write(item.FirstSeparator); writer.Write(item.LastSeparator); writer.Write(item.Automatic); writer.Write(item.RecognizedAtUtc.Ticks);
                            writer.Write((byte)item.InputKind); writer.Write(item.Issuer); writer.Write(item.Algorithm); writer.Write(item.Digits);
                            writer.Write(item.Period); writer.Write((byte)item.OtpKind); writer.Write(item.Counter); writer.Write(item.UsesUri);
                            writer.Write(item.Name);
                        }
                    }
                    plain = stream.ToArray();
                    // Remove plaintext from MemoryStream's backing buffer as well.
                    Array.Clear(stream.GetBuffer(), 0, (int)stream.Length);
                }
                encrypted = ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                using (FileStream file = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                { file.Write(encrypted, 0, encrypted.Length); file.Flush(true); }
                if (File.Exists(path)) File.Replace(temporary, path, null);
                else File.Move(temporary, path);
            }
            finally
            {
                if (plain != null) Array.Clear(plain, 0, plain.Length);
                if (encrypted != null) Array.Clear(encrypted, 0, encrypted.Length);
                if (File.Exists(temporary)) File.Delete(temporary);
            }
        }
    }
}
