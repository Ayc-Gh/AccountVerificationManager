package cn.local.accountmanager.core;

/** Fixed elapsed durations: day = 24 hours, week = 7 days, month = 30 days. */
public final class RetentionPeriod {
    private static final long[] MILLIS = {60000L, 3600000L, 86400000L, 604800000L, 2592000000L};
    private static final String[] UNITS = {"分", "时", "天", "周", "月"};
    public final int amount, unit;
    private RetentionPeriod(int amount, int unit) { this.amount = amount; this.unit = unit; }
    public static RetentionPeriod parse(String text, int unit) {
        text = text == null ? "" : text.trim();
        if (!text.matches("[0-9]{1,6}") || Integer.parseInt(text) < 1)
            throw new IllegalArgumentException("请输入 1 至 999999 的整数。");
        if (unit < 0 || unit >= UNITS.length) throw new IllegalArgumentException("请选择分、时、天、周或月。");
        return new RetentionPeriod(Integer.parseInt(text), unit);
    }
    public String label() { return amount + UNITS[unit]; }
    public long expiresAt(long now) {
        try {
            long result = Math.addExact(now, amount * MILLIS[unit]);
            if (result <= now || result > 253402300799999L) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException e) { throw new IllegalArgumentException("期限过长，请减小数字或选择更短的单位。"); }
    }
}
