package cn.local.accountmanager.core;

public final class SmartParser {
    private SmartParser() {}

    public static AccountData parse(String input, String manualSeparator) {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("请粘贴账号记录、2FA 密钥或 Authenticator URI。");
        if (input.length() > AccountParser.MAX_INPUT) throw new IllegalArgumentException("内容过长，请每次只识别一条记录。");
        String text = strip(input);
        if (text.length() >= 2 && ((text.startsWith("“") && text.endsWith("”")) || (text.startsWith("\"") && text.endsWith("\""))))
            text = strip(text.substring(1, text.length() - 1));
        if (text.regionMatches(true, 0, "otpauth:", 0, 8) || text.regionMatches(true, 0, "otpauth-migration:", 0, 18))
            return AuthenticatorUri.parse(text);
        if (manualSeparator != null && !manualSeparator.isEmpty()) {
            int first = text.indexOf(manualSeparator), last = text.lastIndexOf(manualSeparator);
            if (first >= 0 && last >= first + manualSeparator.length()) return AccountParser.parse(input, manualSeparator);
        }
        boolean keyCandidate = !text.isEmpty();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '=' || Base32.whitespace(c))) { keyCandidate = false; break; }
        }
        if (keyCandidate) return new AccountData("", "", text, "", "", AccountData.Kind.SECRET, "", OtpParameters.DEFAULT);
        return AccountParser.parse(input, manualSeparator);
    }

    private static String strip(String value) {
        int start = 0, end = value.length();
        while (start < end && Base32.whitespace(value.charAt(start))) start++;
        while (end > start && Base32.whitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }
}
