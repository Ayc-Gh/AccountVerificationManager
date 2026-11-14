package cn.local.accountmanager.core;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class SmartParserTest {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String URI = "otpauth://totp/Example:alice%2Btest%40example.com?secret=" + SECRET + "&issuer=Example";

    @Test public void recognizesAllThreeTypesAndPreservesRecords() {
        try (AccountData d = SmartParser.parse("demo@example.com------Keep%_Password*-----" + SECRET, null)) {
            assertEquals(AccountData.Kind.RECORD, d.kind); assertEquals("Keep%_Password*", d.password);
            assertEquals("287082", d.code(59));
        }
        try (AccountData d = SmartParser.parse("\u00a0“gezd gnbv\ngy3t qojq gezd gnbv gy3t qojq”\u00a0", null)) {
            assertEquals(AccountData.Kind.SECRET, d.kind); assertEquals(SECRET, d.secret); assertEquals("", d.account); assertEquals("", d.password);
            assertEquals("287082", d.code(59)); assertEquals("验证码：287082", d.copyGroup(59));
            assertEquals("2FA密钥：" + SECRET + "\n验证码：287082", d.copyAll(59));
        }
        try (AccountData d = SmartParser.parse("MY======", "----")) { assertEquals(AccountData.Kind.SECRET, d.kind); assertEquals("MY", d.secret); }
        try (AccountData d = SmartParser.parse("demoSEPPasswordSEP" + SECRET, "SEP")) { assertEquals(AccountData.Kind.RECORD, d.kind); assertEquals("Password", d.password); }
        try (AccountData d = SmartParser.parse(URI, "----")) {
            assertEquals(AccountData.Kind.URI, d.kind); assertEquals("alice+test@example.com", d.account); assertEquals("Example", d.issuer);
            assertEquals(OtpParameters.DEFAULT, d.otp); assertEquals("", d.password); assertEquals("287082", d.code(59));
            assertFalse(d.copyAll(59).contains("密码：")); assertFalse(d.copyGroup(59).contains(SECRET));
        }
    }

    @Test public void uriPercentEncodingPlusAndOptionalMetadata() {
        try (AccountData d = SmartParser.parse("OTPAUTH://TOTP/%E7%A4%BA%E4%BE%8B%3Aalice+test%40example.com?secret=" + SECRET + "&issuer=%E7%A4%BA%E4%BE%8B", null)) {
            assertEquals("示例", d.issuer); assertEquals("alice+test@example.com", d.account);
            try (AccountData roundTrip = SmartParser.parse(d.originalText(), null)) {
                assertEquals(d.account, roundTrip.account); assertEquals(d.issuer, roundTrip.issuer); assertEquals(d.otp, roundTrip.otp);
            }
        }
        try (AccountData d = SmartParser.parse("otpauth://totp/Example%3A%20Alice%20Smith?secret=" + SECRET, null)) {
            assertEquals("Example", d.issuer); assertEquals("Alice Smith", d.account);
        }
        try (AccountData d = SmartParser.parse("otpauth://totp/?secret=" + SECRET + "&issuer=Example&image=https%3A%2F%2Fexample.com%2Ficon.png", null)) {
            assertEquals("Example", d.displayName()); assertEquals("", d.account);
            assertFalse(d.originalText().contains("image="));
        }
        try (AccountData d = SmartParser.parse("otpauth://totp?secret=" + SECRET, null)) { assertEquals("", d.account); assertEquals("287082", d.code(59)); }
    }

    @Test public void rfcVectorsAcrossAllAlgorithms() {
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] algorithms = {"SHA1", "SHA256", "SHA512"};
        String[] seeds = {"12345678901234567890", "12345678901234567890123456789012", "1234567890123456789012345678901234567890123456789012345678901234"};
        String[][] codes = {{"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"},
                {"46119246", "68084774", "67062674", "91819424", "90698825", "77737706"},
                {"90693936", "25091201", "99943326", "93441116", "38618901", "47863826"}};
        for (int algorithm = 0; algorithm < algorithms.length; algorithm++) for (int i = 0; i < times.length; i++) {
            byte[] key = seeds[algorithm].getBytes(StandardCharsets.US_ASCII);
            assertEquals(codes[algorithm][i], Totp.generate(key, times[i], new OtpParameters(algorithms[algorithm], 8, 30)));
            assertEquals(codes[algorithm][i].substring(2), Totp.generate(key, times[i], new OtpParameters(algorithms[algorithm], 6, 30)));
        }
    }

    @Test public void readsCustomPeriodDigitsAndAlgorithm() {
        try (AccountData d = SmartParser.parse(URI + "&algorithm=SHA256&digits=8&period=60", null)) {
            assertEquals(new OtpParameters("SHA256", 8, 60), d.otp);
            byte[] key = Base32.decode(SECRET);
            assertEquals(Totp.generate(key, 59, d.otp), d.code(59));
            assertEquals(d.code(0), d.code(59)); assertNotEquals(d.code(59), d.code(60));
            assertEquals(8, d.code(59).length());
            try (AccountData restored = SmartParser.parse(d.originalText(), null)) { assertEquals(d.code(1234567890), restored.code(1234567890)); }
        }
        try (AccountData d = SmartParser.parse(URI + "&period=1", null)) { assertEquals(1, d.otp.period); }
        try (AccountData d = SmartParser.parse(URI + "&period=2147483647", null)) { assertEquals(2147483647, d.otp.period); }
    }

    @Test public void rejectsMalformedOrUnsupportedUrisWithoutFallback() {
        String[] bad = {"otpauth://hotp/Example?secret=" + SECRET + "&counter=0", "otpauth-migration://offline?data=abc", "otpauth://totp/Alice", "otpauth://totp/Alice?secret=", "otpauth://totp/Alice?secret=A1",
                URI + "&secret=MY", URI + "&SECRET=MY", URI + "&digits=7", URI + "&digits=", URI + "&algorithm=MD5", URI + "&period=0", URI + "&period=-1", URI + "&period=1.5", URI + "&period=2147483648", URI + "&counter=0", URI + "#fragment",
                "otpauth://totp/Other:alice?secret=" + SECRET + "&issuer=Example", "otpauth://totp/Alice%ZZ?secret=" + SECRET, "otpauth://totp/%FF?secret=" + SECRET, "otpauth://totp/Alice%0AInjected?secret=" + SECRET,
                "otpauth://user@totp/Alice?secret=" + SECRET, "otpauth://totp:80/Alice?secret=" + SECRET, "otpauth:totp/Alice?secret=" + SECRET, "otpauth://unknown/Alice?secret=" + SECRET};
        for (String input : bad) assertThrows("Should reject malformed URI", IllegalArgumentException.class, () -> SmartParser.parse(input, null));
        for (String input : new String[]{"", "A1", "A", "MZ", "MY=", "MY======A", "234567"})
            assertThrows(IllegalArgumentException.class, () -> SmartParser.parse(input, null));
    }

    @Test public void allTypesPersistWithExactParametersAndIdentity() throws Exception {
        List<HistoryEntry> entries = new ArrayList<>();
        for (String text : new String[]{"demo@example.com----Password----" + SECRET, SECRET, URI, URI + "&algorithm=SHA512&digits=8&period=60"}) {
            try (AccountData data = SmartParser.parse(text, null)) { entries = HistoryModel.record(entries, data, true, 1); }
        }
        assertEquals(4, entries.size());
        byte[] bytes = HistoryCodec.encode(entries); List<HistoryEntry> restored = HistoryCodec.decode(bytes);
        assertEquals(AccountData.Kind.URI, restored.get(0).kind); assertEquals(new OtpParameters("SHA512", 8, 60), restored.get(0).otp);
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(entries.get(i).originalText(), restored.get(i).originalText());
            assertEquals(entries.get(i).currentCode(1234567890), restored.get(i).currentCode(1234567890));
        }
        try (AccountData duplicate = SmartParser.parse(SECRET.toLowerCase(Locale.ROOT), null)) { restored = HistoryModel.record(restored, duplicate, true, 2); }
        assertEquals(4, restored.size()); assertEquals(AccountData.Kind.SECRET, restored.get(0).kind);
    }

    @Test public void readsV1HistoryAndWritesV2WithoutLosingOldFields() throws Exception {
        byte[] v1 = legacyHistory();
        List<HistoryEntry> migrated = HistoryCodec.decode(v1);
        HistoryEntry old = migrated.get(0);
        assertEquals(AccountData.Kind.RECORD, old.kind); assertEquals(OtpParameters.DEFAULT, old.otp); assertEquals(" *Keep|Symbols* ", old.password);
        assertFalse(old.automatic); assertEquals("|", old.firstSeparator); assertEquals("287082", old.currentCode(59));
        try (AccountData fresh = SmartParser.parse(URI + "&digits=8&period=60", null)) { migrated = HistoryModel.record(migrated, fresh, true, 1235); }
        List<HistoryEntry> roundTrip = HistoryCodec.decode(HistoryCodec.encode(migrated));
        assertEquals(2, roundTrip.size()); assertEquals(" *Keep|Symbols* ", roundTrip.get(1).password);
        assertEquals(old.id, roundTrip.get(1).id); assertEquals(60, roundTrip.get(0).otp.period);
    }

    public static byte[] legacyHistory() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x41564D48); out.writeInt(1); out.writeInt(1);
            for (String value : new String[]{"6ef4828d-6abf-4abe-809e-7ff9f7a1e28a", "legacy@example.com", " *Keep|Symbols* ", SECRET, "|", "|"}) {
                byte[] b = value.getBytes(StandardCharsets.UTF_8); out.writeInt(b.length); out.write(b);
            }
            out.writeBoolean(false); out.writeLong(1234);
        }
        return bytes.toByteArray();
    }
}
