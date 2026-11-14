package cn.local.accountmanager.storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import cn.local.accountmanager.core.HistoryCodec;

/** Authenticated binary envelope. Only ciphertext is ever passed to AtomicFile. */
public final class HistoryCipher {
    private static final byte[] AAD = "AccountVerificationManager.Android.History.v1".getBytes(StandardCharsets.UTF_8);
    private HistoryCipher() {}
    public static byte[] encrypt(byte[] plain, SecretKey key) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(plain), iv = cipher.getIV();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x41564D45); out.writeInt(1); out.writeByte(iv.length); out.write(iv);
            out.writeInt(encrypted.length); out.write(encrypted);
        }
        return bytes.toByteArray();
    }
    public static byte[] decrypt(byte[] bytes, SecretKey key) throws GeneralSecurityException, IOException {
        if (bytes.length > HistoryCodec.MAX_BYTES + 128) throw new IOException("历史文件大小异常。");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x41564D45 || in.readInt() != 1) throw new IOException("历史文件格式无效。");
            int length = in.readUnsignedByte();
            if (length != 12) throw new IOException("历史文件加密参数无效。");
            byte[] iv = new byte[length]; in.readFully(iv);
            int size = in.readInt();
            if (size < 16 || size > HistoryCodec.MAX_BYTES + 16 || size != in.available()) throw new IOException("历史文件长度无效。");
            byte[] encrypted = new byte[size]; in.readFully(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            return cipher.doFinal(encrypted);
        }
    }
}
