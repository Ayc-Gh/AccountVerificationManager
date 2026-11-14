package cn.local.accountmanager.core;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Totp {
    private Totp() {}
    public static long now() { return System.currentTimeMillis() / 1000L; }

    public static String generate(byte[] key, long unixSeconds, int digits) {
        return generate(key, unixSeconds, new OtpParameters("SHA1", digits, 30));
    }

    public static String generate(byte[] key, long unixSeconds, OtpParameters parameters) {
        if (unixSeconds < 0) throw new IllegalArgumentException("无效的验证码时间。");
        int digits = parameters.digits;
        try {
            String algorithm = "Hmac" + parameters.algorithm;
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(unixSeconds / parameters.period).array());
            int offset = hash[hash.length - 1] & 15;
            int binary = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
                    | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
            String result = String.format(Locale.ROOT, "%0" + digits + "d", binary % (digits == 6 ? 1000000 : 100000000));
            Arrays.fill(hash, (byte) 0);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("系统无法计算验证码。", e);
        }
    }
}
