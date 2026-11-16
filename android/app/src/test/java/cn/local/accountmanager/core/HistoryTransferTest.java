package cn.local.accountmanager.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.io.IOException;

public class HistoryTransferTest {
    private List<HistoryEntry> fixture() {
        try (AccountData data = AccountParser.parse("测试+tag@example.com|  Demo|Password  |otpauth://totp/Issuer:ignored?secret=JBSWY3DPEHPK3PXP&issuer=Issuer&algorithm=SHA256&digits=8&period=60", "|")) {
            return Collections.singletonList(HistoryEntry.fromData(UUID.randomUUID().toString(), data, false, 123456789).withName("工作邮箱").withManagement(4102444800000L, true));
        }
    }
    @Test public void encryptedRoundtripPreservesEveryFieldAndHidesCredentials() throws Exception {
        List<HistoryEntry> original = fixture(); byte[] file = HistoryTransfer.encrypt(original, "测试密码 🔑 123");
        assertFalse(new String(file, java.nio.charset.StandardCharsets.ISO_8859_1).contains("JBSWY3DPEHPK3PXP"));
        HistoryEntry actual = HistoryTransfer.decrypt(file, "测试密码 🔑 123").get(0), expected = original.get(0);
        assertEquals(expected.account, actual.account); assertEquals(expected.password, actual.password); assertEquals(expected.secret, actual.secret);
        assertEquals(expected.otp, actual.otp); assertEquals(expected.name, actual.name); assertEquals(expected.expiresAt, actual.expiresAt); assertTrue(actual.pinned); assertFalse(actual.automatic);
        assertEquals(expected.originalText(), actual.originalText()); assertEquals(expected.recognizedAt, actual.recognizedAt);
        assertFalse(Arrays.equals(file, HistoryTransfer.encrypt(original, "测试密码 🔑 123")));
    }
    @Test public void wrongPasswordTamperedHeaderAndCiphertextAreRejected() throws Exception {
        byte[] file = HistoryTransfer.encrypt(fixture(), "test-password");
        assertThrows(IOException.class, () -> HistoryTransfer.decrypt(file, "wrong-password"));
        for (int offset : new int[]{8, 24, 40, file.length - 1}) {
            byte[] bad = file.clone(); bad[offset] ^= 1;
            assertThrows(IOException.class, () -> HistoryTransfer.decrypt(bad, "test-password"));
        }
        assertThrows(IOException.class, () -> HistoryTransfer.decrypt(new byte[HistoryTransfer.MAX_BYTES + 1], "test-password"));
    }
    @Test public void importIsAtomicOnOverflowAndKeepsExistingMetadata() {
        List<HistoryEntry> old = new ArrayList<>();
        for (int i = 0; i < 10; i++) try (AccountData data = SmartParser.parse("otpauth://totp/test" + i + "?secret=JBSWY3DPEHPK3PXP", null)) { old = HistoryModel.record(old, data, true, 10 + i); }
        final List<HistoryEntry> full = old;
        assertThrows(IllegalArgumentException.class, () -> HistoryModel.importEntries(full, fixture(), 100));
        assertEquals(10, old.size());
        List<HistoryEntry> fixture = fixture(); HistoryEntry existing = fixture.get(0).withName("保留本机名称").withManagement(0, false);
        List<HistoryEntry> merged = HistoryModel.importEntries(Collections.singletonList(existing), fixture, 100);
        assertEquals(1, merged.size()); assertEquals(existing.id, merged.get(0).id); assertEquals("保留本机名称", merged.get(0).name); assertFalse(merged.get(0).pinned);
        assertTrue(HistoryModel.importEntries(new ArrayList<>(), fixture, 4102444800000L).isEmpty());
    }
}
