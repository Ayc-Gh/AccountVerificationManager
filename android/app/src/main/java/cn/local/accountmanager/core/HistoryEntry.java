package cn.local.accountmanager.core;

public final class HistoryEntry {
    public static final int NAME_LIMIT = 40;
    public final String name;
    public final String id, account, password, secret, firstSeparator, lastSeparator;
    public final boolean automatic;
    public final long recognizedAt;
    public final AccountData.Kind kind;
    public final String issuer;
    public final OtpParameters otp;

    public HistoryEntry(String id, String account, String password, String secret, String first, String last, boolean automatic, long time) {
        this(id, account, password, secret, first, last, automatic, time, AccountData.Kind.RECORD, "", OtpParameters.DEFAULT);
    }
    public HistoryEntry(String id, String account, String password, String secret, String first, String last, boolean automatic, long time,
                        AccountData.Kind kind, String issuer, OtpParameters otp) {
        this(id, account, password, secret, first, last, automatic, time, kind, issuer, otp, "");
    }
    public HistoryEntry(String id, String account, String password, String secret, String first, String last, boolean automatic, long time,
                        AccountData.Kind kind, String issuer, OtpParameters otp, String name) {
        this.id = id; this.account = account; this.password = password; this.secret = secret;
        firstSeparator = first; lastSeparator = last; this.automatic = automatic; recognizedAt = time;
        this.kind = kind; this.issuer = issuer; this.otp = otp;
        this.name = normalizeName(name);
    }
    public static String normalizeName(String value) {
        if (value == null) return "";
        int start = 0, end = value.length();
        while (start < end && Base32.whitespace(value.charAt(start))) start++;
        while (end > start && Base32.whitespace(value.charAt(end - 1))) end--;
        String name = value.substring(start, end);
        if (name.length() > NAME_LIMIT) throw new IllegalArgumentException("记录名称最多 40 个字符。");
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i)))
            throw new IllegalArgumentException("记录名称不能包含换行或控制字符。");
        return name;
    }
    public HistoryEntry withName(String value) {
        return new HistoryEntry(id, account, password, secret, firstSeparator, lastSeparator, automatic, recognizedAt, kind, issuer, otp, value);
    }
    public static HistoryEntry fromData(String id, AccountData data, boolean automatic, long time) {
        return new HistoryEntry(id, data.account, data.password, data.secret, data.firstSeparator, data.lastSeparator, automatic, time,
                data.kind, data.issuer, data.otp);
    }
    public AccountData open() { return new AccountData(account, password, secret, firstSeparator, lastSeparator, kind, issuer, otp); }
    public String originalText() { try (AccountData data = open()) { return data.originalText(); } }
    public String displayName() { return name.isEmpty() ? defaultDisplayName() : name; }
    public String defaultDisplayName() { try (AccountData data = open()) { return data.displayName(); } }
    public String typeDescription() { try (AccountData data = open()) { return data.typeDescription(); } }
    public String currentCode(long seconds) { try (AccountData data = open()) { return data.code(seconds); } }
    public String copyGroup(long seconds) {
        try (AccountData data = open()) { return data.copyGroup(seconds); }
    }
}
