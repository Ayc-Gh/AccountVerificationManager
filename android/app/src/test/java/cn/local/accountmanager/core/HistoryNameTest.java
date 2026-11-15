package cn.local.accountmanager.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HistoryNameTest {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String URI = "otpauth://totp/Example:label%40example.com?secret=" + SECRET + "&issuer=Example&algorithm=SHA256&digits=8&period=60";
    private List<HistoryEntry> records() {
        List<HistoryEntry> result = new ArrayList<>();
        try (AccountData data = SmartParser.parse(URI, null)) { result = HistoryModel.record(result, data, true, 12); }
        try (AccountData data = SmartParser.parse(SECRET, null)) { result = HistoryModel.record(result, data, true, 13); }
        return result;
    }
    @Test public void nameRoundTripKeepsCredentialsAndOrder() throws Exception {
        List<HistoryEntry> old = records(); HistoryEntry original = old.get(1);
        List<HistoryEntry> named = HistoryModel.rename(old, original.id, "  工作邮箱 🔐  ");
        HistoryEntry entry = HistoryCodec.decode(HistoryCodec.encode(named)).get(1);
        assertEquals("工作邮箱 🔐", entry.name); assertEquals(entry.name, entry.displayName());
        assertEquals(original.id, entry.id); assertEquals(original.recognizedAt, entry.recognizedAt);
        assertEquals(original.account, entry.account); assertEquals(original.password, entry.password);
        assertEquals(original.otp, entry.otp); assertEquals(original.originalText(), entry.originalText());
        assertEquals(original.copyGroup(1234567890), entry.copyGroup(1234567890));
        assertEquals("", old.get(1).name); assertEquals(old.get(0).id, named.get(0).id);
    }
    @Test public void repeatedRecognitionRetainsNameAndSameId() {
        List<HistoryEntry> old = records(); HistoryEntry first = old.get(1);
        old = HistoryModel.rename(old, first.id, "办公");
        try (AccountData data = SmartParser.parse(URI, null)) { old = HistoryModel.record(old, data, true, 15); }
        assertEquals(2, old.size()); assertEquals(first.id, old.get(0).id); assertEquals("办公", old.get(0).name);
        assertEquals(15, old.get(0).recognizedAt);
        assertEquals("label@example.com", old.get(0).account);
    }
    @Test public void emptyNameRestoresDefaultAndValidationDoesNotChangeList() {
        List<HistoryEntry> old = records(); String id = old.get(0).id;
        List<HistoryEntry> named = HistoryModel.rename(old, id, "密钥 " + "甲".repeat(37));
        assertEquals(40, named.get(0).name.length());
        HistoryEntry cleared = HistoryModel.rename(named, id, " \u3000 ").get(0);
        assertEquals("", cleared.name); assertEquals(cleared.defaultDisplayName(), cleared.displayName());
        for (String invalid : new String[]{"甲".repeat(41), "a\nb", "a\0b"})
            assertThrows(IllegalArgumentException.class, () -> HistoryModel.rename(old, id, invalid));
        assertThrows(IllegalArgumentException.class, () -> HistoryModel.rename(old, UUID.randomUUID().toString(), "missing"));
        assertEquals("", old.get(0).name);
    }
    @Test public void legacyV1AndV2MigrateToV3WithEmptyNames() throws Exception {
        for (int version : new int[]{1, 2}) {
            List<HistoryEntry> old = HistoryCodec.decode(legacy(version));
            HistoryEntry original = old.get(0); assertEquals("", original.name);
            List<HistoryEntry> named = HistoryModel.rename(old, original.id, "旧版记录");
            byte[] encoded = HistoryCodec.encode(named);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) { in.readInt(); assertEquals(3, in.readInt()); }
            HistoryEntry entry = HistoryCodec.decode(encoded).get(0);
            assertEquals("旧版记录", entry.name); assertEquals(original.id, entry.id);
            assertEquals(original.originalText(), entry.originalText()); assertEquals(original.currentCode(59), entry.currentCode(59));
            if (version == 2) { assertEquals("SHA256", entry.otp.algorithm); assertEquals(8, entry.otp.digits); assertEquals(60, entry.otp.period); }
        }
    }
    @Test public void changedCredentialsDoNotInheritOtherRecordsName() {
        List<HistoryEntry> old = records(); old = HistoryModel.rename(old, old.get(1).id, "办公");
        try (AccountData data = SmartParser.parse(URI.replace("period=60", "period=30"), null)) { old = HistoryModel.record(old, data, true, 20); }
        assertEquals("", old.get(0).name); assertEquals("办公", old.get(2).name);
    }
    private static byte[] legacy(int version) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x41564D48); out.writeInt(version); out.writeInt(1);
            String[] fields = {"6ef4828d-6abf-4abe-809e-7ff9f7a1e28a", "legacy@example.com", version == 1 ? "LegacyPassword" : "", SECRET,
                    version == 1 ? "----" : "", version == 1 ? "----" : ""};
            for (String value : fields) write(out, value);
            out.writeBoolean(true); out.writeLong(1234);
            if (version == 2) {
                write(out, "URI"); write(out, "Legacy"); write(out, "SHA256"); out.writeInt(8); out.writeInt(60);
            }
        }
        return bytes.toByteArray();
    }
    private static void write(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
}
