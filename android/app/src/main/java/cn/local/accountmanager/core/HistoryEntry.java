package cn.local.accountmanager.core;

public final class HistoryEntry {
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
        this.id = id; this.account = account; this.password = password; this.secret = secret;
        firstSeparator = first; lastSeparator = last; this.automatic = automatic; recognizedAt = time;
        this.kind = kind; this.issuer = issuer; this.otp = otp;
    }
    public static HistoryEntry fromData(String id, AccountData data, boolean automatic, long time) {
        return new HistoryEntry(id, data.account, data.password, data.secret, data.firstSeparator, data.lastSeparator, automatic, time,
                data.kind, data.issuer, data.otp);
    }
    public AccountData open() { return new AccountData(account, password, secret, firstSeparator, lastSeparator, kind, issuer, otp); }
    public String originalText() { try (AccountData data = open()) { return data.originalText(); } }
    public String displayName() { try (AccountData data = open()) { return data.displayName(); } }
    public String typeDescription() { try (AccountData data = open()) { return data.typeDescription(); } }
    public String currentCode(long seconds) { try (AccountData data = open()) { return data.code(seconds); } }
    public String copyGroup(long seconds) {
        try (AccountData data = open()) { return data.copyGroup(seconds); }
    }
}
