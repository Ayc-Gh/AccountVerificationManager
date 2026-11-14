package cn.local.accountmanager.core;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class CoreTest {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private AccountData parse(String value) { return AccountParser.parse(value, null); }
    private void invalid(String value) { assertThrows(IllegalArgumentException.class, () -> parse(value)); }

    @Test public void rfc6238VectorsAndRollover() {
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] codes = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < times.length; i++) {
            assertEquals(codes[i], Totp.generate(key, times[i], 8));
            assertEquals(codes[i].substring(2), Totp.generate(key, times[i], 6));
        }
        assertEquals("287082", Totp.generate(key, 30, 6));
        assertEquals("287082", Totp.generate(key, 59, 6));
        assertEquals("359152", Totp.generate(key, 60, 6));
        assertThrows(IllegalArgumentException.class, () -> Totp.generate(key, -1, 6));
        assertThrows(IllegalArgumentException.class, () -> Totp.generate(key, 59, 7));
    }

    @Test public void base32KnownVectorsAndWhitespace() {
        String[][] vectors = {{"", ""}, {"f", "MY======"}, {"fo", "MZXQ===="}, {"foo", "MZXW6==="}, {"foob", "MZXW6YQ="}, {"fooba", "MZXW6YTB"}, {"foobar", "MZXW6YTBOI======"}};
        for (int i = 1; i < vectors.length; i++) {
            assertEquals(vectors[i][0], new String(Base32.decode(vectors[i][1]), StandardCharsets.US_ASCII));
            assertEquals(vectors[i][0], new String(Base32.decode(vectors[i][1].replace("=", "")), StandardCharsets.US_ASCII));
        }
        assertArrayEquals("f".getBytes(StandardCharsets.US_ASCII), Base32.decode(" m\u00a0y\t== == == "));
        for (String bad : new String[]{"", "A", "AAA", "AAAAAA", "MZ", "MY=", "MY======A", "MY========", "A0", "A8", "A-", "========"})
            assertThrows("Invalid Base32 " + bad, IllegalArgumentException.class, () -> Base32.decode(bad));
    }

    @Test public void originalRegressionVariableDashesAndPasswordSymbols() {
        try (AccountData data = parse("demo@example.com------Keep%_Password*-----" + SECRET)) {
            assertEquals("demo@example.com", data.account);
            assertEquals("Keep%_Password*", data.password);
            assertEquals("------", data.firstSeparator); assertEquals("-----", data.lastSeparator);
            assertEquals("287082", data.code(59));
        }
        try (AccountData data = parse("\u00a0“ demo@example.com----  Password with spaces  ----my====== ”\u00a0")) {
            assertEquals("  Password with spaces  ", data.password); assertEquals("MY", data.secret);
        }
        try (AccountData data = parse("demo@example.com-－—pass*—－-" + SECRET)) {
            assertEquals("pass*", data.password);
        }
    }

    @Test public void unicodeAndMixedAutomaticSeparators() {
        for (String sep : new String[]{"---", "=====", "|||", "***", "###", "！！！", "———", "💠💠💠", "|#|", "!@#", "[.*]"}) {
            try (AccountData data = parse("demo@example.com" + sep + "pass-word" + sep + SECRET)) {
                assertEquals("demo@example.com", data.account); assertEquals("pass-word", data.password);
                assertEquals(SECRET, data.secret); assertEquals(sep, data.firstSeparator); assertEquals("287082", data.code(59));
            }
        }
    }

    @Test public void allVariableDashLengthsFromThreeToThirtyTwo() {
        for (int left = 3; left <= 32; left++) for (int right = 3; right <= 32; right++) {
            try (AccountData data = parse("demo@example.com" + "-".repeat(left) + "Keep-a%_Password*" + "-".repeat(right) + SECRET)) {
                assertEquals("demo@example.com", data.account); assertEquals("Keep-a%_Password*", data.password);
                assertEquals(SECRET, data.secret); assertEquals("287082", data.code(59));
            }
        }
    }

    @Test public void preservesInternalSymbolsAndBase32Padding() {
        try (AccountData data = parse("demo@example.com|||p*%_Word%*=====MY======")) {
            assertEquals("p*%_Word%*", data.password); assertEquals("MY", data.secret); assertEquals("=====", data.lastSeparator);
        }
        try (AccountData data = parse("demo@example.com###pass!!!inside|||" + SECRET)) {
            assertEquals("pass!!!inside", data.password); assertEquals("###", data.firstSeparator); assertEquals("|||", data.lastSeparator);
        }
        try (AccountData data = parse("demo@example.com====pass|||||MY== == ==")) { assertEquals("MY", data.secret); }
    }

    @Test public void literalManualDelimiters() {
        for (String sep : new String[]{"|", "--", "::", "[.*]", "<->", "SEP", "分隔", "\t", " "}) {
            try (AccountData data = AccountParser.parse("demo@example.com" + sep + "Keep###Symbols*" + sep + SECRET, sep)) {
                assertEquals("Keep###Symbols*", data.password); assertEquals(SECRET, data.secret); assertEquals(sep, data.firstSeparator);
            }
        }
        try (AccountData data = AccountParser.parse("demo@example.com|***pass***|MY======", "|")) { assertEquals("***pass***", data.password); }
        try (AccountData data = AccountParser.parse("demo@example.com=pass=MY======", "=")) { assertEquals("MY", data.secret); }
        try (AccountData data = AccountParser.parse("demo@example.com----pass----inside----" + SECRET, "----")) { assertEquals("pass----inside", data.password); }
        for (String sep : new String[]{"", "::", "\n"})
            assertThrows(IllegalArgumentException.class, () -> AccountParser.parse("demo@example.com|||pass|||" + SECRET, sep));
        assertThrows(IllegalArgumentException.class, () -> AccountParser.parse("demo@example.com||||||" + SECRET, "|||"));
    }

    @Test public void rejectsInvalidRecordsAndClosedResults() {
        for (String bad : new String[]{"", "demo----password", "----password----MY", "demo--------MY", "demo----password----", "demo----password----A1", "demo----password----MY\nother----test----MY", "demo-password-" + SECRET,
                "demo--pass--" + SECRET, "demoaaaPASSaaa" + SECRET, "demo111PASS111" + SECRET, "demo   PASS   " + SECRET}) invalid(bad);
        invalid("x".repeat(AccountParser.MAX_INPUT + 1));
        AccountData closed = parse("demo----pass----MY"); closed.close();
        assertThrows(IllegalStateException.class, () -> closed.code(59));
    }

    @Test public void capacityDeduplicationAndCredentialIdentity() {
        List<HistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) try (AccountData data = parse("demo" + i + "@example.com----Password" + i + "----" + SECRET)) {
            entries = HistoryModel.record(entries, data, true, i);
        }
        assertEquals(10, entries.size()); assertEquals("demo11@example.com", entries.get(0).account); assertEquals("demo2@example.com", entries.get(9).account);
        String originalId = entries.get(9).id;
        try (AccountData data = parse("demo2@example.com----Password2----" + SECRET)) { entries = HistoryModel.record(entries, data, true, 13); }
        assertEquals(10, entries.size()); assertEquals(originalId, entries.get(0).id);
        try (AccountData data = parse("demo2@example.com----Changed----" + SECRET)) { entries = HistoryModel.record(entries, data, true, 14); }
        assertEquals("Changed", entries.get(0).password); assertEquals("Password2", entries.get(1).password);
    }

    @Test public void codecRoundTripRestoreAndFreshHistoryCode() throws Exception {
        List<HistoryEntry> list;
        try (AccountData data = AccountParser.parse("manual@example.com|***Keep|Symbols***|" + SECRET, "|")) { list = HistoryModel.record(Collections.emptyList(), data, false, 123); }
        byte[] bytes = HistoryCodec.encode(list);
        HistoryEntry e = HistoryCodec.decode(bytes).get(0);
        assertFalse(e.automatic); assertEquals("|", e.firstSeparator); assertEquals(123, e.recognizedAt);
        try (AccountData restored = e.open()) { assertEquals("***Keep|Symbols***", restored.password); }
        assertEquals("287082", e.currentCode(59)); assertEquals("359152", e.currentCode(60));
        assertEquals("账号：manual@example.com\n密码：***Keep|Symbols***\n验证码：287082", e.copyGroup(59));
        assertFalse(e.copyGroup(60).contains(SECRET));
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(java.io.IOException.class, () -> HistoryCodec.decode(trailing));
        assertThrows(java.io.IOException.class, () -> HistoryCodec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(java.io.IOException.class, () -> HistoryCodec.decode(new byte[]{1, 2, 3}));
        assertThrows(java.io.IOException.class, () -> HistoryCodec.decode(new byte[HistoryCodec.MAX_BYTES + 1]));
    }
}
