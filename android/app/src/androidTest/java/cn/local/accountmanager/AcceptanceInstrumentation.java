package cn.local.accountmanager;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.util.AtomicFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import cn.local.accountmanager.core.*;
import cn.local.accountmanager.storage.*;

/** Real-device acceptance checks. Runs only against the separate .qa package. */
public final class AcceptanceInstrumentation extends Instrumentation {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private MainActivity activity;
    private HistoryRepository repository;
    private int passed;
    private boolean preview;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments); preview = arguments != null && "true".equals(arguments.getString("preview")); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(getTargetContext().getPackageName().endsWith(".qa"), "isolated QA application");
            storageChecks();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            repository = ((ManagerApplication) activity.getApplication()).history;
            waitFor(repository::loaded);
            clearRepository();
            uiChecks();
            pasteAndClearChecks();
            clearRepository(); main(() -> { click(R.id.nav_parse); click(R.id.clear_input); });
            result.putString("stream", "PASS: " + passed + " on-device checks; Keystore, encrypted persistence, corruption recovery, UI parsing, copies, history, lifecycle.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL after " + passed + " checks: " + e + "\n" + android.util.Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void storageChecks() throws Exception {
        Context context = getTargetContext();
        EncryptedHistoryStore store = new EncryptedHistoryStore(context, "acceptance-history.bin", "acceptance-history-v1");
        try {
            store.clear();
            for (int i = 0; i < 12; i++) try (AccountData data = AccountParser.parse("test" + i + "@example.com----SyntheticPassword" + i + "----" + SECRET, null)) { store.record(data, true); }
            List<HistoryEntry> entries = store.load();
            check(entries.size() == 10 && entries.get(0).account.equals("test11@example.com") && entries.get(9).account.equals("test2@example.com"), "persist ten newest records");
            String raw = new String(Files.readAllBytes(store.file().toPath()), StandardCharsets.UTF_8);
            check(!raw.contains("test11@example.com") && !raw.contains("SyntheticPassword") && !raw.contains(SECRET), "credentials not plaintext on disk");
            entries = new EncryptedHistoryStore(context, "acceptance-history.bin", "acceptance-history-v1").load();
            check(entries.get(0).password.equals("SyntheticPassword11"), "new store instance decrypts with Android Keystore");
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            check(keys.getKey("acceptance-history-v1", null).getEncoded() == null, "encryption key is not exportable");
            String id = entries.get(9).id;
            try (AccountData data = AccountParser.parse("test2@example.com----SyntheticPassword2----" + SECRET, null)) { store.record(data, true); }
            check(store.load().size() == 10 && store.load().get(0).id.equals(id), "duplicate updates same entry and moves to top");
            try (AccountData data = AccountParser.parse("manual@example.com|***Keep|Symbols***|" + SECRET, "|")) { store.record(data, false); }
            HistoryEntry manual = store.load().get(0);
            try (AccountData opened = manual.open()) {
                check(!manual.automatic && manual.firstSeparator.equals("|") && opened.password.equals("***Keep|Symbols***"), "manual mode and exact password restored");
            }
            check(manual.currentCode(59).equals("287082") && manual.currentCode(60).equals("359152"), "history code recalculates across rollover");
            byte[] corrupt = {1, 2, 3, 4, 5}; Files.write(store.file().toPath(), corrupt);
            boolean readFailed = false, writeFailed = false;
            try { store.load(); } catch (Exception expected) { readFailed = true; }
            try (AccountData data = AccountParser.parse("cannot-save@example.com----Password----" + SECRET, null)) {
                try { store.record(data, true); } catch (Exception expected) { writeFailed = true; }
            }
            check(readFailed && writeFailed && Arrays.equals(corrupt, Files.readAllBytes(store.file().toPath())), "unreadable history remains untouched");
            store.clear(); check(store.load().isEmpty(), "explicit clear recovers corrupt file");
        } finally {
            new AtomicFile(store.file()).delete();
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null); keys.deleteEntry("acceptance-history-v1");
        }
    }
    private void pasteAndClearChecks() throws Exception {
        main(() -> {
            click(R.id.auto_mode);
            activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("synthetic QA record", "demo@example.com------Demo%_Password*-----" + SECRET));
            click(R.id.paste);
            check(text(R.id.account).getText().toString().equals("demo@example.com"), "paste and parse button");
        });
        waitFor(() -> repository.entries().get(0).account.equals("demo@example.com"));
        check(repository.entries().size() == 2, "paste duplicate remains deduplicated");
        if (preview) {
            main(() -> scrollFor(R.id.input).scrollTo(0, 0));
            capture("preview-main.png");
            main(() -> {
                ScrollView scroll = scrollFor(R.id.secret);
                scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                check(scroll.getScrollY() > 0, "login details reachable by scrolling");
            });
            capture("preview-details.png");
            main(() -> click(R.id.nav_history));
            capture("preview-history.png");
        }
        main(() -> {
            click(R.id.nav_parse); click(R.id.clear_input);
            check(!activity.findViewById(R.id.copy_code).isEnabled() && repository.entries().size() == 2, "clear current preserves history");
            click(R.id.nav_history);
            HistoryEntry entry = repository.entries().get(0);
            activity.findViewById(R.id.history_list).findViewWithTag("entry:" + entry.id).performClick();
            click(R.id.nav_history); click(R.id.clear_history);
        });
        clickDialog("取消");
        check(repository.entries().size() == 2, "cancel clear history preserves records");
        main(() -> click(R.id.clear_history));
        clickDialog("清空历史");
        waitFor(() -> repository.entries().isEmpty());
        main(() -> {
            click(R.id.nav_parse);
            check(activity.findViewById(R.id.copy_code).isEnabled() && text(R.id.account).getText().toString().equals("demo@example.com"), "confirmed history clear preserves current result");
        });
    }
    private ScrollView scrollFor(int id) {
        ViewParent parent = activity.findViewById(id).getParent();
        while (!(parent instanceof ScrollView)) parent = parent.getParent();
        return (ScrollView) parent;
    }
    private void clickDialog(String label) throws Exception {
        waitForIdleSync();
        UiAutomation automation = getUiAutomation();
        final android.view.accessibility.AccessibilityNodeInfo[] button = {null};
        waitFor(() -> {
            android.view.accessibility.AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) return false;
            for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                if (label.equals(String.valueOf(node.getText())) && node.isClickable()) { button[0] = node; return true; }
            }
            return false;
        });
        if (!button[0].performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("dialog click failed: " + label);
        waitForIdleSync();
    }
    private void capture(String name) throws Exception {
        waitForIdleSync();
        CountDownLatch settled = new CountDownLatch(1);
        main(() -> activity.getWindow().getDecorView().postOnAnimation(() -> activity.getWindow().getDecorView().postOnAnimation(settled::countDown)));
        if (!settled.await(5, TimeUnit.SECONDS)) throw new AssertionError("window did not submit frames");
        // Copy only our application's Window; never capture a different app after instrumentation exits.
        Bitmap[] bitmap = {null}; int[] status = {-1}; CountDownLatch captured = new CountDownLatch(1);
        main(() -> {
            View decor = activity.getWindow().getDecorView();
            bitmap[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(activity.getWindow(), bitmap[0], value -> { status[0] = value; captured.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        if (!captured.await(5, TimeUnit.SECONDS) || status[0] != PixelCopy.SUCCESS) throw new AssertionError("window capture failed: " + status[0]);
        File directory = new File(getTargetContext().getExternalFilesDir(null), "screenshots");
        if (!directory.exists() && !directory.mkdirs()) throw new AssertionError("cannot create screenshot directory");
        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) { bitmap[0].compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap[0].recycle(); }
    }

    private void uiChecks() throws Exception {
        main(() -> { text(R.id.input).setText("demo@example.com------Demo%_Password*-----" + SECRET); click(R.id.parse); });
        waitFor(() -> repository.entries().size() == 1);
        main(() -> {
            check(text(R.id.account).getText().toString().equals("demo@example.com"), "UI account result");
            check(text(R.id.password).getText().toString().equals("Demo%_Password*"), "UI preserves trailing password symbol");
            check(text(R.id.password).getTransformationMethod() != null, "password concealed by default");
            click(R.id.show_password); check(text(R.id.password).getTransformationMethod() == null, "show password toggle");
            click(R.id.copy_account); check(clipboard().equals("demo@example.com"), "copy account");
            click(R.id.copy_password); check(clipboard().equals("Demo%_Password*"), "copy exact password");
            click(R.id.copy_secret); check(clipboard().equals(SECRET), "copy secret");
            click(R.id.copy_code); checkCurrentCode(clipboard(), "copy current six-digit code");
            check(activity.getSystemService(ClipboardManager.class).getPrimaryClipDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"), "clipboard sensitive flag");
            click(R.id.copy_all); check(clipboard().contains("2FA密钥：" + SECRET) && clipboard().contains("密码：Demo%_Password*"), "copy all includes secret and exact password");
            text(R.id.input).append("x");
            check(!activity.findViewById(R.id.copy_code).isEnabled() && text(R.id.code).getText().toString().equals("--- ---"), "editing invalidates old results");
            click(R.id.parse); check(text(R.id.status).getText().toString().contains("密钥"), "invalid input reports error");
        });
        check(repository.entries().size() == 1, "invalid input not stored");
        main(() -> {
            click(R.id.manual_mode); text(R.id.manual_separator).setText("|");
            text(R.id.input).setText("manual@example.com|***Keep|Symbols***|" + SECRET); click(R.id.parse);
        });
        waitFor(() -> repository.entries().size() == 2);
        main(() -> {
            text(R.id.manual_separator).setText("::"); check(!activity.findViewById(R.id.copy_all).isEnabled(), "changing manual delimiter invalidates results");
            click(R.id.nav_history);
            View group = activity.findViewById(R.id.history_list).findViewWithTag("history-copy-3:manual@example.com"); group.performClick();
            check(clipboard().startsWith("账号：manual@example.com\n密码：***Keep|Symbols***\n验证码：") && !clipboard().contains(SECRET), "history group contains three fields without secret");
            View otp = activity.findViewById(R.id.history_list).findViewWithTag("history-copy-2:manual@example.com"); otp.performClick(); checkCurrentCode(clipboard(), "history quick-copy current OTP");
            HistoryEntry entry = repository.entries().get(0);
            View row = activity.findViewById(R.id.history_list).findViewWithTag("entry:" + entry.id); row.performClick();
            check(text(R.id.password).getText().toString().equals("***Keep|Symbols***") && text(R.id.manual_separator).getText().toString().equals("|"), "history click restores exact fields and manual mode");
            click(R.id.auto_mode); check(!activity.findViewById(R.id.copy_code).isEnabled(), "mode switch invalidates result");
            click(R.id.nav_history);
            activity.findViewById(R.id.history_list).findViewWithTag("entry:" + entry.id).performClick();
        });
        // Real Activity recreation retains only an in-memory session; no credentials in a Bundle.
        MainActivity old = activity;
        CountDownLatch recreated = new CountDownLatch(1);
        final MainActivity[] resumed = {null};
        Application application = (Application) getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks callback = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {
                if (a instanceof MainActivity && a != old) { resumed[0] = (MainActivity) a; recreated.countDown(); }
            }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {}
        };
        main(() -> { application.registerActivityLifecycleCallbacks(callback); old.recreate(); });
        if (!recreated.await(10, TimeUnit.SECONDS)) throw new AssertionError("activity did not recreate");
        activity = resumed[0];
        main(() -> {
            application.unregisterActivityLifecycleCallbacks(callback);
            check(text(R.id.password).getText().toString().equals("***Keep|Symbols***") && text(R.id.password).getTransformationMethod() != null, "in-memory result survives recreation with password concealed");
        });
        check(repository.entries().size() == 2, "history survives activity recreation");
        main(() -> { click(R.id.nav_history); HistoryEntry entry = repository.entries().get(0); activity.findViewById(R.id.history_list).findViewWithTag("entry:" + entry.id).performClick(); });
    }
    private void clearRepository() throws Exception {
        CountDownLatch latch = new CountDownLatch(1); boolean[] ok = {false};
        main(() -> repository.clear(success -> { ok[0] = success; latch.countDown(); }));
        if (!latch.await(10, TimeUnit.SECONDS) || !ok[0]) throw new AssertionError("cannot clear QA repository");
    }
    private TextView text(int id) { return activity.findViewById(id); }
    private void click(int id) { if (!activity.findViewById(id).performClick()) throw new AssertionError("click failed " + id); }
    private String clipboard() { return activity.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString(); }
    private void checkCurrentCode(String value, String label) {
        long now = Totp.now(); byte[] key = Base32.decode(SECRET);
        check(value.matches("[0-9]{6}") && (value.equals(Totp.generate(key, now, 6)) || value.equals(Totp.generate(key, now - 1, 6))), label);
    }
    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label); passed++;
        Bundle status = new Bundle(); status.putString("stream", "PASS " + label + "\n"); sendStatus(0, status);
    }
    private void main(Runnable action) { runOnMainSync(action); waitForIdleSync(); }
    private void waitFor(BooleanSupplier condition) throws Exception {
        long until = SystemClock.elapsedRealtime() + 10000;
        while (!condition.getAsBoolean()) {
            if (SystemClock.elapsedRealtime() > until) throw new AssertionError("timed out waiting for state");
            SystemClock.sleep(40);
        }
    }
}
