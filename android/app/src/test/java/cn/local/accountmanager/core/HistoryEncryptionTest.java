package cn.local.accountmanager.core;

import cn.local.accountmanager.storage.HistoryCipher;
import org.junit.Test;
import javax.crypto.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class HistoryEncryptionTest {
    @Test public void aesGcmAuthenticatesCiphertextAndUsesFreshIv() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
        SecretKey key = generator.generateKey();
        byte[] plain = "Synthetic@example.com Password123 GEZDGNBVGY3TQOJQ".getBytes(StandardCharsets.UTF_8);
        byte[] a = HistoryCipher.encrypt(plain, key), b = HistoryCipher.encrypt(plain, key);
        assertFalse(Arrays.equals(a, b));
        assertFalse(new String(a, StandardCharsets.UTF_8).contains("Synthetic@example.com"));
        assertArrayEquals(plain, HistoryCipher.decrypt(a, key));
        a[a.length - 1] ^= 1;
        assertThrows(java.security.GeneralSecurityException.class, () -> HistoryCipher.decrypt(a, key));
        assertThrows(java.security.GeneralSecurityException.class, () -> HistoryCipher.decrypt(b, generator.generateKey()));
        assertThrows(java.io.IOException.class, () -> HistoryCipher.decrypt(Arrays.copyOf(b, b.length - 1), key));
        b[0] ^= 1;
        assertThrows(java.io.IOException.class, () -> HistoryCipher.decrypt(b, key));
    }
}
