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
    private static void SmartRecord(HistoryStore store, string text)
    {
        using (AccountData data = SmartParser.Parse(text)) store.Record(data, true);
    }
    private static void LegacyFile(string path, Guid id, int version = 1)
    {
        byte[] plain;
        using (MemoryStream stream = new MemoryStream())
        {
            using (BinaryWriter writer = new BinaryWriter(stream, Encoding.UTF8, true))
            {
                writer.Write(0x41564D48); writer.Write(version); writer.Write(1);
                writer.Write(id.ToString("N")); writer.Write("legacy@example.com"); writer.Write("Legacy%Password"); writer.Write(Secret);
                writer.Write("----"); writer.Write("----"); writer.Write(true); writer.Write(DateTime.UtcNow.Ticks);
                if (version >= 2) {
                    writer.Write((byte)InputKind.Record); writer.Write(""); writer.Write("SHA1"); writer.Write(6); writer.Write(30);
                    writer.Write((byte)OtpKind.Totp); writer.Write((ulong)0); writer.Write(false);
                }
                if (version >= 3) writer.Write("旧版名称");
            }
            plain = stream.ToArray();
        }
        try { File.WriteAllBytes(path, ProtectedData.Protect(plain, Encoding.UTF8.GetBytes("AccountVerificationManager.History.v1"), DataProtectionScope.CurrentUser)); }
        finally { Array.Clear(plain, 0, plain.Length); }
    }
    private static void TransferChecks(string folder)
    {
        HistoryStore source = new HistoryStore(Path.Combine(folder, "export.bin"));
        HistoryEntry entry;
        using (AccountData value = AccountParser.Parse("测试+tag@example.com|  Demo|Password  |otpauth://totp/Issuer:ignored?secret=JBSWY3DPEHPK3PXP&issuer=Issuer&algorithm=SHA256&digits=8&period=60", "|")) entry = source.Record(value, false, new DateTime(2100, 1, 1, 0, 0, 0, DateTimeKind.Utc));
        source.Rename(entry.Id, "工作邮箱"); source.SetPinned(entry.Id, true);
        byte[] file = HistoryTransfer.Encrypt(source.Entries, "测试密码 🔑 123");
        System.Collections.Generic.List<HistoryEntry> decoded = HistoryTransfer.Decrypt(file, "测试密码 🔑 123");
        HistoryEntry actual = decoded[0], expected = source.Entries[0];
        Check(actual.Account == expected.Account && actual.Password == expected.Password && actual.Secret == expected.Secret && actual.Algorithm == "SHA256" && actual.Digits == 8 && actual.Period == 60, "encrypted transfer preserves exact credentials and OTP parameters");
        Check(actual.Name == expected.Name && actual.Pinned && actual.ExpiresAtUtc == expected.ExpiresAtUtc && !actual.Automatic && actual.FirstSeparator == "|", "encrypted transfer preserves history settings");
        Check(!Encoding.UTF8.GetString(file).Contains(expected.Secret), "export file does not contain plaintext key");
        Check(Convert.ToBase64String(file) != Convert.ToBase64String(HistoryTransfer.Encrypt(source.Entries, "测试密码 🔑 123")), "repeated exports use fresh salt and IV");
        bool failed = false; try { HistoryTransfer.Decrypt(file, "wrong-password"); } catch (FormatException) { failed = true; } Check(failed, "wrong file password rejected");
        foreach (int offset in new int[]{8, 24, 40, file.Length - 1}) {
            byte[] bad = (byte[])file.Clone(); bad[offset] ^= 1; failed = false;
            try { HistoryTransfer.Decrypt(bad, "测试密码 🔑 123"); } catch (FormatException) { failed = true; }
            Check(failed, "tampered export rejected before decode at " + offset);
        }
        HistoryStore target = new HistoryStore(Path.Combine(folder, "import.bin"));
        Check(target.Import(decoded) == 1 && target.Entries[0].Name == "工作邮箱", "selected encrypted entries import into independent store");
        target.Rename(target.Entries[0].Id, "本机名称");
        Check(target.Import(decoded) == 0 && target.Entries[0].Name == "本机名称", "duplicate import preserves existing metadata");
        for (int i = 0; i < 9; i++) Record(target, "capacity" + i, "Demo");
        HistoryStore another = new HistoryStore(Path.Combine(folder, "another.bin")); Record(another, "overflow", "Demo");
        byte[] before = File.ReadAllBytes(target.FilePath); failed = false;
        try { target.Import(another.Entries); } catch (InvalidOperationException) { failed = true; }
        Check(failed && Convert.ToBase64String(before) == Convert.ToBase64String(File.ReadAllBytes(target.FilePath)), "overflow import is atomic and preserves existing history");
    }
    private static void ManagementChecks(string folder)
    {
        DateTime now = new DateTime(2030, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        string path = Path.Combine(folder, "management.bin");
        HistoryStore store = new HistoryStore(path, delegate { return now; });
        HistoryEntry expiring;
        using (AccountData data = AccountParser.Parse("expire@example.com----Demo----" + Secret)) expiring = store.Record(data, true, now.AddMinutes(5));
        store.SetPinned(expiring.Id, true); store.Rename(expiring.Id, "保留名称");
        Record(store, "permanent@example.com", "Demo");
        Check(store.Entries[0].Id == expiring.Id && store.Entries.Count == 2, "pinned entry remains first when a new record is saved");
        using (AccountData data = AccountParser.Parse("expire@example.com----Demo----" + Secret)) {
            HistoryEntry repeated = store.Record(data, true, now.AddDays(2));
            Check(repeated.Id == expiring.Id && repeated.Pinned && repeated.ExpiresAtUtc == expiring.ExpiresAtUtc && repeated.Name == "保留名称", "duplicate preserves expiry pin identity and name");
        }
        HistoryStore concurrent = new HistoryStore(path, delegate { return now; });
        concurrent.SetExpiration(expiring.Id, now.AddMinutes(10)); store.SetPinned(expiring.Id, false);
        Check(store.Find(expiring.Id).ExpiresAtUtc == now.AddMinutes(10), "pin update preserves concurrent expiry change");
        concurrent.SetPinned(expiring.Id, true); store.SetExpiration(expiring.Id, now.AddMinutes(15));
        Check(store.Find(expiring.Id).Pinned, "expiry update preserves concurrent pin change");
        HistoryEntry changed = store.ReplaceOtp(store.Find(expiring.Id), "JBSWY3DPEHPK3PXP");
        Check(changed.Pinned && changed.ExpiresAtUtc == now.AddMinutes(15) && changed.Name == "保留名称", "OTP replacement preserves management metadata");
        byte[] before = File.ReadAllBytes(path); bool rejected = false;
        try { store.SetExpiration(expiring.Id, now); } catch (FormatException) { rejected = true; }
        Check(rejected && Convert.ToBase64String(before) == Convert.ToBase64String(File.ReadAllBytes(path)), "past expiry rejected without modifying history");
        now = now.AddMinutes(15); store.Load();
        Check(store.Find(expiring.Id) == null && store.Entries.Count == 1, "pinned record removed at exact expiry boundary");
        concurrent.Load(); Check(concurrent.Entries.Count == 1, "expiry deletion survives encrypted reload");
        rejected = false; try { store.ReplaceOtp(changed, Secret); } catch (InvalidOperationException) { rejected = true; }
        Check(rejected, "expired replacement draft cannot resurrect record");
        rejected = false; try { store.SetExpiration(changed.Id, now.AddDays(1)); } catch (InvalidOperationException) { rejected = true; }
        Check(rejected, "expired record cannot be renewed through stale management dialog");
        Guid permanent = store.Entries[0].Id; store.SetExpiration(permanent, now.AddDays(1)); store.SetExpiration(permanent, null);
        Check(store.Find(permanent).ExpiresAtUtc == null, "expiry can be cleared to retain permanently");
        store.Delete(permanent); concurrent.Load(); Check(concurrent.Entries.Count == 0, "single deletion persists across windows");
        for (int i = 0; i < 10; i++) {
            now = now.AddSeconds(1);
            using (AccountData data = AccountParser.Parse("pin" + i + "----Demo----" + Secret)) { HistoryEntry saved = store.Record(data, true); store.SetPinned(saved.Id, true); }
        }
        Check(store.Entries.Count == 10, "ten pinned records fit the existing capacity");
        before = File.ReadAllBytes(path); rejected = false;
        try { Record(store, "extra", "Demo"); } catch (InvalidOperationException) { rejected = true; }
        Check(rejected && Convert.ToBase64String(before) == Convert.ToBase64String(File.ReadAllBytes(path)), "all pinned capacity refuses new entry without silent eviction");
        Guid victim = store.Entries[9].Id; store.SetPinned(victim, false); Record(store, "extra", "Demo");
        Check(store.Find(victim) == null && store.Entries.Count == 10, "capacity evicts unpinned entry before pinned entries");
        Guid retained = store.Entries[0].Id; before = new byte[] { 1, 2, 3, 4 }; File.WriteAllBytes(path, before);
        rejected = false; try { store.Delete(retained); } catch (CryptographicException) { rejected = true; }
        Check(rejected && File.ReadAllBytes(path).Length == 4, "single deletion cannot overwrite corrupt history");
        rejected = false; try { store.Load(); } catch (CryptographicException) { rejected = true; }
        Check(rejected && File.ReadAllBytes(path).Length == 4, "automatic cleanup cannot overwrite corrupt history");
        string v3Path = Path.Combine(folder, "legacy-v3.bin"); Guid legacyId = Guid.NewGuid(); LegacyFile(v3Path, legacyId, 3);
        HistoryStore legacy = new HistoryStore(v3Path); legacy.Load(); HistoryEntry legacyEntry = legacy.Find(legacyId);
        Check(legacyEntry.Name == "旧版名称" && legacyEntry.ExpiresAtUtc == null && !legacyEntry.Pinned, "v3 migration defaults to permanent unpinned and preserves name");
        legacy.SetPinned(legacyId, true); legacy.Load(); Check(legacy.Find(legacyId).Pinned && legacy.Find(legacyId).Name == "旧版名称", "v3 upgrade persists metadata and name in v4");
    }
    private static void ReplacementChecks(string folder)
    {
        const string nextSecret = "JBSWY3DPEHPK3PXP";
        string replacePath = Path.Combine(folder, "replace.bin"); HistoryStore store = new HistoryStore(replacePath);
        using (AccountData data = AccountParser.Parse("keep@example.com| *Pass|word* |" + Secret, "|")) store.Record(data, false);
        HistoryEntry original = store.Rename(store.Entries[0].Id, "工作邮箱");
        Record(store, "other@example.com", "OtherPassword");
        HistoryEntry changed = store.ReplaceOtp(original, nextSecret); store.Load();
        Check(store.Entries.Count == 2 && store.Entries[1].Id == original.Id && changed.RecognizedAtUtc == original.RecognizedAtUtc, "replace preserves identity timestamp position and capacity");
        Check(changed.Account == original.Account && changed.Password == original.Password && changed.Name == original.Name && !changed.Automatic && changed.FirstSeparator == "|", "replace preserves exact credentials name and separator mode");
        using (AccountData expected = SmartParser.Parse(nextSecret)) Check(changed.CurrentCode(59) == expected.Code(59) && changed.Secret == nextSecret, "replace persists new secret and code");
        byte[] before = File.ReadAllBytes(replacePath); bool failed = false;
        try { store.ReplaceOtp(changed, "not-a-valid-key"); } catch (FormatException) { failed = true; }
        Check(failed && Convert.ToBase64String(before) == Convert.ToBase64String(File.ReadAllBytes(replacePath)), "invalid replacement never changes encrypted history");
        failed = false; try { store.ReplaceOtp(original, Secret); } catch (InvalidOperationException) { failed = true; }
        Check(failed && store.Find(original.Id).Secret == nextSecret, "stale replacement cannot overwrite newer OTP");
        HistoryStore other = new HistoryStore(replacePath); other.Rename(changed.Id, "另一个窗口改名");
        string uri = "otpauth://totp/New:ignored@example.com?secret=" + Secret + "&issuer=New&algorithm=SHA256&digits=8&period=60";
        changed = store.ReplaceOtp(changed, uri);
        Check(changed.Name == "另一个窗口改名" && changed.Account == original.Account && changed.Password == original.Password, "replacement keeps concurrent name change and ignores incoming URI account");
        Check(changed.Algorithm == "SHA256" && changed.Digits == 8 && changed.Period == 60 && changed.Issuer == "New" && changed.UsesUri, "URI replacement retains full new OTP parameters");
        using (AccountData roundTrip = SmartParser.Parse(changed.OriginalText(), "|")) Check(roundTrip.Account == original.Account && roundTrip.Password == original.Password && roundTrip.Code(59) == changed.CurrentCode(59), "changed record round-trips with custom parameters");
        changed = store.ReplaceOtp(changed, nextSecret);
        Check(changed.Algorithm == "SHA256" && changed.Digits == 8 && changed.Period == 60, "bare replacement retains existing TOTP parameters");
        using (AccountData data = changed.Open()) store.Record(data, false);
        Check(store.Entries.Count == 2 && store.Entries[0].Name == changed.Name, "re-recognition after replacement retains name without duplicate");
        using (AccountData data = AccountParser.Parse(changed.Account + "|" + changed.Password + "|" + Secret, "|")) store.Record(data, false);
        HistoryEntry duplicateTarget = store.Entries[0];
        // Existing replacement has custom parameters; test collision with an explicit matching URI.
        failed = false; try { store.ReplaceOtp(changed, "otpauth://totp/ignored?secret=" + Secret); } catch (InvalidOperationException) { failed = true; }
        Check(failed && store.Find(duplicateTarget.Id).Secret == Secret, "replacement rejects collision with another record");
        SmartRecord(store, "otpauth://hotp/hotp@example.com?secret=" + Secret + "&counter=1");
        HistoryEntry hotp = store.Entries[0]; store.AdvanceCounter(hotp.Id, 1); failed = false;
        try { store.ReplaceOtp(hotp, "otpauth://hotp/new?secret=" + nextSecret + "&counter=0"); } catch (InvalidOperationException) { failed = true; }
        Check(failed && store.Find(hotp.Id).Counter == 2, "replacement rejects stale HOTP counter");
        hotp = store.Find(hotp.Id); failed = false; try { store.ReplaceOtp(hotp, nextSecret); } catch (FormatException) { failed = true; }
        Check(failed, "bare HOTP replacement requires explicit new counter URI");
        HistoryEntry advanced = store.ReplaceOtp(hotp, "otpauth://hotp/new?secret=" + nextSecret + "&counter=0");
        Check(advanced.Counter == 0 && advanced.Account == "hotp@example.com", "HOTP URI replacement uses new counter and keeps current account");
        store.Clear(); failed = false; try { store.ReplaceOtp(changed, Secret); } catch (InvalidOperationException) { failed = true; }
        Check(failed && store.Entries.Count == 0, "removed record cannot be silently recreated by replacement");
        File.WriteAllBytes(replacePath, new byte[] { 1, 2, 3 }); failed = false;
        try { store.ReplaceOtp(changed, Secret); } catch (CryptographicException) { failed = true; }
        Check(failed && File.ReadAllBytes(replacePath).Length == 3, "replacement preserves corrupt history");
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
            SmartRecord(reopened, Secret);
            HistoryEntry pure = reopened.Entries[0]; reopened.Load(); pure = reopened.Find(pure.Id);
            Check(pure.InputKind == InputKind.Secret && pure.Account == "" && pure.Password == "", "pure key persists without invented credentials");
            Check(pure.OriginalText() == Secret && pure.CopyGroup(59) == "验证码：287082", "pure key restore and group copying");
            string rich = "otpauth://totp/Service:uri@example.com?secret=" + Secret + "&issuer=Service&algorithm=SHA512&digits=8&period=60";
            SmartRecord(reopened, rich); HistoryEntry uri = reopened.Entries[0]; reopened.Load(); uri = reopened.Find(uri.Id);
            Check(uri.InputKind == InputKind.AuthenticatorUri && uri.Algorithm == "SHA512" && uri.Digits == 8 && uri.Period == 60 && uri.Issuer == "Service", "URI settings persist across restart");
            using (AccountData restored = SmartParser.Parse(uri.OriginalText())) Check(restored.Code(59) == uri.CurrentCode(59) && restored.Password == "", "URI history reconstructs parameterized URI");
            SmartRecord(reopened, rich.Replace("period=60", "period=30"));
            Check(reopened.Entries[0].Period == 30 && reopened.Find(uri.Id).Period == 60, "different OTP settings do not collapse as duplicates");
            SmartRecord(reopened, "otpauth://hotp/hotp@example.com?secret=" + Secret + "&counter=1");
            HistoryEntry counterEntry = reopened.Entries[0]; int countBefore = reopened.Entries.Count;
            reopened.Rename(counterEntry.Id, "计数器账号");
            HistoryEntry advanced = reopened.AdvanceCounter(counterEntry.Id, 1);
            Check(advanced.Counter == 2 && advanced.CurrentCode(0) == "359152", "explicit HOTP advance generates next code");
            Check(advanced.Name == "计数器账号", "HOTP advance preserves custom name");
            Check(reopened.Find(counterEntry.Id).Counter == 2 && reopened.Entries.Count == countBefore, "HOTP counter persists without creating another record");
            bool staleRejected = false;
            try { reopened.AdvanceCounter(counterEntry.Id, 1); } catch (InvalidOperationException) { staleRejected = true; }
            Check(staleRejected && reopened.Find(counterEntry.Id).Counter == 2, "stale window cannot overwrite a newer counter");
            advanced.CopyGroup(10); advanced.CurrentCode(99999);
            Check(reopened.Find(counterEntry.Id).Counter == 2, "copying never consumes HOTP counter");
            SmartRecord(reopened, "otpauth://hotp/max@example.com?secret=" + Secret + "&counter=18446744073709551615");
            bool overflowRejected = false;
            try { reopened.AdvanceCounter(reopened.Entries[0].Id, UInt64.MaxValue); } catch (InvalidOperationException) { overflowRejected = true; }
            Check(overflowRejected && reopened.Entries[0].Counter == UInt64.MaxValue, "counter overflow leaves history intact");
            string legacyPath = Path.Combine(folder, "legacy.bin"); Guid legacyId = Guid.NewGuid(); LegacyFile(legacyPath, legacyId);
            HistoryStore legacy = new HistoryStore(legacyPath); legacy.Load();
            Check(legacy.Entries[0].Id == legacyId && legacy.Entries[0].CurrentCode(59) == "287082" && legacy.Entries[0].Period == 30, "read version 1 history with original defaults");
            SmartRecord(legacy, Secret); legacy.Load();
            Check(legacy.Entries.Count == 2 && legacy.Entries[1].Id == legacyId && legacy.Entries[1].Password == "Legacy%Password", "version 4 write preserves legacy records and IDs");
            byte[] migrated = ProtectedData.Unprotect(File.ReadAllBytes(legacyPath), Encoding.UTF8.GetBytes("AccountVerificationManager.History.v1"), DataProtectionScope.CurrentUser);
            try { using (BinaryReader reader = new BinaryReader(new MemoryStream(migrated))) { reader.ReadInt32(); Check(reader.ReadInt32() == 4, "migration remains encrypted and uses version 4"); } }
            finally { Array.Clear(migrated, 0, migrated.Length); }
            string v2Path = Path.Combine(folder, "legacy-v2.bin"); Guid v2Id = Guid.NewGuid(); LegacyFile(v2Path, v2Id, 2);
            HistoryStore v2 = new HistoryStore(v2Path); v2.Load();
            Check(v2.Entries[0].Id == v2Id && v2.Entries[0].Name == "", "read version 2 with empty default name");
            v2.Rename(v2Id, "旧版账号"); v2.Load();
            Check(v2.Entries[0].Name == "旧版账号" && v2.Entries[0].Password == "Legacy%Password" && v2.Entries[0].CurrentCode(59) == "287082", "version 2 naming migrates without changing credentials");

            HistoryEntry beforeName = reopened.Find(uri.Id); string copyBeforeName = beforeName.CopyGroup(59);
            int originalIndex = -1; for (int i = 0; i < reopened.Entries.Count; i++) if (reopened.Entries[i].Id == uri.Id) originalIndex = i;
            HistoryEntry named = reopened.Rename(uri.Id, "  工作邮箱 🔐  ");
            Check(named.Name == "工作邮箱 🔐" && named.DisplayName == named.Name, "custom name trims whitespace and becomes display title");
            Check(named.Id == beforeName.Id && named.RecognizedAtUtc == beforeName.RecognizedAtUtc && reopened.Entries[originalIndex].Id == uri.Id, "naming preserves identity timestamp and list order");
            Check(named.CopyGroup(59) == copyBeforeName && named.OriginalText() == beforeName.OriginalText(), "naming leaves copied credentials and OTP input unchanged");
            HistoryStore afterName = new HistoryStore(path); afterName.Load();
            Check(afterName.Find(uri.Id).Name == "工作邮箱 🔐", "name survives encrypted restart");
            Check(!Encoding.UTF8.GetString(File.ReadAllBytes(path)).Contains("工作邮箱"), "name is not plaintext on disk");
            SmartRecord(afterName, rich); afterName.Load();
            Check(afterName.Entries[0].Id == uri.Id && afterName.Entries[0].Name == "工作邮箱 🔐", "duplicate recognition keeps custom name");
            HistoryStore oldWindow = new HistoryStore(path); oldWindow.Load();
            afterName.Rename(uri.Id, "更新的名称"); Record(oldWindow, "another-window@example.com", "SyntheticPassword");
            afterName.Load(); Check(afterName.Find(uri.Id).Name == "更新的名称", "stale window does not overwrite another window's rename");
            HistoryEntry resetName = afterName.Rename(uri.Id, "   ");
            Check(resetName.Name == "" && resetName.DisplayName == resetName.DefaultDisplayName, "empty name restores default display");
            afterName.Rename(uri.Id, new string('名', 40));
            Check(afterName.Find(uri.Id).Name.Length == 40, "forty character name accepted");
            byte[] validBeforeInvalidName = File.ReadAllBytes(path);
            foreach (string invalidName in new string[] { new string('名', 41), "bad\nname", "bad\0name" }) {
                bool rejected = false; try { afterName.Rename(uri.Id, invalidName); } catch (FormatException) { rejected = true; }
                Check(rejected, "invalid name rejected");
            }
            bool missingRejected = false; try { afterName.Rename(Guid.NewGuid(), "missing"); } catch (InvalidOperationException) { missingRejected = true; }
            Check(missingRejected && Convert.ToBase64String(validBeforeInvalidName) == Convert.ToBase64String(File.ReadAllBytes(path)), "invalid or removed record rename cannot modify history");
            byte[] corrupt = { 1, 2, 3, 4, 5 }; File.WriteAllBytes(path, corrupt);
            bool corruptRead = false, corruptWrite = false;
            try { new HistoryStore(path).Load(); } catch (CryptographicException) { corruptRead = true; }
            try { Record(reopened, "cannot-overwrite@example.com", "NoOverwrite"); } catch (CryptographicException) { corruptWrite = true; }
            Check(corruptRead && corruptWrite && File.ReadAllBytes(path).Length == corrupt.Length, "unreadable history remains untouched");
            bool renameCorrupt = false; try { reopened.Rename(uri.Id, "NoOverwrite"); } catch (CryptographicException) { renameCorrupt = true; }
            Check(renameCorrupt && File.ReadAllBytes(path).Length == corrupt.Length, "rename does not overwrite unreadable history");
            reopened.Clear(); HistoryStore cleared = new HistoryStore(path); cleared.Load();
            Check(cleared.Entries.Count == 0, "clear persists across restart and recovers corrupt file");
            ReplacementChecks(folder);
            ManagementChecks(folder);
            TransferChecks(folder);
            DateTime durationNow = new DateTime(2030, 1, 1, 0, 0, 0, DateTimeKind.Utc);
            HistoryStore durationStore = new HistoryStore(Path.Combine(folder, "retention.bin"), () => durationNow);
            RetentionPeriod duration = RetentionPeriod.Parse("2", 0); durationNow = durationNow.AddHours(3);
            using (AccountData value = AccountParser.Parse("duration@example.com----Synthetic----" + Secret)) {
                HistoryEntry first = durationStore.RecordWithRetention(value, true, duration);
                Check(first.ExpiresAtUtc == durationNow.AddMinutes(2) && first.RecognizedAtUtc == durationNow, "retention starts at actual record time, not settings time");
                durationStore.SetPinned(first.Id, true); durationNow = durationNow.AddSeconds(30);
                HistoryEntry duplicate = durationStore.RecordWithRetention(value, true, RetentionPeriod.Parse("9", 4));
                Check(duplicate.Id == first.Id && duplicate.ExpiresAtUtc == first.ExpiresAtUtc && duplicate.Pinned, "duplicate ignores new duration and preserves pinned expiry");
                durationNow = first.ExpiresAtUtc.Value.AddTicks(-1); durationStore.Load(); Check(durationStore.Entries.Count == 1, "retention entry remains one tick before expiry");
                durationNow = first.ExpiresAtUtc.Value; durationStore.Load(); Check(durationStore.Entries.Count == 0, "retention entry removed at exact expiry even when pinned");
                HistoryStore reloadedDuration = new HistoryStore(Path.Combine(folder, "retention.bin"), () => durationNow); reloadedDuration.Load();
                Check(reloadedDuration.Entries.Count == 0, "retention deletion persists across restart");
                Check(!durationStore.RecordWithRetention(value, true, null).ExpiresAtUtc.HasValue, "no retention records permanently");
            }
            Console.WriteLine("PASS: " + passed + " history checks; encrypted persistence, capacity, deduplication, OTP replacement, live OTP, multi-window updates, failure handling.");
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
