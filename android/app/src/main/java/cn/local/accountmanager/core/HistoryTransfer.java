package cn.local.accountmanager.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Cross-platform AVMX0001 encrypted transfer; authenticate before decrypting. */
public final class HistoryTransfer {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final byte[] MAGIC = "AVMX0001".getBytes(StandardCharsets.US_ASCII);
    private HistoryTransfer() {}
    private static byte[] derive(String password, byte[] salt) throws GeneralSecurityException {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("请输入文件密码。");
        char[] chars = password.toCharArray(); PBEKeySpec spec = new PBEKeySpec(chars, salt, 210000, 512); Arrays.fill(chars, '\0');
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    public static byte[] encrypt(List<HistoryEntry> entries, String password) throws IOException, GeneralSecurityException {
        byte[] plain = encode(entries), salt = new byte[16], iv = new byte[16], keys = null;
        try {
            SecureRandom random = new SecureRandom(); random.nextBytes(salt); random.nextBytes(iv); keys = derive(password, salt);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys, 0, 32, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plain), file = new byte[40 + encrypted.length + 32];
            System.arraycopy(MAGIC, 0, file, 0, 8); System.arraycopy(salt, 0, file, 8, 16); System.arraycopy(iv, 0, file, 24, 16); System.arraycopy(encrypted, 0, file, 40, encrypted.length);
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(keys, 32, 32, "HmacSHA256")); mac.update(file, 0, file.length - 32); System.arraycopy(mac.doFinal(), 0, file, file.length - 32, 32);
            if (file.length > MAX_BYTES) throw new IOException("导出内容过大。"); return file;
        } finally { Arrays.fill(plain, (byte)0); if (keys != null) Arrays.fill(keys, (byte)0); }
    }
    public static List<HistoryEntry> decrypt(byte[] file, String password) throws IOException, GeneralSecurityException {
        if (file.length < 88 || file.length > MAX_BYTES || (file.length - 72) % 16 != 0 || !Arrays.equals(MAGIC, Arrays.copyOf(file, 8))) throw new IOException("文件格式无效，请选择 .avmx 加密文件。");
        byte[] keys = derive(password, Arrays.copyOfRange(file, 8, 24)), plain = null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(keys, 32, 32, "HmacSHA256")); mac.update(file, 0, file.length - 32);
            if (!MessageDigest.isEqual(mac.doFinal(), Arrays.copyOfRange(file, file.length - 32, file.length))) throw new IOException("密码错误或文件已损坏。");
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys, 0, 32, "AES"), new IvParameterSpec(file, 24, 16));
            plain = cipher.doFinal(file, 40, file.length - 72); return decode(plain);
        } finally { Arrays.fill(keys, (byte)0); if (plain != null) Arrays.fill(plain, (byte)0); }
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); if (bytes.length > 131072) throw new IOException("字段过长。"); out.writeLong(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        long length = in.readLong(); if (length < 0 || length > 131072 || length > in.available()) throw new IOException("文件字段无效。");
        byte[] bytes = new byte[(int)length]; in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    private static byte[] encode(List<HistoryEntry> entries) throws IOException {
        if (entries.isEmpty() || entries.size() > HistoryModel.LIMIT) throw new IOException("请选择 1 至 10 条记录。");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(entries.size());
            for (HistoryEntry e : entries) {
                if (e.expired(System.currentTimeMillis())) throw new IOException("选中的记录已到期，请重新选择。");
                text(out, e.kind.name()); text(out, e.account); text(out, e.password);
                try (AccountData data = e.open()) { text(out, AuthenticatorUri.format(data)); }
                text(out, e.name); text(out, e.firstSeparator); text(out, e.lastSeparator);
                out.writeLong(e.recognizedAt); out.writeLong(e.expiresAt); out.writeBoolean(e.automatic); out.writeBoolean(e.pinned);
            }
        }
        return bytes.toByteArray();
    }
    private static List<HistoryEntry> decode(byte[] plain) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
            long count = in.readLong(); if (count < 1 || count > HistoryModel.LIMIT) throw new IOException("文件记录数无效。");
            List<HistoryEntry> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                AccountData.Kind kind = AccountData.Kind.valueOf(text(in)); String account = text(in), password = text(in), uri = text(in), name = text(in), first = text(in), last = text(in);
                long recognized = in.readLong(), expires = in.readLong(); boolean automatic = in.readBoolean(), pinned = in.readBoolean();
                if (recognized < 0 || expires < 0) throw new IOException("文件时间无效。");
                try (AccountData otp = AuthenticatorUri.parse(uri); AccountData data = new AccountData(account, password, otp.secret, first, last, kind, otp.issuer, otp.otp)) {
                    entries.add(HistoryEntry.fromData(UUID.randomUUID().toString(), data, automatic, recognized).withName(name).withManagement(expires, pinned));
                }
            }
            if (in.available() != 0) throw new IOException("文件包含多余数据。"); return entries;
        } catch (IllegalArgumentException e) { throw new IOException("文件记录不受支持：" + e.getMessage()); }
    }
}
