package cn.local.accountmanager.storage;

import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import cn.local.accountmanager.core.*;

/** One serial worker for the app; no disk or Keystore operations on the UI thread. */
public final class HistoryRepository {
    public interface Callback { void complete(boolean success); }
    public interface Listener { void changed(); }
    private final EncryptedHistoryStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new HashSet<>();
    private volatile List<HistoryEntry> entries = Collections.emptyList();
    private volatile boolean failed, loaded;

    public HistoryRepository(EncryptedHistoryStore store) { this.store = store; }
    public List<HistoryEntry> entries() { return Collections.unmodifiableList(HistoryModel.active(entries, System.currentTimeMillis())); }
    public HistoryEntry find(String id) { for (HistoryEntry e : entries()) if (e.id.equals(id)) return e; return null; }
    private final Runnable expiryTask = this::load;
    private void scheduleExpiry() {
        main.removeCallbacks(expiryTask); long now = System.currentTimeMillis(), delay = Long.MAX_VALUE;
        for (HistoryEntry e : entries) if (e.expiresAt > 0) delay = Math.min(delay, Math.max(1, e.expiresAt - now));
        if (delay != Long.MAX_VALUE) main.postDelayed(expiryTask, failed ? 60000 : Math.min(delay, 60000));
    }
    public boolean failed() { return failed; }
    public boolean loaded() { return loaded; }
    public void listen(Listener listener) { listeners.add(listener); }
    public void unlisten(Listener listener) { listeners.remove(listener); }
    public void load() { execute(store::load, null); }
    public void clear(Callback callback) { execute(store::clear, callback); }
    public void rename(String id, String name, Callback callback) { execute(() -> store.rename(id, name), callback); }
    public void replaceOtp(HistoryEntry expected, String text, Callback callback) { execute(() -> store.replaceOtp(expected, text), callback); }
    public void importEntries(List<HistoryEntry> incoming, Callback callback) { List<HistoryEntry> snapshot = new ArrayList<>(incoming); execute(() -> store.importEntries(snapshot), callback); }
    public void delete(String id, Callback callback) { execute(() -> store.delete(id), callback); }
    public void manage(String id, Long expires, Boolean pinned, Callback callback) { execute(() -> store.manage(id, expires, pinned), callback); }
    public void record(AccountData data, boolean automatic, Callback callback) { record(data, automatic, 0, callback); }
    public void record(AccountData data, boolean automatic, long expires, Callback callback) {
        // Snapshot fields, not the current result's closeable key, before enqueueing work.
        HistoryEntry snapshot = HistoryEntry.fromData("", data, automatic, 0);
        execute(() -> { try (AccountData copy = snapshot.open()) { return store.record(copy, automatic, expires); } }, callback);
    }
    public void recordWithRetention(AccountData data, boolean automatic, RetentionPeriod period, Callback callback) {
        HistoryEntry snapshot = HistoryEntry.fromData("", data, automatic, 0);
        execute(() -> { try (AccountData copy = snapshot.open()) { return store.recordWithRetention(copy, automatic, period); } }, callback);
    }
    private interface Operation { List<HistoryEntry> run() throws Exception; }
    private void execute(Operation operation, Callback callback) {
        worker.execute(() -> {
            List<HistoryEntry> next = null;
            try { next = Collections.unmodifiableList(operation.run()); }
            catch (Exception ignored) { /* Credentials and exception details are never logged. */ }
            final List<HistoryEntry> result = next;
            main.post(() -> {
                loaded = true; failed = result == null;
                if (result != null) entries = result;
                scheduleExpiry();
                for (Listener listener : new ArrayList<>(listeners)) listener.changed();
                if (callback != null) callback.complete(result != null);
            });
        });
    }
}
