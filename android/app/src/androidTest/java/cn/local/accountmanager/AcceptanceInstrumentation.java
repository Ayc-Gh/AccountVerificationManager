package cn.local.accountmanager;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.util.AtomicFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import androidx.core.content.IntentCompat;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import cn.local.accountmanager.qr.QrImportActivity;
import cn.local.accountmanager.core.*;
import cn.local.accountmanager.storage.*;

/** Real-device acceptance checks. Runs only against the separate .qa package. */
public final class AcceptanceInstrumentation extends Instrumentation {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private MainActivity activity;
    private HistoryRepository repository;
    private int passed;
    private boolean preview;
    private String exportRotation;
    private String loginRecovery;
    private boolean legacyAutofill;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments); preview = arguments != null && "true".equals(arguments.getString("preview"));
        exportRotation = arguments == null ? null : arguments.getString("exportRotation");
        loginRecovery = arguments == null ? null : arguments.getString("loginRecovery");
        legacyAutofill = arguments != null && "true".equals(arguments.getString("legacyAutofill"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(getTargetContext().getPackageName().endsWith(".qa"), "isolated QA application");
            if (getTargetContext().getSystemService(android.app.KeyguardManager.class).isKeyguardLocked())
                throw new IllegalStateException("Unlock the connected phone before interactive QA checks.");
            if (exportRotation == null && loginRecovery == null && !legacyAutofill) storageChecks();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            repository = ((ManagerApplication) activity.getApplication()).history;
            waitFor(repository::loaded);
            clearRepository();
            if (legacyAutofill) {
                legacyAutofillCopyCheck(savedLoginRecord());
                clearRepository();
                result.putString("stream", "PASS: " + passed + " legacy autofill checks on API " + Build.VERSION.SDK_INT + ".\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (loginRecovery != null) {
                loginRecoveryChecks(loginRecovery);
                clearRepository();
                result.putString("stream", "PASS: " + passed + " login recovery checks.\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (exportRotation != null) {
                rotatingDocumentChecks(exportRotation);
                clearRepository();
                result.putString("stream", "PASS: " + passed + " export rotation checks.\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            uiChecks();
            pasteAndClearChecks();
            smartRecognitionChecks();
            qrImportChecks();
            historyNamingChecks();
            otpReplacementChecks();
            historyManagementChecks();
            transferFileChecks();
            loginChecks();
            loginRecoveryChecks("both");
            rotatingDocumentChecks("both");
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
            KeyStore legacyKeys = KeyStore.getInstance("AndroidKeyStore"); legacyKeys.load(null);
            byte[] legacyPlain = legacyHistory();
            Files.write(store.file().toPath(), HistoryCipher.encrypt(legacyPlain, (javax.crypto.SecretKey) legacyKeys.getKey("acceptance-history-v1", null)));
            List<HistoryEntry> oldHistory = store.load();
            check(oldHistory.size() == 1 && oldHistory.get(0).password.equals(" *Legacy|Password* ") && oldHistory.get(0).otp.equals(OtpParameters.DEFAULT), "old encrypted v1 history still decrypts");
            try (AccountData uri = SmartParser.parse("otpauth://totp/Example:alice@example.com?secret=" + SECRET + "&issuer=Example&algorithm=SHA256&digits=8&period=60", null)) { store.record(uri, true); }
            List<HistoryEntry> upgraded = new EncryptedHistoryStore(context, "acceptance-history.bin", "acceptance-history-v1").load();
            check(upgraded.size() == 2 && upgraded.get(0).otp.equals(new OtpParameters("SHA256", 8, 60)) && upgraded.get(1).password.equals(" *Legacy|Password* "), "v1 migration preserves old record and new URI parameters");
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
            store.rename(manual.id, "手工记录");
            HistoryEntry named = new EncryptedHistoryStore(context, "acceptance-history.bin", "acceptance-history-v1").load().get(0);
            check(named.name.equals("手工记录") && named.password.equals(manual.password) && named.currentCode(59).equals("287082"), "record name persists through real Keystore storage");
            check(!new String(Files.readAllBytes(store.file().toPath()), StandardCharsets.UTF_8).contains("手工记录"), "custom record name is encrypted on disk");
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
    private byte[] legacyHistory() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x41564D48); out.writeInt(1); out.writeInt(1);
            for (String value : new String[]{"6ef4828d-6abf-4abe-809e-7ff9f7a1e28a", "legacy@example.com", " *Legacy|Password* ", SECRET, "|", "|"}) {
                byte[] b = value.getBytes(StandardCharsets.UTF_8); out.writeInt(b.length); out.write(b);
            }
            out.writeBoolean(false); out.writeLong(1234);
        }
        return bytes.toByteArray();
    }

    private void smartRecognitionChecks() throws Exception {
        main(() -> {
            click(R.id.nav_parse); click(R.id.auto_mode);
            text(R.id.input).setText("gezd gnbv gy3t qojq gezd gnbv gy3t qojq"); click(R.id.parse);
            check(!activity.findViewById(R.id.copy_account).isEnabled() && !activity.findViewById(R.id.copy_password).isEnabled(), "pure key has no fabricated account or password");
            check(text(R.id.status).getText().toString().contains("纯 2FA 密钥"), "smart recognition identifies pure Base32 key");
            click(R.id.copy_all); check(clipboard().contains("2FA密钥：" + SECRET) && !clipboard().contains("账号：") && !clipboard().contains("密码："), "pure key copy all contains only real fields");
            click(R.id.copy_code); checkCurrentCode(clipboard(), "pure key current OTP");
        });
        waitFor(() -> repository.entries().size() == 1);
        check(repository.entries().get(0).kind == AccountData.Kind.SECRET, "pure key persists with correct type");
        if (preview) { main(() -> scrollFor(R.id.input).scrollTo(0, 0)); capture("preview-secret.png"); }
        main(() -> {
            click(R.id.nav_history);
            activity.findViewById(R.id.history_list).findViewWithTag("history-copy-4:").performClick(); check(clipboard().equals(SECRET), "pure key history copies secret");
            activity.findViewById(R.id.history_list).findViewWithTag("entry:" + repository.entries().get(0).id).performClick();
            check(text(R.id.input).getText().toString().equals(SECRET) && activity.findViewById(R.id.copy_code).isEnabled(), "pure key history restores recognized result");
        });
        String uri = "otpauth://totp/Example:alice+test%40example.com?secret=" + SECRET + "&issuer=Example&algorithm=SHA256&digits=8&period=60";
        OtpParameters custom = new OtpParameters("SHA256", 8, 60);
        main(() -> {
            text(R.id.input).setText(uri); click(R.id.parse);
            check(text(R.id.account).getText().toString().equals("alice+test@example.com") && text(R.id.issuer).getText().toString().equals("Example"), "URI label issuer and plus address parsed");
            check(!activity.findViewById(R.id.copy_password).isEnabled(), "URI has no fabricated password");
            check(text(R.id.otp_parameters).getText().toString().equals(custom.description()), "URI custom algorithm digits and period displayed");
            click(R.id.copy_code); checkCode(clipboard(), custom, "URI SHA256 eight-digit current OTP");
            click(R.id.copy_uri);
            try (AccountData parsed = SmartParser.parse(clipboard(), null)) {
                check(parsed.otp.equals(custom) && parsed.account.equals("alice+test@example.com"), "copy URI preserves metadata and custom parameters");
            }
        });
        waitFor(() -> repository.entries().size() == 2);
        check(repository.entries().get(0).otp.equals(custom), "asynchronous history save preserves URI parameters");
        if (preview) {
            main(() -> scrollFor(R.id.input).scrollTo(0, 0)); capture("preview-uri.png");
            main(() -> { ScrollView scroll = scrollFor(R.id.secret); scroll.scrollTo(0, scroll.getChildAt(0).getHeight()); }); capture("preview-uri-details.png");
        }
        recreateActivity();
        main(() -> {
            check(text(R.id.otp_parameters).getText().toString().equals(custom.description()), "URI parameters survive activity recreation");
            click(R.id.copy_code); checkCode(clipboard(), custom, "URI code remains correct after recreation");
            text(R.id.input).setText("otpauth://hotp/Alice?secret=" + SECRET + "&counter=0"); click(R.id.parse);
            check(text(R.id.status).getText().toString().contains("HOTP") && !activity.findViewById(R.id.copy_code).isEnabled(), "unsupported HOTP fails clearly and invalidates old result");
            click(R.id.nav_history);
            HistoryEntry entry = repository.entries().get(0);
            activity.findViewById(R.id.history_list).findViewWithTag("history-copy-2:" + entry.account).performClick(); checkCode(clipboard(), custom, "URI history computes custom OTP");
            activity.findViewById(R.id.history_list).findViewWithTag("history-copy-3:" + entry.account).performClick();
            check(clipboard().contains("发行方：Example") && !clipboard().contains("密码：") && !clipboard().contains(SECRET), "URI history group omits absent password and secret");
            activity.findViewById(R.id.history_list).findViewWithTag("entry:" + entry.id).performClick();
            check(text(R.id.otp_parameters).getText().toString().equals(custom.description()), "URI history restore retains parameters");
            click(R.id.parse);
        });
        waitFor(() -> repository.entries().get(0).otp.equals(custom));
        check(repository.entries().size() == 2, "restored URI reparses without duplicate history");
        main(() -> { text(R.id.input).setText("otpauth://totp/?secret=" + SECRET); click(R.id.parse); });
        waitFor(() -> repository.entries().size() == 3);
        main(() -> {
            check(!activity.findViewById(R.id.copy_account).isEnabled() && !activity.findViewById(R.id.copy_password).isEnabled(), "unnamed URI still generates code without fake fields");
            click(R.id.copy_code); checkCurrentCode(clipboard(), "unnamed URI uses standard defaults");
            click(R.id.nav_history);
        });
        if (preview) capture("preview-smart-history.png");
    }

    private void recreateActivity() throws Exception {
        MainActivity old = activity;
        CountDownLatch latch = new CountDownLatch(1); MainActivity[] next = {null};
        Application application = (Application) getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks callback = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) { if (a instanceof MainActivity && a != old) { next[0] = (MainActivity) a; latch.countDown(); } }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {}
        };
        main(() -> { application.registerActivityLifecycleCallbacks(callback); old.recreate(); });
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("URI activity recreation timed out");
        activity = next[0]; main(() -> application.unregisterActivityLifecycleCallbacks(callback));
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
            check(!activity.findViewById(R.id.copy_code).isEnabled(), "confirmed history clear invalidates restored credentials");
        });
        clearHistoryLifecycleChecks();
    }

    private void clearHistoryLifecycleChecks() throws Exception {
        java.lang.reflect.Field storeField = HistoryRepository.class.getDeclaredField("store");
        storeField.setAccessible(true);
        EncryptedHistoryStore store = (EncryptedHistoryStore) storeField.get(repository);
        String record = "clear-lifecycle@example.com----Synthetic----" + SECRET;
        openClearDialogForSavedRecord(record);

        MainActivity page = activity;
        Application app = page.getApplication();
        CountDownLatch stopped = new CountDownLatch(1), resumed = new CountDownLatch(1);
        Application.ActivityLifecycleCallbacks observer = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle state) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) { if (a == page && stopped.getCount() == 0) resumed.countDown(); }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) { if (a == page) stopped.countDown(); }
            public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            public void onActivityDestroyed(Activity a) {}
        };
        main(() -> app.registerActivityLifecycleCallbacks(observer));
        try {
            // clear() uses this monitor on the repository worker. Let Android actually stop the Activity before the write.
            synchronized (store) {
                clickDialog("清空历史");
                main(() -> check(page.moveTaskToBack(true), "confirmed clear moves the QA task to the background"));
                check(stopped.await(10, TimeUnit.SECONDS), "clear is still pending when the Activity stops listening");
            }
            waitFor(() -> repository.entries().isEmpty());
            main(() -> check("历史记录已清空".contentEquals(text(R.id.status).getText()) && resumed.getCount() == 1,
                    "the successful clear callback reached the stopped Activity"));
        } finally {
            try {
                if (stopped.getCount() == 0) {
                    ComponentName component = new ComponentName(page, MainActivity.class);
                    try (BufferedReader output = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(
                            getUiAutomation().executeShellCommand("am start -n " + component.flattenToString())), StandardCharsets.UTF_8))) {
                        while (output.readLine() != null) { /* Wait for the launch command to finish. */ }
                    }
                    check(resumed.await(10, TimeUnit.SECONDS), "the original QA Activity resumes");
                }
            } finally { main(() -> app.unregisterActivityLifecycleCallbacks(observer)); }
        }
        main(() -> {
            click(R.id.nav_parse);
            check(text(R.id.input).length() == 0 && "等待解析".contentEquals(text(R.id.secret).getText()),
                    "returning to the cleared record shows no input or credentials");
            for (int id : new int[]{R.id.copy_account, R.id.copy_password, R.id.copy_secret, R.id.copy_code,
                    R.id.copy_issuer, R.id.copy_uri, R.id.copy_all, R.id.replace_otp})
                check(!page.findViewById(id).isEnabled(), "cleared record disables " + page.getResources().getResourceEntryName(id));
        });

        openClearDialogForSavedRecord(record);
        synchronized (store) {
            clickDialog("清空历史");
            recreateActivity();
        }
        waitFor(() -> repository.entries().isEmpty());
        main(() -> {
            click(R.id.nav_parse);
            check(text(R.id.input).length() == 0 && !activity.findViewById(R.id.copy_code).isEnabled(),
                    "a recreated Activity clears its restored selection when the pending clear completes");
        });

        openClearDialogForSavedRecord(record);
        String draft = "unfinished local draft";
        synchronized (store) {
            clickDialog("清空历史");
            main(() -> { click(R.id.nav_parse); text(R.id.input).setText(draft); });
            recreateActivity();
        }
        waitFor(() -> repository.entries().isEmpty());
        main(() -> check(draft.contentEquals(text(R.id.input).getText()) && !activity.findViewById(R.id.copy_code).isEnabled(),
                "a manual draft entered before clear finishes survives Activity recreation"));

        List<HistoryEntry> pinned = new ArrayList<>();
        for (int i = 0; i < HistoryModel.LIMIT; i++) {
            try (AccountData data = SmartParser.parse("pin" + i + "@example.com----Synthetic----" + SECRET, null)) {
                pinned.add(HistoryEntry.fromData(UUID.randomUUID().toString(), data, true, System.currentTimeMillis()).withManagement(0, true));
            }
        }
        main(() -> repository.importEntries(pinned, null));
        waitFor(() -> repository.entries().size() == pinned.size());
        main(() -> {
            text(R.id.input).setText(record); click(R.id.parse);
            check(text(R.id.status).getText().toString().contains("均已置顶"), "valid parse remains unsaved when history is full of pins");
            click(R.id.nav_history); click(R.id.clear_history);
        });
        clickDialog("清空历史");
        waitFor(() -> repository.entries().isEmpty());
        main(() -> {
            click(R.id.nav_parse);
            check(record.contentEquals(text(R.id.input).getText()) && SECRET.contentEquals(text(R.id.secret).getText())
                            && activity.findViewById(R.id.copy_code).isEnabled() && !activity.findViewById(R.id.replace_otp).isEnabled(),
                    "clearing other records preserves the unsaved result and its copy action");
            click(R.id.clear_input);
        });
    }

    private void openClearDialogForSavedRecord(String record) throws Exception {
        main(() -> { text(R.id.input).setText(record); click(R.id.parse); });
        waitFor(() -> repository.entries().size() == 1);
        main(() -> {
            check(activity.findViewById(R.id.replace_otp).isEnabled(), "current result is bound to its saved record before clear");
            click(R.id.nav_history); click(R.id.clear_history);
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
                if (label.equals(String.valueOf(node.getText())) && node.isEnabled() && node.isClickable()) { button[0] = node; return true; }
            }
            return false;
        });
        if (!button[0].performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("dialog click failed: " + label);
        waitForIdleSync();
    }
    private void capture(String name) throws Exception {
        waitForIdleSync();
        boolean[] protectedWindow = {false};
        main(() -> protectedWindow[0] = (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        if (protectedWindow[0]) {
            Bundle message = new Bundle(); message.putString("stream", "Preview skipped: secure window protection remains enabled.\n");
            sendStatus(0, message); return;
        }
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
            PersistableBundle extras = activity.getSystemService(ClipboardManager.class).getPrimaryClipDescription().getExtras();
            check(extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE"), "sensitive clipboard preview protection enabled");
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
    private File qrFixture(String content) throws Exception {
        File directory = new File(getTargetContext().getCacheDir(), "qr-photos"); directory.mkdirs();
        File file = File.createTempFile("upload-fixture-", ".png", directory);
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 600, 600);
        int[] pixels = new int[600 * 600];
        for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) pixels[y * 600 + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
        Bitmap bitmap = Bitmap.createBitmap(pixels, 600, 600, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
        return file;
    }
    private void qrImportChecks() throws Exception {
        clearRepository();
        AtomicInteger qrWindows = new AtomicInteger(), unprotectedWindows = new AtomicInteger();
        Application application = (Application) getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks privacyObserver = new Application.ActivityLifecycleCallbacks() {
            private void inspect(Activity a) {
                if (a instanceof QrImportActivity) {
                    qrWindows.incrementAndGet();
                    if ((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0) unprotectedWindows.incrementAndGet();
                }
            }
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityPostCreated(Activity a, Bundle b) { inspect(a); }
            public void onActivityStarted(Activity a) { if (Build.VERSION.SDK_INT < 29) inspect(a); }
            public void onActivityResumed(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {}
        };
        main(() -> application.registerActivityLifecycleCallbacks(privacyObserver));
        main(() -> {
            click(R.id.nav_parse);
            check(activity.findViewById(R.id.qr_photo).isEnabled() && activity.findViewById(R.id.qr_upload).isEnabled(), "both QR import actions available");
            check((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0, "main window blocks screenshots and recordings");
            check(BuildConfig.VERSION_NAME.equals("1.10.1-qa") && BuildConfig.VERSION_CODE == 11001, "Android synchronized version 1.10.1");
        });
        String uploadText = "otpauth://totp/Upload:uploaded%40example.com?secret=" + SECRET + "&issuer=Upload&algorithm=SHA256&digits=8&period=60";
        File upload = qrFixture(uploadText);
        AtomicBoolean uploadCalled = new AtomicBoolean();
        ActivityMonitor uploadMonitor = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())) return null;
                uploadCalled.set(true);
                Uri uri = FileProvider.getUriForFile(getTargetContext(), getTargetContext().getPackageName() + ".qr-photos", upload);
                return new ActivityResult(Activity.RESULT_OK, new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            }
        };
        addMonitor(uploadMonitor);
        try {
            main(() -> { click(R.id.manual_mode); click(R.id.qr_upload); });
            waitFor(() -> uploadCalled.get() && text(R.id.account).getText().toString().equals("uploaded@example.com"));
            waitFor(() -> repository.entries().size() == 1);
            main(() -> {
                check(uploadCalled.get(), "upload uses system image picker result");
                check(text(R.id.otp_parameters).getText().toString().contains("SHA256") && text(R.id.code).getText().toString().replace(" ", "").length() == 8, "uploaded QR keeps custom OTP settings");
                click(R.id.copy_code); checkCode(clipboard(), new OtpParameters("SHA256", 8, 60), "uploaded QR current OTP");
            });
            check(repository.entries().get(0).account.equals("uploaded@example.com"), "uploaded QR saved to encrypted history");
            main(() -> check((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0, "QR import keeps main-window capture protection"));
        } finally { removeMonitor(uploadMonitor); upload.delete(); }

        File camera = qrFixture("otpauth://totp/Camera:photo%40example.com?secret=" + SECRET + "&issuer=Camera");
        AtomicBoolean cameraCalled = new AtomicBoolean(); AtomicReference<Throwable> cameraFailure = new AtomicReference<>();
        AtomicReference<Uri> outputUri = new AtomicReference<>();
        ActivityMonitor cameraMonitor = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (!MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction())) return null;
                try {
                    Uri output = IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri.class); outputUri.set(output);
                    if (output == null || !"content".equals(output.getScheme()) || (intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0)
                        throw new AssertionError("camera output URI grant missing");
                    try (java.io.InputStream in = Files.newInputStream(camera.toPath()); java.io.OutputStream out = getTargetContext().getContentResolver().openOutputStream(output)) {
                        byte[] buffer = new byte[8192]; int count; while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                    cameraCalled.set(true); return new ActivityResult(Activity.RESULT_OK, null);
                } catch (Throwable error) { cameraFailure.set(error); return new ActivityResult(Activity.RESULT_CANCELED, null); }
            }
        };
        addMonitor(cameraMonitor);
        try {
            main(() -> click(R.id.qr_photo));
            waitFor(() -> cameraFailure.get() != null || (cameraCalled.get() && text(R.id.account).getText().toString().equals("photo@example.com")));
            if (cameraFailure.get() != null) throw new AssertionError(cameraFailure.get());
            waitFor(() -> repository.entries().size() == 2);
            check(cameraCalled.get() && outputUri.get() != null, "photo capture reads full-resolution output URI");
            main(() -> { click(R.id.copy_code); checkCurrentCode(clipboard(), "photo QR current OTP"); });
            check(repository.entries().get(0).account.equals("photo@example.com"), "photo QR saved to encrypted history");
        } finally { removeMonitor(cameraMonitor); camera.delete(); }

        ActivityMonitor cancelled = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                return Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction()) || MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction()) ?
                        new ActivityResult(Activity.RESULT_CANCELED, null) : null;
            }
        };
        addMonitor(cancelled);
        try {
            main(() -> click(R.id.qr_upload)); waitForIdleSync();
            main(() -> check(text(R.id.account).getText().toString().equals("photo@example.com") && activity.findViewById(R.id.copy_code).isEnabled(), "cancelled upload preserves current result"));
            main(() -> click(R.id.qr_photo)); waitForIdleSync();
            main(() -> check(text(R.id.account).getText().toString().equals("photo@example.com") && activity.findViewById(R.id.copy_code).isEnabled(), "cancelled photo preserves current result"));
        } finally { removeMonitor(cancelled); }

        File invalid = qrFixture("https://example.com/not-an-authenticator");
        ActivityMonitor invalidMonitor = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())) return null;
                return new ActivityResult(Activity.RESULT_OK, new Intent().setData(FileProvider.getUriForFile(getTargetContext(), getTargetContext().getPackageName() + ".qr-photos", invalid)));
            }
        };
        addMonitor(invalidMonitor);
        try {
            main(() -> click(R.id.qr_upload));
            waitFor(() -> text(R.id.status).getText().toString().contains("二维码内容不是支持"));
            main(() -> check(!activity.findViewById(R.id.copy_code).isEnabled(), "non-2FA QR clears stale copy actions"));
            check(repository.entries().size() == 2, "failed QR import does not alter history");
        } finally { removeMonitor(invalidMonitor); invalid.delete(); }
        main(() -> {
            application.unregisterActivityLifecycleCallbacks(privacyObserver);
            check(qrWindows.get() >= (Build.VERSION.SDK_INT >= 29 ? 5 : 2) && unprotectedWindows.get() == 0, "all observed photo and upload windows use capture protection");
            AlertDialog dialog = PrivacyProtection.show(new AlertDialog.Builder(activity).setTitle("保护检查").setMessage("Synthetic test content"));
            try { check((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0, "dialog windows also block capture"); }
            finally { dialog.dismiss(); }
        });
    }
    private AlertDialog namingDialog() {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("namingDialog"); field.setAccessible(true);
            return (AlertDialog) field.get(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private HistoryEntry namedEntry(String id) {
        for (HistoryEntry entry : repository.entries()) if (entry.id.equals(id)) return entry;
        throw new AssertionError("QA history entry missing");
    }
    private boolean hasText(View view, String value) {
        if (view instanceof TextView text && value.contentEquals(text.getText())) return true;
        if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), value)) return true;
        return false;
    }
    private void historyNamingChecks() throws Exception {
        HistoryEntry original = repository.entries().get(1); String id = original.id;
        String firstId = repository.entries().get(0).id;
        main(() -> {
            click(R.id.nav_history);
            View rename = activity.findViewById(R.id.history_list).findViewWithTag("history-name:" + id);
            check(rename != null && rename.performClick(), "history record has naming action");
            AlertDialog dialog = namingDialog();
            check(dialog != null && (dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0, "name editor preserves capture protection");
            ((EditText) dialog.findViewById(R.id.history_name_input)).setText("  工作邮箱  ");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        waitFor(() -> namedEntry(id).name.equals("工作邮箱"));
        main(() -> {
            check(hasText(activity.findViewById(R.id.history_list), "工作邮箱"), "history list shows custom name");
            check(hasText(activity.findViewById(R.id.history_list), original.defaultDisplayName()), "named record still shows original identity");
            check(repository.entries().get(0).id.equals(firstId) && namedEntry(id).recognizedAt == original.recognizedAt, "naming preserves ordering and recognized time");
        });
        HistoryEntry reopened = new EncryptedHistoryStore(getTargetContext()).load().get(1);
        check(reopened.name.equals("工作邮箱") && reopened.originalText().equals(original.originalText()), "reopened history retains name and exact OTP parameters");
        main(() -> {
            activity.findViewById(R.id.history_list).findViewWithTag("entry:" + id).performClick();
            check(text(R.id.account).getText().toString().equals(original.account), "named history restores account rather than label");
            click(R.id.parse);
        });
        waitFor(() -> repository.entries().get(0).id.equals(id));
        check(namedEntry(id).name.equals("工作邮箱") && repository.entries().size() == 2, "re-recognition retains custom name without duplicate");
        main(() -> {
            click(R.id.nav_history);
            activity.findViewById(R.id.history_list).findViewWithTag("history-name:" + id).performClick();
            ((EditText)namingDialog().findViewById(R.id.history_name_input)).setText("");
            namingDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        waitFor(() -> namedEntry(id).name.isEmpty());
        check(namedEntry(id).displayName().equals(namedEntry(id).defaultDisplayName()), "empty name restores automatic history title");
        main(() -> {
            activity.findViewById(R.id.history_list).findViewWithTag("history-name:" + id).performClick();
            ((EditText)namingDialog().findViewById(R.id.history_name_input)).setText("不保存的名称");
            namingDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            check(namedEntry(id).name.isEmpty(), "cancelled name editor does not save draft");
        });
    }
    private AlertDialog replacementDialog() {
        try { java.lang.reflect.Field field = MainActivity.class.getDeclaredField("replacementEditor"); field.setAccessible(true); return ((OtpReplacementEditor)field.get(activity)).dialog; }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void otpReplacementChecks() throws Exception {
        clearRepository(); final String nextSecret = "JBSWY3DPEHPK3PXP";
        final String uri = "otpauth://totp/New:ignored@example.com?secret=" + nextSecret + "&issuer=New&algorithm=SHA256&digits=8&period=60";
        main(() -> { click(R.id.nav_parse); click(R.id.manual_mode); ((EditText)activity.findViewById(R.id.manual_separator)).setText("|");
            ((EditText)activity.findViewById(R.id.input)).setText("keep@example.com| *Pass|word* |" + SECRET); click(R.id.parse); });
        waitFor(() -> repository.entries().size() == 1);
        HistoryEntry original = repository.entries().get(0); CountDownLatch named = new CountDownLatch(1);
        main(() -> repository.rename(original.id, "工作邮箱", ok -> named.countDown()));
        if (!named.await(10, TimeUnit.SECONDS)) throw new AssertionError("QA rename timeout");
        main(() -> {
            check(activity.findViewById(R.id.replace_otp).isEnabled(), "replacement action available for saved record"); click(R.id.replace_otp);
            AlertDialog dialog = replacementDialog();
            check((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0, "replacement dialog preserves capture protection");
            check(!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(), "empty replacement cannot save");
            check(hasText(dialog.getWindow().getDecorView(), "纯密钥沿用当前参数；URI 采用新参数。"),
                    "empty replacement explains the distinction between a bare secret and a URI");
            dialog.findViewById(R.id.replace_otp_old_copy).performClick(); checkCurrentCode(clipboard(), "replacement copies current old OTP");
            check(activity.getSystemService(ClipboardManager.class).getPrimaryClipDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"), "replacement copied OTP is marked sensitive");
            ((EditText)dialog.findViewById(R.id.replace_otp_input)).setText("invalid-key");
            check(!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(), "invalid replacement disables saving");
            activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("synthetic replacement", nextSecret));
            dialog.findViewById(R.id.replace_otp_paste).performClick();
            check(((EditText)dialog.findViewById(R.id.replace_otp_input)).getText().toString().equals(nextSecret) && dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(), "paste new key previews replacement");
            // Select a period that puts the actual device clock at RFC 4226 counter 1.
            // Its SHA1 test key yields 287082 (six digits) and 94287082 (eight).
            long now = Totp.now();
            long period = now - now / 4;
            String vector = "otpauth://totp/QA?secret=" + SECRET + "&algorithm=SHA1&period=" + period;
            EditText draft = dialog.findViewById(R.id.replace_otp_input);
            TextView previewCode = dialog.findViewById(R.id.replace_otp_code);
            draft.setText(vector + "&digits=6");
            check("287 082".contentEquals(previewCode.getText()), "six-digit replacement preview displays the exact two groups");
            draft.setText(vector + "&digits=8");
            check("9428 7082".contentEquals(previewCode.getText()), "eight-digit replacement preview displays the exact two groups");
            ((EditText)dialog.findViewById(R.id.replace_otp_input)).setText(uri);
            check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled() && ((TextView)dialog.findViewById(R.id.replace_otp_code)).getText().toString().replace(" ", "").length() == 8, "replacement previews eight digit new OTP");
            dialog.findViewById(R.id.replace_otp_copy).performClick();
            byte[] key = Base32.decode(nextSecret); String copied = clipboard();
            check(copied.equals(Totp.generate(key, Totp.now(), new OtpParameters("SHA256",8,60))) || copied.equals(Totp.generate(key, Totp.now()-1, new OtpParameters("SHA256",8,60))), "new OTP copy uses replacement key and parameters"); Arrays.fill(key,(byte)0);
            check(repository.entries().get(0).secret.equals(SECRET), "preview does not persist replacement");
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            check(repository.entries().get(0).secret.equals(SECRET), "cancel replacement preserves old record"); click(R.id.replace_otp);
        });
        File image = qrFixture(uri); AtomicBoolean uploaded = new AtomicBoolean();
        ActivityMonitor monitor = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())) return null; uploaded.set(true);
                Uri file = FileProvider.getUriForFile(getTargetContext(), getTargetContext().getPackageName()+".qr-photos", image);
                return new ActivityResult(Activity.RESULT_OK, new Intent().setData(file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            }
        }; addMonitor(monitor);
        try {
            main(() -> replacementDialog().findViewById(R.id.replace_otp_upload).performClick());
            waitFor(() -> uploaded.get() && ((EditText)replacementDialog().findViewById(R.id.replace_otp_input)).getText().toString().equals(uri));
            main(() -> check(uploaded.get() && replacementDialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(), "replacement QR upload returns into draft without changing account"));
            main(() -> {
                try {
                    Object retained = activity.onRetainNonConfigurationInstance(); java.lang.reflect.Field field = retained.getClass().getDeclaredField("replacement"); field.setAccessible(true);
                    check(field.get(retained) != null, "replacement draft available for configuration retention");
                } catch (Exception error) { throw new AssertionError(error); }
            });
            recreateActivity();
            main(() -> {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                check(((EditText)replacementDialog().findViewById(R.id.replace_otp_input)).getText().toString().equals(uri), "replacement draft survives activity recreation in memory");
                replacementDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            waitFor(() -> repository.entries().get(0).secret.equals(nextSecret));
            main(() -> {
                HistoryEntry changed = repository.entries().get(0);
                check(changed.id.equals(original.id) && changed.account.equals(original.account) && changed.password.equals(original.password) && changed.name.equals("工作邮箱"), "replacement preserves record identity credentials and custom name");
                check(changed.recognizedAt == original.recognizedAt && repository.entries().size() == 1, "replacement preserves timestamp position and count");
                check(text(R.id.account).getText().toString().equals("keep@example.com") && text(R.id.password).getText().toString().equals(" *Pass|word* ") && text(R.id.otp_parameters).getText().toString().contains("SHA256"), "saved replacement refreshes current credentials and parameters");
                click(R.id.parse);
            });
            waitForOnMain(() -> repository.entries().size() == 1 && !repository.failed()
                    && activity.findViewById(R.id.replace_otp).isEnabled());
            HistoryEntry disk = new EncryptedHistoryStore(getTargetContext()).load().get(0);
            check(disk.secret.equals(nextSecret) && disk.otp.digits == 8 && disk.name.equals("工作邮箱"), "replacement survives encrypted reload and re-recognition");
        } finally { removeMonitor(monitor); image.delete(); }
        AtomicBoolean photoCalled = new AtomicBoolean();
        ActivityMonitor photo = new ActivityMonitor() { @Override public ActivityResult onStartActivity(Intent intent) {
            if (!MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction())) return null; photoCalled.set(true); return new ActivityResult(Activity.RESULT_CANCELED, null);
        }}; addMonitor(photo);
        try {
            AtomicReference<AlertDialog> cameraOwner = new AtomicReference<>();
            main(() -> {
                click(R.id.replace_otp);
                cameraOwner.set(replacementDialog());
            });
            AlertDialog owner = cameraOwner.get();
            BooleanSupplier dialogFocused = () -> owner.isShowing() && owner.getWindow().getDecorView().hasWindowFocus();
            waitForOnMain(dialogFocused);
            main(() -> owner.findViewById(R.id.replace_otp_photo).performClick());
            waitFor(photoCalled::get);
            waitForOnMain(dialogFocused);
            main(() -> {
                check(replacementDialog() == owner && owner.isShowing() && repository.entries().get(0).secret.equals(nextSecret),
                        "cancelled replacement camera retains dialog and saved record");
                replacementDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
        } finally { removeMonitor(photo); }
    }

    private void waitForOnMain(BooleanSupplier condition) throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        waitFor(() -> {
            runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            return ready.get();
        });
    }

    private void historyManagementChecks() throws Exception {
        clearRepository();
        main(() -> { click(R.id.nav_parse); click(R.id.auto_mode); text(R.id.input).setText("pinned@example.com----Demo----" + SECRET); click(R.id.parse); });
        waitFor(() -> repository.entries().size() == 1);
        String pinnedId = repository.entries().get(0).id;
        CountDownLatch pin = new CountDownLatch(1); main(() -> repository.manage(pinnedId, null, true, ok -> pin.countDown()));
        check(pin.await(10, TimeUnit.SECONDS) && repository.find(pinnedId).pinned, "pin persists through serial encrypted storage");
        AtomicReference<RetentionPeriod> expiry = new AtomicReference<>();
        for (int selection = 0; selection < 5; selection++) {
            final int selectedUnit = selection;
            main(() -> {
                AlertDialog dialog = ExpirationEditor.show(activity, null, 0, false, expiry::set);
                CheckBox enabled = dialog.getWindow().getDecorView().findViewWithTag("expiry-enabled");
                if (selectedUnit == 0) {
                    check((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0, "expiry dialog is protected");
                    check(!enabled.isChecked(), "expiry defaults to permanent");
                }
                enabled.setChecked(true);
                EditText amount = dialog.getWindow().getDecorView().findViewWithTag("expiry-amount");
                Spinner unit = dialog.getWindow().getDecorView().findViewWithTag("expiry-unit");
                if (selectedUnit == 0) {
                    for (String invalid : new String[]{"", "0", "-1", "1.5", "1000000", "abc"}) {
                        amount.setText(invalid); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                        check(dialog.isShowing() && expiry.get() == null, "invalid duration cannot close editor: " + invalid);
                    }
                    amount.setText("999999"); unit.setSelection(4); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    check(dialog.isShowing() && expiry.get() == null, "excessive duration is rejected in editor");
                }
                amount.setText("2"); unit.setSelection(selectedUnit); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                check(!dialog.isShowing() && expiry.get().amount == 2 && expiry.get().unit == selectedUnit, "duration UI accepts unit " + selectedUnit);
            });
        }
        AtomicReference<AlertDialog> pickerOwner = new AtomicReference<>();
        main(() -> {
            AlertDialog dialog = ExpirationEditor.show(activity, RetentionPeriod.parse("3", 1), 0, false, value -> { throw new AssertionError("cancel must not save"); }); pickerOwner.set(dialog);
            Spinner unit = dialog.getWindow().getDecorView().findViewWithTag("expiry-unit"); unit.performClick();
        });
        clickDialog("月");
        main(() -> {
            Spinner unit = pickerOwner.get().getWindow().getDecorView().findViewWithTag("expiry-unit");
            check(unit.getSelectedItemPosition() == 4, "protected unit picker changes adjacent selection");
            pickerOwner.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        });
        AtomicBoolean permanent = new AtomicBoolean();
        main(() -> {
            AlertDialog dialog = ExpirationEditor.show(activity, null, System.currentTimeMillis() + 86400000, true, value -> permanent.set(value == null));
            CheckBox enabled = dialog.getWindow().getDecorView().findViewWithTag("expiry-enabled"); enabled.setChecked(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); check(permanent.get(), "history expiry can be removed to keep permanently");
        });
        main(() -> {
            TextView expiryButton = activity.getWindow().getDecorView().findViewWithTag("new-expiration");
            check("保存期限：永久（仅首次保存）".contentEquals(expiryButton.getText()), "parse page displays the exact permanent retention label");
            expiryButton.performClick();
        });
        clickDialog("设置保存期限");
        clickDialog("保存");
        main(() -> {
            TextView expiryButton = activity.getWindow().getDecorView().findViewWithTag("new-expiration");
            check("保存期限：1天（仅首次保存）".contentEquals(expiryButton.getText()), "saving a one-day duration updates the parse page's label");
        });
        recreateActivity();
        main(() -> {
            text(R.id.input).setText("second@example.com----Demo----" + SECRET); click(R.id.parse);
        });
        waitFor(() -> repository.entries().size() == 2);
        HistoryEntry second = repository.entries().stream().filter(e -> e.account.equals("second@example.com")).findFirst().get();
        main(() -> {
            check(repository.entries().get(0).id.equals(pinnedId) && text(R.id.account).getText().toString().equals("second@example.com"), "new parse selects actual record with pinned first entry");
            check(second.expiresAt == second.recognizedAt + TimeUnit.DAYS.toMillis(1), "duration survives rotation and begins at first save time");
            TextView expiryButton = activity.getWindow().getDecorView().findViewWithTag("new-expiration");
            check("保存期限：永久（仅首次保存）".contentEquals(expiryButton.getText()), "first successful save resets the retention label to permanent");
            check(activity.getWindow().getDecorView().findViewWithTag("history-manage:" + second.id) != null, "history has per-record management entry");
        });
        AtomicReference<List<HistoryEntry>> selection = new AtomicReference<>(); AtomicReference<String> password = new AtomicReference<>();
        main(() -> {
            AlertDialog dialog = TransferDialogs.select(activity, "测试多选", repository.entries(), selection::set);
            check((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0, "multi-selection dialog is protected");
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            check(selection.get().size() == 2, "multi-selection exports both selected entries");
            AlertDialog secretDialog = TransferDialogs.password(activity, true, password::set);
            check((secretDialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0, "file password dialog is protected");
            EditText first = secretDialog.getWindow().getDecorView().findViewWithTag("文件密码"), confirm = secretDialog.getWindow().getDecorView().findViewWithTag("再次输入文件密码");
            first.setText("测试密码 🔑 123"); confirm.setText("different"); secretDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            check(secretDialog.isShowing() && password.get() == null, "mismatched file passwords cannot export");
            confirm.setText("测试密码 🔑 123"); secretDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            check(password.get().equals("测试密码 🔑 123") && first.length() == 0 && confirm.length() == 0, "file password accepted and cleared from editor");
        });
        byte[] encrypted = HistoryTransfer.encrypt(selection.get(), password.get());
        List<HistoryEntry> decoded = HistoryTransfer.decrypt(encrypted, password.get());
        check(decoded.size() == 2 && decoded.get(0).pinned && decoded.get(1).expiresAt == second.expiresAt, "device encryption roundtrip preserves selection and settings");
        boolean rejected = false; try { HistoryTransfer.decrypt(encrypted, "wrong"); } catch (Exception expected) { rejected = true; }
        check(rejected, "wrong import password is rejected on device");
        CountDownLatch deadline = new CountDownLatch(1); main(() -> repository.manage(second.id, System.currentTimeMillis() + 2500, null, ok -> deadline.countDown()));
        check(deadline.await(10, TimeUnit.SECONDS), "short expiry scheduled");
        main(() -> click(R.id.replace_otp));
        waitFor(() -> repository.find(second.id) == null);
        waitFor(() -> !activity.findViewById(R.id.copy_code).isEnabled() && text(R.id.input).length() == 0);
        main(() -> check(!activity.findViewById(R.id.copy_code).isEnabled() && text(R.id.input).length() == 0, "expiry clears current credentials and copy actions"));
        check(new EncryptedHistoryStore(getTargetContext()).load().size() == 1, "expired deletion persists on disk");
        clearRepository(); CountDownLatch imported = new CountDownLatch(1); main(() -> repository.importEntries(decoded, ok -> imported.countDown()));
        check(imported.await(10, TimeUnit.SECONDS) && repository.entries().size() == 2, "multiple decrypted entries import into encrypted local storage");
        CountDownLatch removed = new CountDownLatch(1); String id = repository.entries().get(0).id; main(() -> repository.delete(id, ok -> removed.countDown()));
        check(removed.await(10, TimeUnit.SECONDS) && repository.entries().size() == 1, "single delete keeps other history entries");
    }
    private void passwordNodes(android.view.accessibility.AccessibilityNodeInfo root, List<android.view.accessibility.AccessibilityNodeInfo> result) {
        if (root == null) return;
        if ("android.widget.EditText".contentEquals(root.getClassName()) && root.isEnabled()) result.add(root);
        for (int i = 0; i < root.getChildCount(); i++) passwordNodes(root.getChild(i), result);
    }
    private void enterFilePassword(int count) throws Exception {
        List<android.view.accessibility.AccessibilityNodeInfo> fields = new ArrayList<>();
        waitFor(() -> { fields.clear(); passwordNodes(getUiAutomation().getRootInActiveWindow(), fields); return fields.size() == count; });
        for (android.view.accessibility.AccessibilityNodeInfo node : fields) {
            Bundle args = new Bundle(); args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Synthetic-file-password-123");
            if (!node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw new AssertionError("cannot enter synthetic file password");
        }
    }
    private void transferFileChecks() throws Exception {
        main(() -> { click(R.id.nav_parse); click(R.id.auto_mode); text(R.id.input).setText("file@example.com----Demo----" + SECRET); click(R.id.parse); });
        waitFor(() -> repository.entries().size() == 2);
        File directory = new File(getTargetContext().getCacheDir(), "qr-photos"); directory.mkdirs();
        File file = new File(directory, "synthetic-history.avmx"); if (file.exists()) file.delete();
        AtomicBoolean exported = new AtomicBoolean(), imported = new AtomicBoolean();
        ActivityMonitor monitor = new ActivityMonitor() { @Override public ActivityResult onStartActivity(Intent intent) {
            if (!Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction()) && !Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())) return null;
            if (Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())) exported.set(true); else imported.set(true);
            Uri uri = FileProvider.getUriForFile(getTargetContext(), getTargetContext().getPackageName() + ".qr-photos", file);
            return new ActivityResult(Activity.RESULT_OK, new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        }}; addMonitor(monitor);
        try {
            main(() -> { click(R.id.nav_history); activity.getWindow().getDecorView().findViewWithTag("history-export").performClick(); });
            clickDialog("全选 / 取消"); clickDialog("继续"); enterFilePassword(2); clickDialog("继续");
            waitFor(() -> exported.get() && file.length() > 88 && text(R.id.status).getText().toString().contains("已导出"));
            check(HistoryTransfer.decrypt(Files.readAllBytes(file.toPath()), "Synthetic-file-password-123").size() == 2, "actual create-document route writes selected encrypted file");
            clearRepository();
            main(() -> activity.getWindow().getDecorView().findViewWithTag("history-import").performClick());
            enterFilePassword(1);
            clickDialog("继续");
            clickDialog("全选 / 取消");
            clickDialog("继续");
            waitFor(() -> imported.get() && repository.entries().size() == 2);
            check(repository.entries().stream().anyMatch(e -> e.account.equals("file@example.com")), "actual open-document route decrypts and imports selected records");
            check(new EncryptedHistoryStore(getTargetContext()).load().size() == 2, "file import survives encrypted storage reload");
        } finally { removeMonitor(monitor); file.delete(); }
    }
    @SuppressWarnings("unchecked") private <T> T loginField(LoginActivity login,String name) {
        try { java.lang.reflect.Field field=LoginActivity.class.getDeclaredField(name);field.setAccessible(true);return (T)field.get(login); } catch (Exception e) { throw new AssertionError(e); }
    }
    private void awaitExportButtons(LoginActivity page, String scenario) throws Exception {
        AtomicBoolean enabled = new AtomicBoolean();
        try {
            waitFor(() -> {
                runOnMainSync(() -> enabled.set(this.<Button>loginField(page, "cpa").isEnabled() && this.<Button>loginField(page, "sub").isEnabled()));
                return enabled.get();
            });
        } catch (AssertionError e) { throw new AssertionError("Both export buttons did not become available: " + scenario, e); }
    }
    private void selectExportDocument(LoginActivity page, boolean sub, File destination) {
        ActivityResult result;
        if (destination == null) result = new ActivityResult(Activity.RESULT_CANCELED, null);
        else {
            Uri uri = FileProvider.getUriForFile(getTargetContext(), getTargetContext().getPackageName() + ".qr-photos", destination);
            result = new ActivityResult(Activity.RESULT_OK, new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        }
        AtomicBoolean requested = new AtomicBoolean();
        ActivityMonitor picker = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())) return null;
                requested.set(true);
                return result;
            }
        };
        addMonitor(picker);
        try { main(() -> this.<Button>loginField(page, sub ? "sub" : "cpa").performClick()); }
        finally { removeMonitor(picker); }
        if (!requested.get()) throw new AssertionError("Clicking the export button did not open the system document picker.");
    }
    private LoginActivity rotateLogin(LoginActivity previous) {
        boolean toLandscape = previous.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
        int requested = toLandscape ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        int expected = toLandscape ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        ActivityMonitor next = addMonitor(LoginActivity.class.getName(), null, false);
        try {
            main(() -> previous.setRequestedOrientation(requested));
            LoginActivity rotated = (LoginActivity) waitForMonitorWithTimeout(next, 10000);
            check(rotated != null && rotated != previous && previous.isDestroyed() && rotated.getResources().getConfiguration().orientation == expected,
                    "login Activity replaced in the requested screen orientation");
            return rotated;
        } finally { removeMonitor(next); }
    }
    private void checkRotatedFile(File document, boolean sub, String step) throws Exception {
        Map<String,Object> json = LoginJson.map(LoginJson.decode(readUtf8(document)));
        Map<String,Object> credentials = json;
        if (sub) {
            List<?> accounts = (List<?>) json.get("accounts");
            check("sub2api-data".equals(json.get("type")) && accounts.size() == 1, step + " contains one sub2api account");
            Map<String,Object> account = LoginJson.map(accounts.get(0));
            check("anthropic".equals(account.get("platform")) && "oauth".equals(account.get("type")), step + " identifies the selected account platform");
            credentials = LoginJson.map(account.get("credentials"));
        } else {
            check("claude".equals(json.get("type")), step + " contains the selected CPA format");
        }
        check("rotation-account".equals(credentials.get("account_uuid")), step + " preserves the selected account UUID");
        check("alice@example.com".equals(credentials.get("email")) && "rotation-access".equals(credentials.get("access_token"))
                && "rotation-refresh".equals(credentials.get("refresh_token")) && "rotation-org".equals(credentials.get(sub ? "org_uuid" : "organization_uuid")),
                step + " preserves the synthetic session identity, tokens and organization");
    }
    private void checkExportNotice(LoginActivity rotated, boolean sub, String step) throws Exception {
        String expected = (sub ? "sub2api" : "CPA") + " 文件已生成，可继续生成另一种格式。";
        AtomicBoolean visible = new AtomicBoolean();
        try {
            waitFor(() -> {
                runOnMainSync(() -> visible.set(expected.contentEquals(this.<TextView>loginField(rotated, "status").getText())));
                return visible.get();
            });
        } catch (AssertionError e) { throw new AssertionError(step + " did not display its completion message on the rotated page", e); }
        check(visible.get(), step + " shows the completed format on the rotated page");
    }
    private void rotatingDocumentChecks(String order) throws Exception {
        if (!Arrays.asList("cpa-first", "sub2api-first", "both").contains(order)) throw new IllegalArgumentException("Unknown exportRotation");
        HistoryEntry entry = savedLoginRecord();
        LoginActivity current = (LoginActivity) startActivitySync(new Intent(getTargetContext(), LoginActivity.class).putExtra("record_id", entry.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        LoginFlow flow = new LoginFlow(repository, entry, OAuthLogin.Provider.CLAUDE, false);
        String tokens = LoginJson.encode(LoginJson.object("access_token", "rotation-access", "refresh_token", "rotation-refresh", "expires_in", 3600,
                "account", LoginJson.object("email_address", entry.account, "uuid", "rotation-account"), "organization", LoginJson.object("uuid", "rotation-org")));
        flow.credential = flow.login.parseTokens(tokens, System.currentTimeMillis());
        showLoginFlow(current, flow);
        File directory = new File(getTargetContext().getCacheDir(), "qr-photos");
        Files.createDirectories(directory.toPath());
        try {
            for (boolean firstSub : new boolean[]{false, true}) {
                String scenario = firstSub ? "sub2api-first" : "cpa-first";
                if (!"both".equals(order) && !scenario.equals(order)) continue;
                File first = File.createTempFile("rotation-first-", ".json", directory);
                File second = File.createTempFile("rotation-second-", ".json", directory);
                File unwritable = Files.createTempDirectory(directory.toPath(), "rotation-directory-").toFile();
                try (BlockedExportDocument blocked = new BlockedExportDocument(directory)) {
                    awaitExportButtons(current, "before " + scenario);
                    selectExportDocument(current, firstSub, blocked.pipe);
                    blocked.awaitWriter();
                    current = rotateLogin(current);
                    final LoginActivity rotated = current;
                    main(() -> check(!this.<Button>loginField(rotated, "cpa").isEnabled() && !this.<Button>loginField(rotated, "sub").isEnabled(),
                            scenario + " keeps exports disabled while the selected document is still being written"));
                    blocked.captureTo(first);
                    checkRotatedFile(first, firstSub, scenario + " first document written across rotation");
                    awaitExportButtons(current, scenario + " after the first complete JSON");
                    checkExportNotice(rotated, firstSub, scenario + " first export");
                    selectExportDocument(current, !firstSub, null);
                    awaitExportButtons(current, scenario + " after cancelling the document picker");
                    selectExportDocument(current, !firstSub, unwritable);
                    awaitExportButtons(current, scenario + " after an unwritable document");
                    main(() -> check(this.<TextView>loginField(rotated, "status").getText().toString().contains("写入失败"),
                            scenario + " displays a recoverable document write error"));
                    selectExportDocument(current, !firstSub, second);
                    awaitExportButtons(current, scenario + " after the second document");
                    checkRotatedFile(second, !firstSub, scenario + " second document written from the rotated page");
                    checkExportNotice(rotated, !firstSub, scenario + " second export");
                } finally {
                    Files.deleteIfExists(first.toPath());
                    Files.deleteIfExists(second.toPath());
                    Files.deleteIfExists(unwritable.toPath());
                }
            }
        } finally {
            main(current::finish);
            flow.close();
        }
    }
    private void showLoginFlow(LoginActivity page, LoginFlow flow) {
        main(() -> {
            try {
                java.lang.reflect.Field field = LoginActivity.class.getDeclaredField("flow");
                field.setAccessible(true);
                field.set(page, flow);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            LoginFlow.active = flow;
            flow.listener = page;
            page.updated();
        });
    }
    private void checkEndedLogin(LoginActivity page, LoginFlow flow, String message, String scenario) throws Exception {
        waitFor(() -> {
            AtomicBoolean detached = new AtomicBoolean();
            runOnMainSync(() -> detached.set(loginField(page, "flow") == null));
            return detached.get();
        });
        main(() -> {
            check(message.contentEquals(this.<TextView>loginField(page, "status").getText()), scenario + " displays its specific message");
            check(LoginFlow.active == null && flow.credential == null && flow.current() == null, scenario + " clears the login session");
            check(!this.<Button>loginField(page, "cpa").isEnabled() && !this.<Button>loginField(page, "sub").isEnabled(),
                    scenario + " disables both exports");
            check(this.<Button>loginField(page, "start").isEnabled() && this.<Spinner>loginField(page, "provider").isEnabled()
                    && !this.<Button>loginField(page, "finish").isEnabled(), scenario + " returns to the login choices");
        });
    }
    private static String sendLoopbackError(OAuthLogin login, String state, String error) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", login.port()), 3000);
            socket.setSoTimeout(3000);
            String target = URI.create(login.redirect()).getRawPath() + "?error=" + error + "&state=" + state + "&error_description=qa-private-detail";
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: localhost:" + login.port() + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            BufferedReader response = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = response.readLine();
            while (response.readLine() != null) { /* Drain the local response before closing the socket. */ }
            return status;
        }
    }
    private LoginActivity loginErrorChecks(LoginActivity initialPage) throws Exception {
        ActivityMonitor browser = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                return Intent.ACTION_VIEW.equals(intent.getAction()) ? new ActivityResult(Activity.RESULT_CANCELED, null) : null;
            }
        };
        addMonitor(browser);
        LoginActivity currentPage = initialPage;
        try {
            for (OAuthLogin.Provider provider : OAuthLogin.Provider.values()) {
                final LoginActivity page = currentPage;
                main(() -> {
                    this.<Spinner>loginField(page, "mode").setSelection(1);
                    this.<Spinner>loginField(page, "provider").setSelection(provider.ordinal());
                    this.<Button>loginField(page, "start").performClick();
                });
                LoginFlow flow = LoginFlow.active;
                check(flow != null && flow.login.provider == provider, "manual login starts a real loopback session " + provider);
                try {
                    main(() -> check("请在官方网页完成登录与授权。".contentEquals(this.<TextView>loginField(page, "status").getText()),
                            "new login presents its own authorization instructions " + provider));
                    if (provider == OAuthLogin.Provider.OPENAI) {
                        TextView status = loginField(page, "status");
                        main(() -> status.setText("等待定时刷新（测试）"));
                        waitFor(() -> {
                            AtomicBoolean refreshed = new AtomicBoolean();
                            runOnMainSync(() -> refreshed.set(flow.notice.contentEquals(status.getText())));
                            return refreshed.get();
                        });
                    }
                    check("HTTP/1.1 400 Bad Request".equals(sendLoopbackError(flow.login, "wrong-state", "access_denied")),
                            "foreign state is rejected by the loopback server " + provider);
                    check(LoginFlow.active == flow && flow.current() != null, "foreign denial leaves the current login active " + provider);
                    boolean denied = provider == OAuthLogin.Provider.OPENAI;
                    String error = denied ? "access_denied" : "server_error";
                    String message = denied ? "本次授权已被拒绝，请重新登录。" : "官方网页未授予授权，请重新登录。";
                    check("HTTP/1.1 200 OK".equals(sendLoopbackError(flow.login, flow.login.state, error)),
                            "current official error is accepted by the loopback server " + provider);
                    checkEndedLogin(page, flow, message, error);
                    CountDownLatch refreshed = new CountDownLatch(1);
                    main(() -> this.<TextView>loginField(page, "status").postDelayed(refreshed::countDown, 1200));
                    if (!refreshed.await(10, TimeUnit.SECONDS)) throw new AssertionError("login refresh did not run");
                    main(() -> check(message.contentEquals(this.<TextView>loginField(page, "status").getText()),
                            "timer refresh preserves the terminal message " + error));
                    if (denied) {
                        ActivityMonitor recreated = addMonitor(LoginActivity.class.getName(), null, false);
                        try {
                            main(page::recreate);
                            currentPage = (LoginActivity) waitForMonitorWithTimeout(recreated, 10000);
                            check(currentPage != null && currentPage != page && page.isDestroyed(), "Android recreated the denied login page");
                        } finally { removeMonitor(recreated); }
                        checkEndedLogin(currentPage, flow, message, "recreated denial");
                    }
                } finally { flow.close(); }
            }
            return currentPage;
        } finally { removeMonitor(browser); }
    }
    private HistoryEntry savedLoginRecord() throws Exception {
        clearRepository();CountDownLatch saved=new CountDownLatch(1);
        main(()->{try(AccountData d=AccountParser.parse("alice@example.com----SyntheticPassword----"+SECRET,null)){repository.record(d,true,ok->saved.countDown());}});
        check(saved.await(10,TimeUnit.SECONDS),"login test record saved in isolated QA storage");
        return repository.entries().get(0);
    }
    private void loginRecoveryChecks(String scenario) throws Exception {
        HistoryEntry entry = savedLoginRecord();
        switch (scenario) {
            case "cold" -> coldLoginChecks(entry);
            case "notice" -> lostDocumentNoticeChecks(entry);
            case "both" -> { coldLoginChecks(entry); lostDocumentNoticeChecks(entry); }
            default -> throw new IllegalArgumentException("Unknown loginRecovery scenario");
        }
    }
    private LoginActivity openLogin(HistoryEntry entry) {
        return (LoginActivity) startActivitySync(new Intent(getTargetContext(), LoginActivity.class)
                .putExtra("record_id", entry.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void legacyAutofillCopyCheck(HistoryEntry entry) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) throw new AssertionError("legacyAutofill requires an Android API 26 or 27 device");
        LoginActivity login = openLogin(entry);
        try {
            main(() -> {
                Spinner mode = loginField(login, "mode");
                mode.setSelection(0);
                Button start = loginField(login, "start");
                check(start.isEnabled() && "历史账号自动填充".equals(mode.getSelectedItem()), "legacy Android can select automatic login for a valid history record");
                start.performClick();
                TextView status = loginField(login, "status");
                check("此系统无法校验网页的 HTTPS 来源，请选择官方网页手动登录。".contentEquals(status.getText()),
                        "legacy automatic login displays the exact HTTPS-origin fallback instructions");
                check(LoginFlow.active == null && mode.isEnabled() && !this.<Button>loginField(login, "finish").isEnabled(),
                        "legacy fallback leaves manual mode available without starting authorization");
            });
        } finally { main(login::finish); waitFor(login::isDestroyed); }
    }
    private void coldLoginChecks(HistoryEntry entry) throws Exception {
        ManagerApplication app = (ManagerApplication) activity.getApplication();
        EncryptedHistoryStore store = new EncryptedHistoryStore(getTargetContext());
        HistoryRepository cold = new HistoryRepository(store);
        ActivityMonitor browser = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                return Intent.ACTION_VIEW.equals(intent.getAction()) ? new ActivityResult(Activity.RESULT_CANCELED, null) : null;
            }
        };
        addMonitor(browser);
        LoginActivity page = null;
        try {
            main(() -> app.history = cold);
            synchronized (store) {
                cold.load(); // The first real disk read cannot return until the cold UI has been checked.
                page = openLogin(entry);
                LoginActivity pending = page;
                main(() -> {
                    Button start = loginField(pending, "start");
                    check(!cold.loaded() && !start.isEnabled(), "cold login waits for the initial encrypted history read");
                    start.performClick();
                    check(LoginFlow.active == null && !this.<TextView>loginField(pending, "status").getText().toString().contains("未到期历史记录"),
                            "an early restart does not misreport an unloaded record as invalid");
                });
            }
            LoginActivity ready = page;
            waitFor(() -> {
                AtomicBoolean enabled = new AtomicBoolean();
                runOnMainSync(() -> enabled.set(cold.loaded() && this.<Button>loginField(ready, "start").isEnabled()));
                return enabled.get();
            });
            main(() -> {
                this.<Spinner>loginField(ready, "mode").setSelection(1);
                this.<Button>loginField(ready, "start").performClick();
                check(LoginFlow.active != null && LoginFlow.active.current() != null && entry.id.equals(LoginFlow.active.recordId),
                        "loaded history re-enables a fresh login with the original record");
            });
        } finally {
            removeMonitor(browser);
            main(() -> app.history = repository);
            if (page != null) { main(page::finish); waitFor(page::isDestroyed); }
        }
    }
    private void lostDocumentNoticeChecks(HistoryEntry entry) throws Exception {
        LoginActivity page = openLogin(entry);
        String notice = "登录结果已失效，未写入文件。";
        try {
            LoginActivity restored = page;
            main(() -> {
                check(LoginFlow.active == null, "restored login starts without the old export credentials");
                int staleRequest = new LoginFlow.ExportRequest(false).code;
                restored.onActivityResult(staleRequest, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://qa.invalid/document")));
                check(notice.contentEquals(this.<TextView>loginField(restored, "status").getText()), "a returned document with no live session displays its outcome");
            });
            page = rotateLogin(restored);
            LoginActivity rotated = page;
            main(() -> {
                check(notice.contentEquals(this.<TextView>loginField(rotated, "status").getText()), "lost document outcome survives subsequent screen rotation");
                check(LoginFlow.active == null && !this.<Button>loginField(rotated, "cpa").isEnabled() && !this.<Button>loginField(rotated, "sub").isEnabled(),
                        "rotation of a lost document outcome does not restore credentials or export buttons");
            });
        } finally { main(page::finish); waitFor(page::isDestroyed); }
    }
    private void loginChecks() throws Exception {
        HistoryEntry entry=savedLoginRecord();
        if (Build.VERSION.SDK_INT < 28) legacyAutofillCopyCheck(entry);
        LoginActivity login=(LoginActivity)startActivitySync(new Intent(getTargetContext(),LoginActivity.class).putExtra("record_id",entry.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(()->{
            login.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            check((login.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"login page remains capture protected");
            check(this.<Spinner>loginField(login,"provider").getCount()==2&&this.<Spinner>loginField(login,"mode").getCount()==2,"both providers and both modes on Android");
            check(!this.<Button>loginField(login,"cpa").isEnabled()&&!this.<Button>loginField(login,"sub").isEnabled(),"Android prevents export before authorization");
            check(!this.<EditText>loginField(login,"callback").isSaveEnabled(),"OAuth callback never saved in Activity state");
            this.<Spinner>loginField(login,"provider").setSelection(1);this.<Spinner>loginField(login,"mode").setSelection(1);
        });
        String syntheticTokens = "";
        for(OAuthLogin.Provider provider:OAuthLogin.Provider.values()){
            LoginFlow flow=new LoginFlow(repository,entry,provider,false);
            String idToken="header."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(LoginJson.encode(LoginJson.object("email","alice@example.com","https://api.openai.com/auth",LoginJson.object("chatgpt_account_id","acct-demo","chatgpt_plan_type","plus"))).getBytes(StandardCharsets.UTF_8))+".synthetic";
            String tokens=LoginJson.encode(provider==OAuthLogin.Provider.OPENAI?LoginJson.object("access_token","synthetic-access","refresh_token","synthetic-refresh","id_token",idToken,"expires_in",3600):LoginJson.object("access_token","synthetic-access","refresh_token","synthetic-refresh","expires_in",3600,"account",LoginJson.object("email_address","alice@example.com","uuid","acct-demo"),"organization",LoginJson.object("uuid","org-demo")));
            if (provider == OAuthLogin.Provider.OPENAI) syntheticTokens = tokens;
            flow.credential=flow.login.parseTokens(tokens,System.currentTimeMillis());
            showLoginFlow(login, flow);
            File exportFolder=new File(getTargetContext().getCacheDir(),"qr-photos");exportFolder.mkdirs();
            for(boolean sub2api:new boolean[]{false,true}){
                File exported=new File(exportFolder,"synthetic-login-"+provider+"-"+sub2api+".json");if(exported.exists())exported.delete();
                ActivityMonitor monitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(!Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction()))return null;Uri uri=FileProvider.getUriForFile(getTargetContext(),getTargetContext().getPackageName()+".qr-photos",exported);return new ActivityResult(Activity.RESULT_OK,new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}};
                addMonitor(monitor);
                try{main(()->this.<Button>loginField(login,sub2api?"sub":"cpa").performClick());waitFor(()->exported.length()>50);Map<String,Object> actual=LoginJson.map(LoginJson.decode(readUtf8(exported)));check(LoginJson.string(actual,"type").equals(sub2api?"sub2api-data":provider==OAuthLogin.Provider.OPENAI?"codex":"claude"),"actual document route writes login JSON "+provider+" "+sub2api);awaitExportButtons(login, "after " + provider + " export");}finally{removeMonitor(monitor);exported.delete();}
            }
            check(LoginJson.string(LoginJson.map(LoginJson.decode(flow.credential.export(false))),"type").equals(provider==OAuthLogin.Provider.OPENAI?"codex":"claude"),"device CPA export shape "+provider);
            check(LoginJson.string(LoginJson.map(LoginJson.decode(flow.credential.export(true))),"type").equals("sub2api-data"),"device sub2api export shape "+provider);
            check(flow.current()!=null,"active login bound to current history "+provider);flow.close();
            check(LoginFlow.active==null&&flow.credential==null&&flow.current()==null,"closing login clears credentials and staged autofill "+provider);
        }
        check(!LoginAutofillService.trusted(getTargetContext(),getTargetContext().getPackageName()),"autofill rejects unsigned lookalike app identity");
        LoginActivity resumed = loginErrorChecks(login);
        String invalid = "历史记录失效或登录超时，请重新开始。";
        LoginFlow expired = new LoginFlow(repository, entry, OAuthLogin.Provider.OPENAI, false);
        showLoginFlow(resumed, expired);
        main(() -> {
            this.<EditText>loginField(resumed, "callback").setText(expired.login.redirect() + "?error=access_denied&state=wrong-state");
            this.<Button>loginField(resumed, "finish").performClick();
        });
        main(() -> check(LoginFlow.active == expired && "回调不属于本次登录，请重新授权。".contentEquals(this.<TextView>loginField(resumed, "status").getText()),
                "a pasted foreign denial displays a recoverable validation error"));
        expired.credential = expired.login.parseTokens(syntheticTokens, System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1) - 1);
        checkEndedLogin(resumed, expired, invalid, "expired tokens");
        LoginFlow flow = new LoginFlow(repository, entry, OAuthLogin.Provider.OPENAI, true);
        flow.credential = flow.login.parseTokens(syntheticTokens, System.currentTimeMillis());
        showLoginFlow(resumed, flow);
        main(() -> check(this.<Button>loginField(resumed, "cpa").isEnabled() && this.<Button>loginField(resumed, "sub").isEnabled(),
                "both exports are available before deleting the authorized record"));
        CountDownLatch deleted=new CountDownLatch(1);main(()->repository.delete(entry.id,ok->deleted.countDown()));check(deleted.await(10,TimeUnit.SECONDS)&&flow.current()==null,"deleted record invalidates staged login");
        checkEndedLogin(resumed, flow, invalid, "deleted record");
        main(resumed::finish);
        waitFor(resumed::isDestroyed);
    }
    private void clearRepository() throws Exception {
        CountDownLatch latch = new CountDownLatch(1); boolean[] ok = {false};
        main(() -> repository.clear(success -> { ok[0] = success; latch.countDown(); }));
        if (!latch.await(10, TimeUnit.SECONDS) || !ok[0]) throw new AssertionError("cannot clear QA repository");
    }
    private TextView text(int id) { return activity.findViewById(id); }
    private static String readUtf8(File file) throws java.io.IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private void click(int id) { if (!activity.findViewById(id).performClick()) throw new AssertionError("click failed " + id); }
    private String clipboard() { return activity.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString(); }
    private void checkCurrentCode(String value, String label) {
        checkCode(value, OtpParameters.DEFAULT, label);
    }
    private void checkCode(String value, OtpParameters parameters, String label) {
        long now = Totp.now(); byte[] key = Base32.decode(SECRET);
        check(value.matches("[0-9]{" + parameters.digits + "}") && (value.equals(Totp.generate(key, now, parameters)) || value.equals(Totp.generate(key, now - 1, parameters))), label);
        Arrays.fill(key, (byte) 0);
    }
    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label); passed++;
        Bundle status = new Bundle(); status.putString("stream", "PASS " + label + "\n"); sendStatus(0, status);
    }
    private void main(Runnable action) {
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                if (activity != null && !activity.isDestroyed()) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                action.run();
            } catch (Throwable error) { failure[0] = error; }
        });
        waitForIdleSync();
        if (failure[0] != null) throw new AssertionError("QA main-thread check failed", failure[0]);
    }
    private void waitFor(BooleanSupplier condition) throws Exception {
        long until = SystemClock.elapsedRealtime() + 10000;
        while (!condition.getAsBoolean()) {
            if (SystemClock.elapsedRealtime() > until) throw new AssertionError("timed out waiting for state");
            SystemClock.sleep(40);
        }
    }
}
