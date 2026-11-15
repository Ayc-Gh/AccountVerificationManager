package cn.local.accountmanager.core;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import java.util.*;

/** Pure-Java QR decoding; neither opens links nor contacts a server. */
public final class QrDecoder {
    private QrDecoder() {}

    public static List<String> decode(int[] pixels, int width, int height) {
        LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, true);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        Set<String> found = new LinkedHashSet<>();
        for (int rotation = 0; rotation < 4 && found.isEmpty(); rotation++) {
            for (int inverse = 0; inverse < 2; inverse++) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("已取消二维码识别。");
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(inverse == 0 ? source : source.invert()));
                try {
                    for (Result result : new QRCodeMultiReader().decodeMultiple(bitmap, hints))
                        if (result.getText() != null) found.add(result.getText());
                } catch (NotFoundException ignored) { }
            }
            if (found.isEmpty()) source = source.rotateCounterClockwise();
        }
        if (found.isEmpty()) throw new IllegalArgumentException("未发现二维码，请选择清晰图片，或先裁剪二维码区域。");
        List<String> supported = new ArrayList<>();
        for (String text : found) {
            try (AccountData data = SmartParser.parse(text, null)) { supported.add(text); }
            catch (IllegalArgumentException ignored) { }
            if (supported.size() >= 32) break;
        }
        if (supported.isEmpty()) throw new IllegalArgumentException("二维码内容不是支持的 2FA 密钥或 Authenticator TOTP URI；暂不支持 HOTP 和批量迁移二维码。");
        return supported;
    }

    public static String describe(String text) {
        try (AccountData data = SmartParser.parse(text, null)) {
            return (data.issuer.isEmpty() ? "" : data.issuer + " · ") +
                    (data.account.isEmpty() ? "2FA 密钥" : data.account) + " · TOTP";
        }
    }
}
