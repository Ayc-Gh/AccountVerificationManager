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
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments); preview = arguments != null && "true".equals(arguments.getString("preview")); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(getTargetContext().getPackageName().endsWith(".qa"), "isolated QA application");
            storageChecks();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            repository = ((ManagerApplication) activity.getApplication()).history;
            waitFor(repository::loaded);
            clearRepository();
            uiChecks();
            pasteAndClearChecks();
            smartRecognitionChecks();
            qrImportChecks();
            historyNamingChecks();
            otpReplacementChecks();
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
            check(BuildConfig.VERSION_NAME.startsWith("1.8.0"), "Android synchronized version 1.8.0");
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
                    Uri output = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri.class); outputUri.set(output);
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
            dialog.findViewById(R.id.replace_otp_old_copy).performClick(); checkCurrentCode(clipboard(), "replacement copies current old OTP");
            check(activity.getSystemService(ClipboardManager.class).getPrimaryClipDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"), "replacement copied OTP is marked sensitive");
            ((EditText)dialog.findViewById(R.id.replace_otp_input)).setText("invalid-key");
            check(!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(), "invalid replacement disables saving");
            activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("synthetic replacement", nextSecret));
            dialog.findViewById(R.id.replace_otp_paste).performClick();
            check(((EditText)dialog.findViewById(R.id.replace_otp_input)).getText().toString().equals(nextSecret) && dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(), "paste new key previews replacement");
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
            waitFor(() -> repository.entries().size() == 1 && !repository.failed());
            HistoryEntry disk = new EncryptedHistoryStore(getTargetContext()).load().get(0);
            check(disk.secret.equals(nextSecret) && disk.otp.digits == 8 && disk.name.equals("工作邮箱"), "replacement survives encrypted reload and re-recognition");
        } finally { removeMonitor(monitor); image.delete(); }
        AtomicBoolean photoCalled = new AtomicBoolean();
        ActivityMonitor photo = new ActivityMonitor() { @Override public ActivityResult onStartActivity(Intent intent) {
            if (!MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction())) return null; photoCalled.set(true); return new ActivityResult(Activity.RESULT_CANCELED, null);
        }}; addMonitor(photo);
        try {
            main(() -> { click(R.id.replace_otp); replacementDialog().findViewById(R.id.replace_otp_photo).performClick(); });
            waitFor(photoCalled::get);
            main(() -> {
                check(replacementDialog().isShowing() && repository.entries().get(0).secret.equals(nextSecret), "cancelled replacement camera retains dialog and saved record");
                replacementDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
        } finally { removeMonitor(photo); }
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
    private void main(Runnable action) { runOnMainSync(action); waitForIdleSync(); }
    private void waitFor(BooleanSupplier condition) throws Exception {
        long until = SystemClock.elapsedRealtime() + 10000;
        while (!condition.getAsBoolean()) {
            if (SystemClock.elapsedRealtime() > until) throw new AssertionError("timed out waiting for state");
            SystemClock.sleep(40);
        }
    }
}
