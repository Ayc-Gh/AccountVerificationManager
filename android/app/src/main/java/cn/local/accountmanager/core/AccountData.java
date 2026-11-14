package cn.local.accountmanager.core;

import java.util.Arrays;

public final class AccountData implements AutoCloseable {
    public final String account, password, secret, firstSeparator, lastSeparator;
    private byte[] key;

    public AccountData(String account, String password, String secret, String first, String last) {
        this.account = account;
        this.password = password;
        this.secret = secret;
        firstSeparator = first;
        lastSeparator = last;
        key = Base32.decode(secret);
    }

    public String code(long seconds) {
        if (key == null) throw new IllegalStateException("该结果已清除，请重新解析。");
        return Totp.generate(key, seconds, 6);
    }

    public String copyAll(long seconds) {
        return "账号：" + account + "\n密码：" + password + "\n2FA密钥：" + secret + "\n验证码：" + code(seconds);
    }

    @Override public void close() {
        if (key != null) { Arrays.fill(key, (byte) 0); key = null; }
    }
}
