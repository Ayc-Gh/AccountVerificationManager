package cn.local.accountmanager.core;

import java.util.ArrayList;
import java.util.List;

/** Literal port of the desktop v1.4 separator rules, scanning Unicode code points. */
public final class AccountParser {
    public static final int MAX_INPUT = 32768;
    private record Span(int start, int length) {}
    private AccountParser() {}

    private static String trim(String s) {
        int start = 0, end = s.length();
        while (start < end && Base32.whitespace(s.codePointAt(start))) start += Character.charCount(s.codePointAt(start));
        while (end > start && Base32.whitespace(s.codePointBefore(end))) end -= Character.charCount(s.codePointBefore(end));
        return s.substring(start, end);
    }

    public static AccountData parse(String input, String manualSeparator) {
        if (input == null || trim(input).isEmpty()) throw new IllegalArgumentException("请先粘贴一条账号记录。");
        if (input.length() > MAX_INPUT) throw new IllegalArgumentException("内容过长，请每次只粘贴一条账号记录。");
        String text = trim(input);
        if (text.length() >= 2 && ((text.startsWith("“") && text.endsWith("”")) || (text.startsWith("\"") && text.endsWith("\""))))
            text = trim(text.substring(1, text.length() - 1));
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0)
            throw new IllegalArgumentException("每次请粘贴一条账号记录，不要包含多行内容。");
        Span first, last;
        int scanEnd = text.length();
        int uriIndex = text.toLowerCase(java.util.Locale.ROOT).indexOf("otpauth://");
        if (uriIndex > 0) scanEnd = uriIndex;
        else while (scanEnd > 0 && (text.charAt(scanEnd - 1) == '=' || Base32.whitespace(text.charAt(scanEnd - 1)))) scanEnd--;
        if (manualSeparator == null) {
            List<Span> spans = findSeparators(text, scanEnd);
            if (spans.size() < 2) throw new IllegalArgumentException("未找到两处分隔符。自动模式需要连续 3 个及以上符号，也可手动指定。");
            first = spans.get(0);
            last = spans.get(spans.size() - 1);
        } else {
            if (manualSeparator.isEmpty()) throw new IllegalArgumentException("请填写手动分隔符。");
            if (manualSeparator.indexOf('\r') >= 0 || manualSeparator.indexOf('\n') >= 0)
                throw new IllegalArgumentException("手动分隔符不能包含换行。");
            int a = text.indexOf(manualSeparator);
            int b = text.substring(0, scanEnd).lastIndexOf(manualSeparator);
            if (a < 0 || b < a + manualSeparator.length())
                throw new IllegalArgumentException("未找到两处手动分隔符，请核对内容是否一致。");
            first = new Span(a, manualSeparator.length());
            last = new Span(b, manualSeparator.length());
        }
        String account = trim(text.substring(0, first.start));
        String password = text.substring(first.start + first.length, last.start);
        String secret = trim(text.substring(last.start + last.length));
        if (account.isEmpty()) throw new IllegalArgumentException("账号不能为空。");
        if (password.isEmpty()) throw new IllegalArgumentException("密码不能为空。");
        try (AccountData otp = secret.regionMatches(true, 0, "otpauth:", 0, 8) ? AuthenticatorUri.parse(secret) :
                new AccountData("", "", secret, "", "", AccountData.Kind.SECRET, "", OtpParameters.DEFAULT)) {
            return new AccountData(account, password, otp.secret, text.substring(first.start, first.start + first.length),
                    text.substring(last.start, last.start + last.length), AccountData.Kind.RECORD, otp.issuer, otp.otp);
        }
    }

    private static List<Span> findSeparators(String text, int end) {
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < end;) {
            int cp = text.codePointAt(i);
            if (!symbol(cp)) { i += Character.charCount(cp); continue; }
            int start = i, count = 0;
            while (i < end && symbol(text.codePointAt(i))) { i += Character.charCount(text.codePointAt(i)); count++; }
            if (count < 3) continue;
            int repeatedStart = -1, repeatedEnd = -1;
            for (int j = start; j < i;) {
                int groupStart = j, original = text.codePointAt(j), groupCount = 1;
                j += Character.charCount(original);
                while (j < i && sameSymbol(original, text.codePointAt(j))) {
                    j += Character.charCount(text.codePointAt(j)); groupCount++;
                }
                if (groupCount >= 3) { if (repeatedStart < 0) repeatedStart = groupStart; repeatedEnd = j; }
            }
            spans.add(repeatedStart < 0 ? new Span(start, i - start) : new Span(repeatedStart, repeatedEnd - repeatedStart));
        }
        return spans;
    }

    private static boolean sameSymbol(int a, int b) {
        String dashes = "-‐‑‒–—―−﹘﹣－";
        return a == b || (dashes.indexOf(a) >= 0 && dashes.indexOf(b) >= 0);
    }

    private static boolean symbol(int cp) {
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                 Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }
}
