package cn.local.accountmanager.storage;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.security.*;
import java.util.*;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import cn.local.accountmanager.core.*;

public final class EncryptedHistoryStore {
    private final AtomicFile file;
    private final String alias;

    public EncryptedHistoryStore(Context context) { this(context, "history.bin", "account-manager-history-v1"); }
    public EncryptedHistoryStore(Context context, String filename, String alias) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), filename));
        this.alias = alias;
    }
    public File file() { return file.getBaseFile(); }

    public synchronized List<HistoryEntry> load() throws IOException, GeneralSecurityException {
        byte[] encrypted;
        // openRead also restores AtomicFile's previous version after an interrupted write.
        try (InputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > HistoryCodec.MAX_BYTES + 128) throw new IOException("历史文件大小异常。");
            }
            encrypted = out.toByteArray();
        } catch (FileNotFoundException e) {
            if (file.getBaseFile().exists()) throw e;
            return new ArrayList<>();
        }
        SecretKey key = key(false);
        byte[] plain = HistoryCipher.decrypt(encrypted, key);
        try {
            List<HistoryEntry> old = HistoryCodec.decode(plain);
            List<HistoryEntry> next = HistoryModel.active(old, System.currentTimeMillis());
            if (next.size() != old.size()) write(next);
            return next;
        }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    public synchronized List<HistoryEntry> record(AccountData data, boolean automatic) throws IOException, GeneralSecurityException {
        return record(data, automatic, 0);
    }
    public synchronized List<HistoryEntry> record(AccountData data, boolean automatic, long expires) throws IOException, GeneralSecurityException {
        // Re-read before every write; a damaged or unreadable file is never overwritten implicitly.
        List<HistoryEntry> next = HistoryModel.record(load(), data, automatic, System.currentTimeMillis(), expires);
        write(next); return next;
    }

    public synchronized List<HistoryEntry> clear() throws IOException, GeneralSecurityException {
        List<HistoryEntry> next = new ArrayList<>();
        write(next); return next;
    }
    public synchronized List<HistoryEntry> recordWithRetention(AccountData data, boolean automatic, RetentionPeriod period) throws IOException, GeneralSecurityException {
        List<HistoryEntry> current = load(); long now = System.currentTimeMillis();
        boolean duplicate = current.stream().anyMatch(e -> HistoryModel.matches(e, data));
        long expires = period == null || duplicate ? 0 : period.expiresAt(now);
        List<HistoryEntry> next = HistoryModel.record(current, data, automatic, now, expires);
        write(next); return next;
    }

    public synchronized List<HistoryEntry> rename(String id, String name) throws IOException, GeneralSecurityException {
        List<HistoryEntry> next = HistoryModel.rename(load(), id, name);
        write(next); return next;
    }

    public synchronized List<HistoryEntry> replaceOtp(HistoryEntry expected, String text) throws IOException, GeneralSecurityException {
        List<HistoryEntry> next = HistoryModel.replaceOtp(load(), expected, text);
        write(next); return next;
    }

    public synchronized List<HistoryEntry> delete(String id) throws IOException, GeneralSecurityException {
        List<HistoryEntry> next = HistoryModel.delete(load(), id, System.currentTimeMillis()); write(next); return next;
    }
    public synchronized List<HistoryEntry> manage(String id, Long expires, Boolean pinned) throws IOException, GeneralSecurityException {
        List<HistoryEntry> next = HistoryModel.manage(load(), id, expires, pinned, System.currentTimeMillis()); write(next); return next;
    }
    public synchronized List<HistoryEntry> importEntries(List<HistoryEntry> incoming) throws IOException, GeneralSecurityException {
        List<HistoryEntry> next = HistoryModel.importEntries(load(), incoming, System.currentTimeMillis()); write(next); return next;
    }
    private void write(List<HistoryEntry> entries) throws IOException, GeneralSecurityException {
        byte[] plain = HistoryCodec.encode(entries), encrypted;
        try { encrypted = HistoryCipher.encrypt(plain, key(true)); }
        finally { Arrays.fill(plain, (byte) 0); }
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(encrypted); file.finishWrite(stream);
        } catch (IOException | RuntimeException e) {
            if (stream != null) file.failWrite(stream);
            throw e;
        }
    }

    private SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if (keys.containsAlias(alias)) return (SecretKey) keys.getKey(alias, null);
        if (!create) throw new KeyStoreException("历史记录密钥不可用，原文件已保留。");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
