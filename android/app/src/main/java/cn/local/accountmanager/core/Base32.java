package cn.local.accountmanager.core;

import java.io.ByteArrayOutputStream;

public final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private Base32() {}

    static boolean whitespace(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
    }

    public static String normalize(String input) {
        StringBuilder out = new StringBuilder();
        input.codePoints().filter(c -> !whitespace(c)).forEach(c -> out.appendCodePoint(Character.toUpperCase(c)));
        return out.toString();
    }

    public static byte[] decode(String input) {
        String value = normalize(input);
        if (value.isEmpty()) throw new IllegalArgumentException("2FA 密钥不能为空。");
        int padding = value.indexOf('=');
        String raw = padding < 0 ? value : value.substring(0, padding);
        if (padding >= 0) {
            for (int i = padding; i < value.length(); i++)
                if (value.charAt(i) != '=') throw new IllegalArgumentException("2FA 密钥的填充符只能出现在末尾。");
            if (value.length() % 8 != 0 || value.length() - raw.length() > 6)
                throw new IllegalArgumentException("2FA 密钥的 Base32 填充格式不正确。");
        }
        int remainder = raw.length() % 8;
        if (raw.isEmpty() || remainder == 1 || remainder == 3 || remainder == 6)
            throw new IllegalArgumentException("2FA 密钥长度不正确，请检查是否复制完整。");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (int i = 0; i < raw.length(); i++) {
            int digit = ALPHABET.indexOf(raw.charAt(i));
            if (digit < 0) throw new IllegalArgumentException("2FA 密钥只能包含 A–Z 和 2–7；末尾可带 =。");
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bytes.write((buffer >> bits) & 255);
                buffer &= (1 << bits) - 1;
            }
        }
        if (bits > 0 && buffer != 0) throw new IllegalArgumentException("2FA 密钥末尾无效，请检查是否复制正确。");
        return bytes.toByteArray();
    }
}
