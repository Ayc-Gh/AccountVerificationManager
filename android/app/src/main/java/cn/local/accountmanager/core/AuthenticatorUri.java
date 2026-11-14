package cn.local.accountmanager.core;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Google Authenticator Key URI format; no network requests are made. */
public final class AuthenticatorUri {
    private AuthenticatorUri() {}

    public static AccountData parse(String text) {
        final URI uri;
        try { uri = new URI(text); }
        catch (URISyntaxException e) { throw new IllegalArgumentException("Authenticator URI 格式不正确，请粘贴完整的 otpauth://totp/ 链接。"); }
        if ("otpauth-migration".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("暂不支持 Authenticator 批量迁移链接，请使用单个 otpauth://totp/ URI。");
        if (!"otpauth".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("Authenticator URI 必须以 otpauth:// 开头。");
        if (uri.isOpaque() || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null)
            throw new IllegalArgumentException("Authenticator URI 包含无效的地址或片段。");
        if ("hotp".equalsIgnoreCase(uri.getRawAuthority()))
            throw new IllegalArgumentException("暂不支持 HOTP 计数型验证码，请使用 otpauth://totp/ URI。");
        if (!"totp".equalsIgnoreCase(uri.getRawAuthority()))
            throw new IllegalArgumentException("Authenticator URI 类型不正确，请使用 otpauth://totp/ URI。");

        Map<String, String> parameters = new HashMap<>();
        String query = uri.getRawQuery();
        if (query != null) for (String part : query.split("&", -1)) {
            if (part.isEmpty()) continue;
            int equals = part.indexOf('=');
            String key = decode(equals < 0 ? part : part.substring(0, equals)).toLowerCase(Locale.ROOT);
            String value = decode(equals < 0 ? "" : part.substring(equals + 1));
            if (parameters.put(key, value) != null) throw new IllegalArgumentException("Authenticator URI 含有重复参数，请核对链接。");
        }
        String secret = parameters.get("secret");
        if (secret == null || secret.isEmpty()) throw new IllegalArgumentException("Authenticator URI 缺少 secret 密钥参数。");
        if (parameters.containsKey("counter")) throw new IllegalArgumentException("TOTP URI 不应包含 HOTP 的 counter 参数。");
        String path = uri.getRawPath();
        String label = decode(path == null ? "" : path.startsWith("/") ? path.substring(1) : path).trim();
        int colon = label.indexOf(':');
        String labelIssuer = colon < 0 ? "" : label.substring(0, colon).trim();
        String account = (colon < 0 ? label : label.substring(colon + 1)).trim();
        String issuer = parameters.containsKey("issuer") ? parameters.get("issuer").trim() : labelIssuer;
        if (account.indexOf(':') >= 0 || issuer.indexOf(':') >= 0)
            throw new IllegalArgumentException("Authenticator URI 的账号或发行方不能包含额外冒号。");
        if (!labelIssuer.isEmpty() && !issuer.isEmpty() && !labelIssuer.equals(issuer))
            throw new IllegalArgumentException("Authenticator URI 的标签发行方与 issuer 参数不一致。");
        if (issuer.isEmpty()) issuer = labelIssuer;
        rejectControls(label); rejectControls(issuer);
        OtpParameters otp = new OtpParameters(parameters.getOrDefault("algorithm", "SHA1"),
                number(parameters, "digits", 6), number(parameters, "period", 30));
        return new AccountData(account, "", secret, "", "", AccountData.Kind.URI, issuer, otp);
    }

    private static int number(Map<String, String> parameters, String key, int fallback) {
        if (!parameters.containsKey(key)) return fallback;
        String value = parameters.get(key);
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("URI 的 " + key + " 必须是正整数。");
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("URI 的 " + key + " 数值过大。"); }
    }

    private static void rejectControls(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)))
            throw new IllegalArgumentException("Authenticator URI 的标签不能包含控制字符。");
    }

    // RFC 3986: a literal '+' is a plus, including in user+tag@example.com.
    private static String decode(String raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) == '%') {
                if (i + 2 >= raw.length()) throw new IllegalArgumentException("URI 的百分号编码不完整。");
                int a = Character.digit(raw.charAt(i + 1), 16), b = Character.digit(raw.charAt(i + 2), 16);
                if (a < 0 || b < 0) throw new IllegalArgumentException("URI 的百分号编码无效。");
                bytes.write(a * 16 + b); i += 3;
            } else {
                int end = i + Character.charCount(raw.codePointAt(i));
                byte[] encoded = raw.substring(i, end).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length); i = end;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("URI 中包含无效的 UTF-8 编码。"); }
    }

    public static String format(AccountData data) {
        String label = data.issuer.isEmpty() ? data.account : data.issuer + ":" + data.account;
        return "otpauth://totp/" + encode(label) + "?secret=" + data.secret
                + (data.issuer.isEmpty() ? "" : "&issuer=" + encode(data.issuer))
                + "&algorithm=" + data.otp.algorithm + "&digits=" + data.otp.digits + "&period=" + data.otp.period;
    }

    private static String encode(String value) {
        StringBuilder out = new StringBuilder();
        final String hex = "0123456789ABCDEF";
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~".indexOf(c) >= 0) out.append((char) c);
            else out.append('%').append(hex.charAt(c >> 4)).append(hex.charAt(c & 15));
        }
        return out.toString();
    }
}
