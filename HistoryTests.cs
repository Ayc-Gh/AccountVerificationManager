using System;
using System.IO;
using System.Reflection;
using System.Text;
using System.Security.Cryptography;
using AccountSplit2FA;

internal static class HistoryTests
{
    private static int passed;
    private const string Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static void Check(bool condition, string label)
    {
        if (!condition) throw new Exception("FAIL: " + label);
        passed++;
    }
    private static void Record(HistoryStore store, string account, string password)
    {
        using (AccountData data = AccountParser.Parse(account + "----" + password + "----" + Secret)) store.Record(data, true);
    }
    private static void Main()
    {
        string folder = Path.Combine(Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location), "history-tests-" + Guid.NewGuid().ToString("N"));
        string path = Path.Combine(folder, "history.bin");
        try
        {
            HistoryStore store = new HistoryStore(path); store.Load();
            Check(store.Entries.Count == 0 && !File.Exists(path), "empty startup does not create data");
            for (int i = 0; i < 12; i++) Record(store, "demo" + i + "@example.com", "Synthetic%_Password" + i);
            Check(store.Entries.Count == 10, "retain only ten records");
            Check(store.Entries[0].Account == "demo11@example.com", "newest first");
            Check(store.Entries[9].Account == "demo2@example.com", "evict oldest");
            byte[] encrypted = File.ReadAllBytes(path);
            string raw = Encoding.UTF8.GetString(encrypted);
            Check(!raw.Contains("demo11@example.com") && !raw.Contains("Synthetic%_Password") && !raw.Contains(Secret), "no plaintext credentials on disk");
            byte[] plain = ProtectedData.Unprotect(encrypted, Encoding.UTF8.GetBytes("AccountVerificationManager.History.v1"), DataProtectionScope.CurrentUser);
            try { using (BinaryReader reader = new BinaryReader(new MemoryStream(plain))) Check(reader.ReadInt32() == 0x41564D48, "actual Windows CurrentUser DPAPI encryption"); }
            finally { Array.Clear(plain, 0, plain.Length); }
            HistoryStore reopened = new HistoryStore(path); reopened.Load();
            Check(reopened.Entries.Count == 10 && reopened.Entries[0].Password == "Synthetic%_Password11", "restart recovers exact credentials");
            Guid originalId = reopened.Entries[9].Id;
            Record(reopened, "demo2@example.com", "Synthetic%_Password2");
            Check(reopened.Entries.Count == 10 && reopened.Entries[0].Id == originalId, "duplicate moves to top without consuming a slot");
            Check(reopened.Entries[0].Account == "demo2@example.com", "duplicate order");
            Record(reopened, "demo2@example.com", "ChangedPassword");
            Check(reopened.Entries[0].Password == "ChangedPassword" && reopened.Entries[1].Password == "Synthetic%_Password2", "distinct credentials remain distinct records");
            using (AccountData manual = AccountParser.Parse("manual@example.com|***Keep|Symbols***|" + Secret, "|")) reopened.Record(manual, false);
            HistoryEntry entry = reopened.Entries[0];
            Check(!entry.Automatic && entry.FirstSeparator == "|", "manual mode saved");
            using (AccountData restored = entry.Open()) Check(restored.Password == "***Keep|Symbols***", "history restore bypasses ambiguous re-parsing");
            using (AccountData restored = AccountParser.Parse(entry.OriginalText(), entry.FirstSeparator)) Check(restored.Password == entry.Password, "manual input can be restored");
            Check(entry.CurrentCode(59) == "287082" && entry.CurrentCode(60) == "359152", "history generates fresh code at interval rollover");
            string group = entry.CopyGroup(59);
            Check(group == "账号：manual@example.com\r\n密码：***Keep|Symbols***\r\n验证码：287082", "group copies account password current code");
            Check(!group.Contains(Secret) && entry.CopyGroup(60).EndsWith("359152"), "group omits secret and does not cache OTP");
            // Both instances write after the other has changed the file; neither drops the other's new record.
            HistoryStore stale = new HistoryStore(path); stale.Load();
            Record(reopened, "window-a@example.com", "WindowA"); Record(stale, "window-b@example.com", "WindowB");
            reopened.Load();
            Check(reopened.Entries[0].Account == "window-b@example.com" && reopened.Entries[1].Account == "window-a@example.com", "multi-window merge under lock");
            using (FileStream locked = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.None))
            {
                bool failed = false;
                try { Record(reopened, "cannot-save@example.com", "NoSave"); } catch (IOException) { failed = true; }
                Check(failed && reopened.Entries[0].Account == "window-b@example.com", "failed save does not report or display a stored record");
            }
            Check(Directory.GetFiles(folder, "*.tmp.*").Length == 0, "no temporary history files left");
            byte[] corrupt = { 1, 2, 3, 4, 5 }; File.WriteAllBytes(path, corrupt);
            bool corruptRead = false, corruptWrite = false;
            try { new HistoryStore(path).Load(); } catch (CryptographicException) { corruptRead = true; }
            try { Record(reopened, "cannot-overwrite@example.com", "NoOverwrite"); } catch (CryptographicException) { corruptWrite = true; }
            Check(corruptRead && corruptWrite && File.ReadAllBytes(path).Length == corrupt.Length, "unreadable history remains untouched");
            reopened.Clear(); HistoryStore cleared = new HistoryStore(path); cleared.Load();
            Check(cleared.Entries.Count == 0, "clear persists across restart and recovers corrupt file");
            Console.WriteLine("PASS: " + passed + " history checks; encrypted persistence, capacity, deduplication, restore, live OTP, multi-window updates, failure handling.");
        }
        finally
        {
            if (Directory.Exists(folder))
            {
                foreach (string file in Directory.GetFiles(folder)) File.Delete(file);
                Directory.Delete(folder);
            }
        }
    }
}
