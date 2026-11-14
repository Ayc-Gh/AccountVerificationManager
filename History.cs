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
        public Guid Id { get; private set; }
        public string Account { get; private set; }
        public string Password { get; private set; }
        public string Secret { get; private set; }
        public string FirstSeparator { get; private set; }
        public string LastSeparator { get; private set; }
        public bool Automatic { get; private set; }
        public DateTime RecognizedAtUtc { get; private set; }

        internal HistoryEntry(Guid id, string account, string password, string secret,
            string firstSeparator, string lastSeparator, bool automatic, DateTime recognizedAtUtc)
        {
            Id = id; Account = account; Password = password; Secret = secret;
            FirstSeparator = firstSeparator; LastSeparator = lastSeparator;
            Automatic = automatic; RecognizedAtUtc = recognizedAtUtc;
        }

        public AccountData Open()
        {
            return new AccountData(Account, Password, Secret, Base32.Decode(Secret), FirstSeparator, LastSeparator);
        }

        public string CurrentCode(long unixSeconds)
        {
            using (AccountData item = Open()) return item.Code(unixSeconds);
        }

        public string CopyGroup(long unixSeconds)
        {
            return "账号：" + Account + "\r\n密码：" + Password + "\r\n验证码：" + CurrentCode(unixSeconds);
        }

        public string OriginalText()
        {
            return Account + FirstSeparator + Password + LastSeparator + Secret;
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
                for (int i = next.Count - 1; i >= 0; i--)
                {
                    HistoryEntry item = next[i];
                    if (item.Account == data.Account && item.Password == data.Password && item.Secret == data.Secret)
                    { id = item.Id; next.RemoveAt(i); }
                }
                next.Insert(0, new HistoryEntry(id, data.Account, data.Password, data.Secret,
                    data.FirstSeparator, data.LastSeparator, automatic, DateTime.UtcNow));
                if (next.Count > Limit) next.RemoveRange(Limit, next.Count - Limit);
                Write(next); entries = next;
            });
        }

        public void Clear()
        {
            // Deliberately does not read first: explicit clearing can recover an unreadable history file.
            Locked(delegate { List<HistoryEntry> empty = new List<HistoryEntry>(); Write(empty); entries = empty; });
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
                    if (reader.ReadInt32() != Magic || reader.ReadInt32() != 1) throw new InvalidDataException("历史记录版本不兼容。");
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
                        if (ticks < DateTime.MinValue.Ticks || ticks > DateTime.MaxValue.Ticks ||
                            account.Length == 0 || password.Length == 0 || first.Length == 0 || last.Length == 0)
                            throw new InvalidDataException("历史记录字段无效。");
                        byte[] key = Base32.Decode(secret); Array.Clear(key, 0, key.Length);
                        result.Add(new HistoryEntry(id, account, password, secret, first, last, automatic, new DateTime(ticks, DateTimeKind.Utc)));
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
                        writer.Write(Magic); writer.Write(1); writer.Write(next.Count);
                        foreach (HistoryEntry item in next)
                        {
                            writer.Write(item.Id.ToString("N")); writer.Write(item.Account); writer.Write(item.Password); writer.Write(item.Secret);
                            writer.Write(item.FirstSeparator); writer.Write(item.LastSeparator); writer.Write(item.Automatic); writer.Write(item.RecognizedAtUtc.Ticks);
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
