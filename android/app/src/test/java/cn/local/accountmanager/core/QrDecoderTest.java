package cn.local.accountmanager.core;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class QrDecoderTest {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String URI = "otpauth://totp/Example:qr%40example.com?secret=" + SECRET + "&issuer=Example&algorithm=SHA256&digits=8&period=60";
    private int[] pixels(String text) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 360, 360);
        int[] result = new int[360 * 360];
        for (int y = 0; y < 360; y++) for (int x = 0; x < 360; x++) result[y * 360 + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
        return result;
    }
    @Test public void pureKeyAndUriPreserveParameters() throws Exception {
        assertEquals(SECRET, QrDecoder.decode(pixels(SECRET), 360, 360).get(0));
        String text = QrDecoder.decode(pixels(URI), 360, 360).get(0);
        assertEquals(URI, text);
        try (AccountData data = SmartParser.parse(text, null)) {
            assertEquals("qr@example.com", data.account); assertEquals("SHA256", data.otp.algorithm);
            assertEquals(8, data.otp.digits); assertEquals(60, data.otp.period);
        }
    }
    @Test public void rotatedAndInverted() throws Exception {
        int[] original = pixels(URI), rotated = new int[original.length], inverted = new int[original.length];
        for (int y = 0; y < 360; y++) for (int x = 0; x < 360; x++) {
            rotated[x * 360 + 359 - y] = original[y * 360 + x];
            inverted[y * 360 + x] = original[y * 360 + x] ^ 0x00ffffff;
        }
        assertEquals(URI, QrDecoder.decode(rotated, 360, 360).get(0));
        assertEquals(URI, QrDecoder.decode(inverted, 360, 360).get(0));
    }
    @Test public void multipleCodesAreChoicesAndDuplicatesCollapse() throws Exception {
        int width = 780, height = 400; int[] image = new int[width * height]; Arrays.fill(image, 0xffffffff);
        int[] first = pixels(SECRET), second = pixels(URI);
        for (int y = 0; y < 360; y++) {
            System.arraycopy(first, y * 360, image, (y + 20) * width + 20, 360);
            System.arraycopy(second, y * 360, image, (y + 20) * width + 400, 360);
        }
        assertEquals(2, QrDecoder.decode(image, width, height).size());
        for (int y = 0; y < 360; y++) System.arraycopy(first, y * 360, image, (y + 20) * width + 400, 360);
        assertEquals(Collections.singletonList(SECRET), QrDecoder.decode(image, width, height));
    }
    @Test public void blankAndNonAuthenticatorCodesReject() throws Exception {
        int[] blank = new int[360 * 360]; Arrays.fill(blank, 0xffffffff);
        assertThrows(IllegalArgumentException.class, () -> QrDecoder.decode(blank, 360, 360));
        for (String text : new String[]{"https://example.com", "otpauth-migration://offline?data=AA", "otpauth://hotp/test?secret=" + SECRET + "&counter=1"}) {
            int[] data = pixels(text);
            assertThrows(IllegalArgumentException.class, () -> QrDecoder.decode(data, 360, 360));
        }
    }
    @Test public void choiceLabelsIdentifyAccountWithoutKey() {
        assertFalse(QrDecoder.describe(SECRET).contains(SECRET));
        assertTrue(QrDecoder.describe(URI).contains("qr@example.com"));
    }
}
