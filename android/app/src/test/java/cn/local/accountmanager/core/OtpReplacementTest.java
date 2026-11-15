package cn.local.accountmanager.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public final class OtpReplacementTest {
    private static final String OLD = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", NEXT = "JBSWY3DPEHPK3PXP";
    private static final String URI = "otpauth://totp/New:ignored@example.com?secret=" + NEXT + "&issuer=New&algorithm=SHA256&digits=8&period=60";
    private HistoryEntry record() {
        try (AccountData data = SmartParser.parse("keep@example.com| *Pass|word* |" + OLD, "|")) {
            return HistoryEntry.fromData(UUID.randomUUID().toString(), data, false, 1234).withName("工作邮箱");
        }
    }
    @Test public void preservesIdentityCredentialsPositionNameAndTime() {
        HistoryEntry original = record(), other = record();
        List<HistoryEntry> old = Arrays.asList(other, original);
        List<HistoryEntry> next = HistoryModel.replaceOtp(old, original, NEXT);
        HistoryEntry changed = next.get(1);
        assertEquals(2, next.size()); assertEquals(original.id, changed.id); assertEquals(other.id, next.get(0).id);
        assertEquals(original.account, changed.account); assertEquals(original.password, changed.password); assertEquals(original.name, changed.name);
        assertEquals(original.recognizedAt, changed.recognizedAt); assertFalse(changed.automatic); assertEquals("|", changed.firstSeparator);
        assertEquals(OLD, old.get(1).secret); assertEquals(NEXT, changed.secret);
    }
    @Test public void appliesUriParametersWithoutReplacingCurrentAccount() throws Exception {
        HistoryEntry original = record();
        HistoryEntry changed = HistoryModel.replaceOtp(Collections.singletonList(original), original, URI).get(0);
        assertEquals(original.account, changed.account); assertEquals(original.password, changed.password);
        assertEquals(new OtpParameters("SHA256", 8, 60), changed.otp); assertEquals("New", changed.issuer);
        try (AccountData supplied = SmartParser.parse(URI, null); AccountData restored = SmartParser.parse(changed.originalText(), "|")) {
            assertEquals(supplied.code(59), changed.currentCode(59)); assertEquals(changed.currentCode(59), restored.code(59));
            assertEquals(original.password, restored.password);
        }
        HistoryEntry read = HistoryCodec.decode(HistoryCodec.encode(Collections.singletonList(changed))).get(0);
        assertEquals(changed.originalText(), read.originalText()); assertEquals("工作邮箱", read.name);
    }
    @Test public void retainsParametersOnBareSecretReplacement() {
        try (AccountData original = SmartParser.parse(URI, null); AccountData changed = original.replaceOtp(OLD)) {
            assertEquals(original.account, changed.account); assertEquals(original.otp, changed.otp); assertEquals(original.issuer, changed.issuer);
            assertEquals(OLD, changed.secret); assertEquals(AccountData.Kind.URI, changed.kind);
        }
    }
    @Test public void rejectsInvalidStaleMissingAndDuplicateRecords() {
        HistoryEntry original = record(); List<HistoryEntry> old = Collections.singletonList(original);
        for (String bad : new String[]{"invalid-key", "", "otpauth://hotp/test?secret=" + NEXT + "&counter=0", "account----pass----" + NEXT})
            assertThrows(IllegalArgumentException.class, () -> HistoryModel.replaceOtp(old, original, bad));
        List<HistoryEntry> changed = HistoryModel.replaceOtp(old, original, NEXT);
        assertThrows(IllegalArgumentException.class, () -> HistoryModel.replaceOtp(changed, original, OLD));
        assertThrows(IllegalArgumentException.class, () -> HistoryModel.replaceOtp(Collections.emptyList(), original, NEXT));
        try (AccountData data = changed.get(0).open()) {
            HistoryEntry duplicate = HistoryEntry.fromData(UUID.randomUUID().toString(), data, false, 100);
            assertThrows(IllegalArgumentException.class, () -> HistoryModel.replaceOtp(Arrays.asList(original, duplicate), original, NEXT));
        }
        assertEquals(OLD, original.secret);
    }
    @Test public void keepsConcurrentNameChangeAndDeduplicatesReRecognition() {
        HistoryEntry original = record(); List<HistoryEntry> old = HistoryModel.rename(Collections.singletonList(original), original.id, "最新名称");
        List<HistoryEntry> changed = HistoryModel.replaceOtp(old, original, NEXT); assertEquals("最新名称", changed.get(0).name);
        try (AccountData data = changed.get(0).open()) {
            List<HistoryEntry> repeated = HistoryModel.record(changed, data, false, 9999);
            assertEquals(1, repeated.size()); assertEquals(original.id, repeated.get(0).id); assertEquals("最新名称", repeated.get(0).name);
        }
    }
    @Test public void automaticallyParsesRecordUriAndPreservesPasswordSymbols() {
        String text = "keep@example.com------ *Pass* -----" + URI;
        try (AccountData data = SmartParser.parse(text, null); AccountData roundTrip = SmartParser.parse(data.originalText(), null)) {
            assertEquals("keep@example.com", data.account); assertEquals(" *Pass* ", data.password);
            assertEquals(new OtpParameters("SHA256", 8, 60), data.otp); assertEquals(data.code(59), roundTrip.code(59));
        }
    }
}
