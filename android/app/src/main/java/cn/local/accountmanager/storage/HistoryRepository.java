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
    public List<HistoryEntry> entries() { return entries; }
    public boolean failed() { return failed; }
    public boolean loaded() { return loaded; }
    public void listen(Listener listener) { listeners.add(listener); }
    public void unlisten(Listener listener) { listeners.remove(listener); }
    public void load() { execute(store::load, null); }
    public void clear(Callback callback) { execute(store::clear, callback); }
    public void record(AccountData data, boolean automatic, Callback callback) {
        // Snapshot fields, not the current result's closeable key, before enqueueing work.
        HistoryEntry snapshot = new HistoryEntry("", data.account, data.password, data.secret, data.firstSeparator, data.lastSeparator, automatic, 0);
        execute(() -> { try (AccountData copy = snapshot.open()) { return store.record(copy, automatic); } }, callback);
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
                for (Listener listener : new ArrayList<>(listeners)) listener.changed();
                if (callback != null) callback.complete(result != null);
            });
        });
    }
}
