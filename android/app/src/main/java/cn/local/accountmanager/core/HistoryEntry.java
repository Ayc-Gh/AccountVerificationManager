package cn.local.accountmanager.core;

public final class HistoryEntry {
    public final String id, account, password, secret, firstSeparator, lastSeparator;
    public final boolean automatic;
    public final long recognizedAt;

    public HistoryEntry(String id, String account, String password, String secret, String first, String last, boolean automatic, long time) {
        this.id = id; this.account = account; this.password = password; this.secret = secret;
        firstSeparator = first; lastSeparator = last; this.automatic = automatic; recognizedAt = time;
    }
    public AccountData open() { return new AccountData(account, password, secret, firstSeparator, lastSeparator); }
    public String originalText() { return account + firstSeparator + password + lastSeparator + secret; }
    public String currentCode(long seconds) { try (AccountData data = open()) { return data.code(seconds); } }
    public String copyGroup(long seconds) {
        return "账号：" + account + "\n密码：" + password + "\n验证码：" + currentCode(seconds);
    }
}
