package cn.local.accountmanager.core;

import java.util.Arrays;

public final class AccountData implements AutoCloseable {
    public enum Kind { RECORD, SECRET, URI }
    public final String account, password, secret, firstSeparator, lastSeparator;
    public final Kind kind;
    public final String issuer;
    public final OtpParameters otp;
    private byte[] key;

    public AccountData(String account, String password, String secret, String first, String last) {
        this(account, password, secret, first, last, Kind.RECORD, "", OtpParameters.DEFAULT);
    }

    public AccountData(String account, String password, String secret, String first, String last, Kind kind, String issuer, OtpParameters otp) {
        if (account == null || password == null || secret == null || first == null || last == null || kind == null || issuer == null || otp == null)
            throw new IllegalArgumentException("识别结果字段不完整。");
        if (kind == Kind.RECORD && (account.isEmpty() || password.isEmpty() || first.isEmpty() || last.isEmpty()))
            throw new IllegalArgumentException("账号记录字段不完整。");
        if (kind != Kind.RECORD && (!password.isEmpty() || !first.isEmpty() || !last.isEmpty()))
            throw new IllegalArgumentException("独立密钥或 URI 中不能包含密码和分隔符。");
        if (kind == Kind.SECRET && (!account.isEmpty() || !issuer.isEmpty() || !otp.equals(OtpParameters.DEFAULT)))
            throw new IllegalArgumentException("独立密钥的参数无效。");
        if (kind == Kind.RECORD && (!issuer.isEmpty() || !otp.equals(OtpParameters.DEFAULT)))
            throw new IllegalArgumentException("账号记录的验证码参数无效。");
        this.account = account;
        this.password = password;
        String normalized = Base32.normalize(secret);
        key = Base32.decode(normalized);
        int padding = normalized.indexOf('=');
        this.secret = padding < 0 ? normalized : normalized.substring(0, padding);
        firstSeparator = first;
        lastSeparator = last;
        this.kind = kind; this.issuer = issuer; this.otp = otp;
    }

    public String code(long seconds) {
        if (key == null) throw new IllegalStateException("该结果已清除，请重新解析。");
        return Totp.generate(key, seconds, otp);
    }

    public String copyAll(long seconds) {
        return copyFields(true, seconds);
    }

    public String copyGroup(long seconds) { return copyFields(false, seconds); }

    private String copyFields(boolean includeSecret, long seconds) {
        StringBuilder out = new StringBuilder();
        if (!issuer.isEmpty()) out.append("发行方：").append(issuer).append('\n');
        if (!account.isEmpty()) out.append("账号：").append(account).append('\n');
        if (!password.isEmpty()) out.append("密码：").append(password).append('\n');
        if (includeSecret) {
            out.append("2FA密钥：").append(secret).append('\n');
            if (kind == Kind.URI) out.append("验证参数：").append(otp.description()).append('\n');
        }
        return out.append("验证码：").append(code(seconds)).toString();
    }

    public String displayName() {
        if (!issuer.isEmpty() && !account.isEmpty()) return issuer + " · " + account;
        if (!account.isEmpty()) return account;
        if (!issuer.isEmpty()) return issuer;
        return kind == Kind.SECRET ? "独立 2FA 密钥" : "Authenticator URI";
    }

    public String typeDescription() {
        return switch (kind) { case RECORD -> "账号记录"; case SECRET -> "纯 2FA 密钥"; case URI -> "Authenticator URI"; };
    }

    public String originalText() {
        return switch (kind) {
            case RECORD -> account + firstSeparator + password + lastSeparator + secret;
            case SECRET -> secret;
            case URI -> AuthenticatorUri.format(this);
        };
    }

    @Override public void close() {
        if (key != null) { Arrays.fill(key, (byte) 0); key = null; }
    }
}
