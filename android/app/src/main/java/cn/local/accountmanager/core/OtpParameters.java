package cn.local.accountmanager.core;

import java.util.Locale;
import java.util.Objects;

public final class OtpParameters {
    public static final OtpParameters DEFAULT = new OtpParameters("SHA1", 6, 30);
    public final String algorithm;
    public final int digits, period;

    public OtpParameters(String algorithm, int digits, int period) {
        String canonical = algorithm.toUpperCase(Locale.ROOT).replace("-", "");
        if (!canonical.equals("SHA1") && !canonical.equals("SHA256") && !canonical.equals("SHA512"))
            throw new IllegalArgumentException("不支持的算法，请使用 SHA1、SHA256 或 SHA512。");
        if (digits != 6 && digits != 8) throw new IllegalArgumentException("验证码位数仅支持 6 位或 8 位。");
        if (period <= 0) throw new IllegalArgumentException("刷新周期必须是正整数秒。");
        this.algorithm = canonical; this.digits = digits; this.period = period;
    }
    @Override public boolean equals(Object other) {
        return other instanceof OtpParameters p && algorithm.equals(p.algorithm) && digits == p.digits && period == p.period;
    }
    @Override public int hashCode() { return Objects.hash(algorithm, digits, period); }
    public String description() { return algorithm + " · " + digits + " 位 · " + period + " 秒"; }
}
