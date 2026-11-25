package cn.local.accountmanager;

import android.app.*;
import android.content.*;
import android.content.ClipboardManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.io.*;
import cn.local.accountmanager.core.*;
import cn.local.accountmanager.storage.HistoryRepository;
import cn.local.accountmanager.qr.QrImportActivity;

public final class MainActivity extends Activity implements HistoryRepository.Listener {
    private static final int QR_IMPORT = 202;
    private static final int QR_REPLACE = 203;
    private static final int HISTORY_IMPORT = 204, HISTORY_EXPORT = 205;
    private byte[] pendingExport;
    private boolean transferBusy;
    private int background, surface, ink, muted, line, subtle, accent, tint, danger;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private HistoryRepository history;
    private LinearLayout root, historyList, manualBox;
    private ScrollView parsePage, historyPage;
    private TextView status, code, countdown, account, password, secret, issuer, otpBadge, otpIdentity, countLabel, historyNotice, copyUri;
    private TextView autoButton, manualButton, parseNav, historyNav, clearHistory, showPassword, detected;
    private EditText input, separator;
    private Track track;
    private final List<TextView> resultButtons = new ArrayList<>();
    private AccountData data;
    private AlertDialog namingDialog;
    private OtpReplacementEditor replacementEditor;
    private TextView replaceOtpButton, expiryButton;
    private RetentionPeriod newRetention;
    private boolean automatic = true, suppress, passwordVisible, historyVisible;
    private long lastStep = -1, editVersion;
    private String selectedId = "";
    private final Runnable tick = new Runnable() {
        @Override public void run() { ensureCurrentRecord(); updateCode(); timer.postDelayed(this, 200); }
    };
    private static final class Session {
        String input, separator, selected;
        RetentionPeriod newRetention;
        byte[] pendingExport;
        boolean auto, history;
        HistoryEntry result;
        OtpReplacementEditor.Draft replacement;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        PrivacyProtection.apply(this);
        history = ((ManagerApplication) getApplication()).history;
        palette();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getDecorView();
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(isDark() ? 0 :
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else getWindow().getDecorView().setSystemUiVisibility(isDark() ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        build();
        Session previous = (Session) getLastNonConfigurationInstance();
        if (previous != null) {
            suppress = true;
            automatic = previous.auto; input.setText(previous.input); separator.setText(previous.separator);
            pendingExport = previous.pendingExport; newRetention = previous.newRetention; refreshExpiryButton(); selectedId = previous.selected; suppress = false; renderMode();
            if (previous.result != null) { data = previous.result.open(); showResult(); }
            switchPage(previous.history);
        } else if (state != null) switchPage(state.getBoolean("history"));
        changed();
        if (previous != null && previous.replacement != null) openReplacement(previous.replacement);
    }

    private void palette() {
        boolean dark = isDark();
        background = Color.parseColor(dark ? "#11181D" : "#F4F6F8");
        surface = Color.parseColor(dark ? "#1B252B" : "#FFFFFF");
        ink = Color.parseColor(dark ? "#EFF5F5" : "#1D3037");
        muted = Color.parseColor(dark ? "#A5B5BD" : "#697D87");
        line = Color.parseColor(dark ? "#32424A" : "#E4EBEE");
        subtle = Color.parseColor(dark ? "#222F36" : "#F5F8F9");
        accent = Color.parseColor(dark ? "#76D6C3" : "#147D70");
        tint = Color.parseColor(dark ? "#213E3B" : "#E8F4F0");
        danger = Color.parseColor(dark ? "#FFB4AB" : "#B63E45");
    }
    private boolean isDark() { return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }

    private void build() {
        root = vertical(); root.setBackgroundColor(background); setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = horizontal(); header.setPadding(dp(20), dp(12), dp(12), dp(12));
        Glyph shield = new Glyph("shield", accent); header.addView(shield, size(36, 40));
        LinearLayout titles = vertical(); titles.setPadding(dp(10), 0, 0, 0);
        titles.addView(text("账号验证管家", 22, ink, true));
        TextView subtitle = text("离线解析  ·  登录按需联网", 11, muted, false);
        titles.addView(subtitle); header.addView(titles, weight());
        TextView help = button("?", subtle, ink, 18, this::showHelp); help.setContentDescription("使用指南与关于");
        header.addView(help, size(44, 44)); root.addView(header);
        FrameLayout pages = new FrameLayout(this); root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        parsePage = scroll(); historyPage = scroll(); pages.addView(parsePage); pages.addView(historyPage);
        LinearLayout content = vertical(); content.setPadding(dp(20), dp(6), dp(20), dp(24)); parsePage.addView(content);
        buildInput(content); buildOtp(content); buildDetails(content);
        TextView privacy = text("●  离线解析，历史本机加密；登录按需联网", 11, muted, false);
        privacy.setGravity(Gravity.CENTER); privacy.setPadding(0, dp(20), 0, dp(6)); content.addView(privacy);
        LinearLayout historyContent = vertical(); historyContent.setPadding(dp(20), dp(6), dp(20), dp(24)); historyPage.addView(historyContent);
        buildHistory(historyContent);
        LinearLayout nav = horizontal(); nav.setPadding(dp(20), dp(8), dp(20), dp(8)); nav.setBackgroundColor(surface);
        parseNav = navItem("智能识别", "scan", R.id.nav_parse, false);
        historyNav = navItem("历史记录", "history", R.id.nav_history, true);
        nav.addView(parseNav, new LinearLayout.LayoutParams(0, dp(52), 1));
        nav.addView(historyNav, new LinearLayout.LayoutParams(0, dp(52), 1)); root.addView(nav);
        resetResult(); renderMode(); switchPage(false);
    }

    private void buildInput(LinearLayout content) {
        LinearLayout card = card(); content.addView(card, spaced(0));
        LinearLayout heading = horizontal(); heading.addView(text("智能识别解析", 17, ink, true), weight());
        TextView clear = button("清空", surface, muted, 13, () -> {
            newRetention = null; refreshExpiryButton();
            input.setText(""); invalidateResult("已清空当前内容，历史记录仍保留。"); hideKeyboard();
        }); clear.setId(R.id.clear_input); heading.addView(clear, size(60, 44)); card.addView(heading);
        input = new EditText(this); input.setId(R.id.input); input.setTextSize(14); input.setTextColor(ink); input.setHintTextColor(muted);
        input.setHint("粘贴账号记录、纯 2FA 密钥\n或 otpauth://totp/… 链接");
        input.setGravity(Gravity.TOP | Gravity.START); input.setMinLines(3); input.setMaxLines(6);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setPadding(dp(14), dp(14), dp(14), dp(14)); input.setBackground(shape(subtle, 12, line));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AccountParser.MAX_INPUT)});
        input.setSaveEnabled(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setContentDescription("待识别的账号记录、2FA 密钥或 Authenticator URI"); input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        card.addView(input, spaced(8));
        LinearLayout modes = horizontal(); modes.setPadding(dp(4), dp(4), dp(4), dp(4)); modes.setBackground(shape(subtle, 12, 0));
        autoButton = button("智能识别", surface, accent, 13, () -> setMode(true)); autoButton.setId(R.id.auto_mode);
        manualButton = button("手动分隔", subtle, muted, 13, () -> setMode(false)); manualButton.setId(R.id.manual_mode);
        modes.addView(autoButton, new LinearLayout.LayoutParams(0, dp(44), 1)); modes.addView(manualButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        card.addView(modes, spaced(12));
        manualBox = vertical();
        manualBox.addView(label("分隔符（按原样匹配，可用单个字符）"));
        separator = new EditText(this); separator.setId(R.id.manual_separator); separator.setSingleLine(true); separator.setText("----");
        separator.setTextSize(14); separator.setTextColor(ink); separator.setBackground(shape(subtle, 10, line));
        separator.setPadding(dp(12), dp(8), dp(12), dp(8)); separator.setSaveEnabled(false);
        separator.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        separator.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)}); separator.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        manualBox.addView(separator, new LinearLayout.LayoutParams(-1, dp(48))); card.addView(manualBox, spaced(4));
        detected = text("连续 3 个及以上符号，如 ---、===、|||", 11, muted, false); detected.setPadding(0, dp(8), 0, dp(2)); card.addView(detected);
        LinearLayout actions = horizontal();
        TextView paste = button("粘贴并解析", tint, accent, 14, this::pasteAndParse); paste.setId(R.id.paste);
        expiryButton = button(expiryButtonText(), tint, accent, 12, () -> ExpirationEditor.show(this, newRetention, 0, false, value -> { newRetention = value; refreshExpiryButton(); }));
        expiryButton.setTag("new-expiration"); card.addView(expiryButton, spaced(8));
        TextView parse = button("识别并生成", accent, isDark() ? Color.parseColor("#102C26") : Color.WHITE, 14, this::parseInput); parse.setId(R.id.parse);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(50), 1); a.setMarginEnd(dp(8)); actions.addView(paste, a);
        actions.addView(parse, new LinearLayout.LayoutParams(0, dp(50), 1)); card.addView(actions, spaced(12));
        LinearLayout qrActions = horizontal();
        TextView photo = button("拍照识别二维码", subtle, accent, 13, () -> openQr(QrImportActivity.PHOTO)); photo.setId(R.id.qr_photo);
        TextView upload = button("上传二维码图片", subtle, accent, 13, () -> openQr(QrImportActivity.UPLOAD)); upload.setId(R.id.qr_upload);
        LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, dp(48), 1); q.setMarginEnd(dp(8)); qrActions.addView(photo, q);
        qrActions.addView(upload, new LinearLayout.LayoutParams(0, dp(48), 1)); card.addView(qrActions, spaced(8));
        status = text("自动识别输入类型，按密钥或 URI 参数生成验证码", 11, muted, false); status.setId(R.id.status);
        status.setPadding(0, dp(10), 0, 0); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); card.addView(status);
        input.addTextChangedListener(watcher(() -> { if (!suppress) invalidateResult("内容已更改，请重新解析。"); }));
        separator.addTextChangedListener(watcher(() -> { if (!suppress && !automatic) invalidateResult("分隔符已更改，请重新解析。"); }));
        input.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.isCtrlPressed() && event.getAction() == KeyEvent.ACTION_DOWN) { parseInput(); return true; }
            return false;
        });
    }

    private void buildOtp(LinearLayout content) {
        LinearLayout card = vertical(); card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.parseColor("#193C46"), Color.parseColor("#102B34")}));
        ((GradientDrawable) card.getBackground()).setCornerRadius(dp(20)); content.addView(card, spaced(16));
        LinearLayout top = horizontal(); TextView title = text("2FA 动态验证码", 14, Color.parseColor("#D3E8E8"), true); top.addView(title, weight());
        replaceOtpButton = button("更换 2FA", Color.parseColor("#2B4F58"), Color.WHITE, 12, this::promptReplacement); replaceOtpButton.setId(R.id.replace_otp);
        top.addView(replaceOtpButton, size(90, 40));
        card.addView(top);
        otpIdentity = text("等待智能识别", 11, Color.parseColor("#D3E8E8"), false); otpIdentity.setMaxLines(2); otpIdentity.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(otpIdentity, spaced(8));
        otpBadge = text("SHA1 · 6 位 · 30 秒", 11, Color.parseColor("#A6C8CA"), false); otpBadge.setId(R.id.otp_parameters); card.addView(otpBadge, spaced(6));
        code = text("--- ---", 42, Color.WHITE, true); code.setId(R.id.code); code.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        code.setLetterSpacing(0.09f); code.setGravity(Gravity.CENTER); code.setPadding(0, dp(12), 0, dp(4));
        code.setAutoSizeTextTypeUniformWithConfiguration(26, 42, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        card.addView(code, new LinearLayout.LayoutParams(-1, dp(80)));
        countdown = text("解析后自动生成", 12, Color.parseColor("#A6C8CA"), false); countdown.setId(R.id.countdown);
        countdown.setGravity(Gravity.CENTER); card.addView(countdown);
        track = new Track(); card.addView(track, new LinearLayout.LayoutParams(-1, dp(22)));
        TextView copy = resultButton("复制验证码", R.id.copy_code, () -> data.code(Totp.now()), "验证码", Color.parseColor("#2B4F58"), Color.WHITE);
        card.addView(copy, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void buildDetails(LinearLayout content) {
        LinearLayout card = card(); content.addView(card, spaced(16));
        LinearLayout heading = horizontal(); heading.addView(text("识别结果", 17, ink, true), weight());
        TextView all = resultButton("复制全部", R.id.copy_all, () -> data.copyAll(Totp.now()), "全部结果", tint, accent);
        heading.addView(all, size(88, 44)); card.addView(heading);
        issuer = field(card, "发行方", R.id.issuer, R.id.copy_issuer, () -> data.issuer, false);
        account = field(card, "账号", R.id.account, R.id.copy_account, () -> data.account, false);
        password = field(card, "密码", R.id.password, R.id.copy_password, () -> data.password, true);
        secret = field(card, "2FA 密钥", R.id.secret, R.id.copy_secret, () -> data.secret, false);
        copyUri = resultButton("复制 Authenticator URI", R.id.copy_uri, () -> data.originalText(), "Authenticator URI", tint, accent);
        card.addView(copyUri, spaced(8));
    }

    private TextView field(LinearLayout card, String title, int valueId, int copyId, Value value, boolean passwordField) {
        LinearLayout group = vertical();
        View divider = new View(this); divider.setBackgroundColor(line); group.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = horizontal(); row.setPadding(0, dp(12), 0, dp(12));
        LinearLayout left = vertical(); left.addView(text(title, 11, muted, false));
        TextView v = text("等待解析", 14, ink, false); v.setId(valueId); v.setSaveEnabled(false);
        v.setPadding(0, dp(5), dp(6), 0); v.setMaxLines(passwordField ? 1 : 3); v.setEllipsize(TextUtils.TruncateAt.END);
        v.setTextDirection(View.TEXT_DIRECTION_LTR); left.addView(v); row.addView(left, weight());
        if (passwordField) {
            showPassword = button("显示", surface, muted, 12, () -> { passwordVisible = !passwordVisible; renderPassword(); });
            showPassword.setId(R.id.show_password); row.addView(showPassword, size(48, 48));
        }
        TextView copy = resultButton("复制", copyId, value, title, tint, accent); copy.setContentDescription("复制" + title);
        row.addView(copy, size(60, 48)); group.addView(row); card.addView(group); return v;
    }

    private void buildHistory(LinearLayout content) {
        LinearLayout heading = horizontal(); heading.addView(text("最近识别", 26, ink, true), weight());
        clearHistory = button("清空历史", surface, danger, 12, this::confirmClearHistory); clearHistory.setId(R.id.clear_history);
        heading.addView(clearHistory, size(88, 48)); content.addView(heading);
        countLabel = text("0 / 10 条 · 点击记录恢复", 12, muted, false); countLabel.setPadding(0, dp(8), 0, dp(12)); content.addView(countLabel);
        LinearLayout transfer = horizontal();
        TextView importButton = button("加密导入", tint, accent, 13, () -> {
            if (transferBusy) { setStatus("正在处理文件，请稍候。", false); return; }
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), HISTORY_IMPORT);
        }); importButton.setTag("history-import");
        TextView exportButton = button("多选导出", tint, accent, 13, this::exportHistory); exportButton.setTag("history-export");
        transfer.addView(importButton, new LinearLayout.LayoutParams(0, dp(48), 1)); transfer.addView(exportButton, new LinearLayout.LayoutParams(0, dp(48), 1)); content.addView(transfer);
        historyNotice = text("", 12, danger, false); historyNotice.setPadding(dp(12), dp(12), dp(12), dp(12));
        historyNotice.setBackground(shape(tint, 12, 0)); content.addView(historyNotice, spaced(0));
        historyList = vertical(); historyList.setId(R.id.history_list); content.addView(historyList);
        TextView note = text("最多保留 10 条，置顶优先保留，到期自动删除。\n密码加密文件包含账号、密码、2FA 链接及历史设置。", 11, muted, false);
        note.setPadding(0, dp(20), 0, dp(10)); note.setGravity(Gravity.CENTER); content.addView(note);
    }

    @Override public void changed() {
        if (historyList == null || isDestroyed()) return;
        ensureCurrentRecord();
        List<HistoryEntry> entries = history.entries();
        replaceOtpButton.setEnabled(data != null && entries.stream().anyMatch(e -> e.id.equals(selectedId)));
        replaceOtpButton.setAlpha(replaceOtpButton.isEnabled() ? 1f : .4f);
        countLabel.setText(getString(R.string.history_count, entries.size()));
        historyNav.setText(entries.isEmpty() ? getString(R.string.history_nav) : getString(R.string.history_nav_count, entries.size()));
        historyNotice.setVisibility(history.failed() ? View.VISIBLE : View.GONE);
        historyNotice.setTextColor(danger);
        historyNotice.setText("历史记录读取或保存失败，原文件已保留。可重试，或明确清空历史后重新保存。");
        clearHistory.setEnabled(history.loaded()); clearHistory.setAlpha(history.loaded() ? 1f : .45f);
        historyList.removeAllViews();
        if (entries.isEmpty()) {
            LinearLayout empty = card(); empty.setPadding(dp(24), dp(48), dp(24), dp(48));
            Glyph glyph = new Glyph("history", accent); LinearLayout.LayoutParams gp = size(48, 48); gp.gravity = Gravity.CENTER; empty.addView(glyph, gp);
            TextView title = text(history.loaded() ? "还没有识别记录" : "正在读取历史…", 17, ink, true); title.setGravity(Gravity.CENTER); empty.addView(title, spaced(16));
            TextView desc = text("成功识别后会自动保存\n下次打开，依然可以快速复制", 12, muted, false); desc.setGravity(Gravity.CENTER); empty.addView(desc, spaced(10));
            historyList.addView(empty, spaced(8));
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (HistoryEntry e : entries) {
            LinearLayout card = card(); card.setBackground(shape(surface, 18, e.id.equals(selectedId) ? accent : line));
            card.setTag("entry:" + e.id);
            LinearLayout titleRow = horizontal();
            TextView title = text(e.displayName(), 15, ink, true); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, dp(8), dp(8), dp(8)); title.setMinHeight(dp(44)); title.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.addView(title, weight());
            TextView rename = button(e.name.isEmpty() ? "命名" : "改名", surface, accent, 12, () -> nameHistory(e));
            rename.setTag("history-name:" + e.id); rename.setContentDescription("命名历史记录 " + e.displayName());
            titleRow.addView(rename, size(56, 44)); card.addView(titleRow);
            if (!e.name.isEmpty()) {
                TextView identity = text(e.defaultDisplayName(), 12, muted, false); identity.setMaxLines(2); identity.setEllipsize(TextUtils.TruncateAt.END);
                identity.setPadding(0, 0, 0, dp(6)); identity.setOnClickListener(v -> restore(e)); card.addView(identity);
            }
            TextView meta = text(format.format(new Date(e.recognizedAt)) + "  ·  " + (e.kind == AccountData.Kind.RECORD ? (e.automatic ? "账号记录" : "手动分隔") : e.typeDescription()) + "  ·  已加密", 11, muted, false);
            meta.setPadding(0, dp(2), 0, dp(12)); card.addView(meta);
            card.setOnClickListener(v -> restore(e)); title.setOnClickListener(v -> restore(e)); meta.setOnClickListener(v -> restore(e));
            title.setContentDescription("恢复历史记录 " + e.displayName()); title.setFocusable(true);
            LinearLayout buttons = horizontal();
            int[] kinds = e.kind == AccountData.Kind.RECORD ? new int[]{0, 1, 2, 3} : e.account.isEmpty() ? new int[]{4, 2, 3} : new int[]{0, 4, 2, 3};
            String[] labels = {"账号", "密码", "验证码", "整组", "密钥"};
            for (int i = 0; i < kinds.length; i++) {
                final int kind = kinds[i];
                TextView b = button(labels[kind], tint, accent, 12, null);
                b.setTag("history-copy-" + kind + ":" + e.account);
                b.setContentDescription("历史记录 " + e.displayName() + " 复制" + labels[kind]);
                b.setOnClickListener(v -> {
                    HistoryEntry current = history.find(e.id);
                    if (current == null) { changed(); setStatus("记录已到期或被移除。", true); return; }
                    copy(kind == 0 ? current.account : kind == 1 ? current.password : kind == 2 ? current.currentCode(Totp.now()) : kind == 4 ? current.secret : current.copyGroup(Totp.now()), labels[kind], b);
                });
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(48), 1); if (i < kinds.length - 1) bp.setMarginEnd(dp(6)); buttons.addView(b, bp);
            }
            card.addView(buttons);
            TextView manage = button((e.pinned ? "已置顶 · " : "") + (e.expiresAt == 0 ? "永久保留" : "到期 " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(e.expiresAt))) + " · 管理", surface, accent, 12, () -> manageHistory(e.id));
            manage.setTag("history-manage:" + e.id); card.addView(manage, spaced(6)); historyList.addView(card, spaced(12));
        }
    }

    private void exportHistory() {
        if (transferBusy) { setStatus("正在处理文件，请稍候。", false); return; }
        TransferDialogs.select(this, "选择导出的记录", history.entries(), chosen -> TransferDialogs.password(this, true, password -> {
            List<HistoryEntry> current = new ArrayList<>();
            for (HistoryEntry e : chosen) { HistoryEntry found = history.find(e.id); if (found == null) { setStatus("所选记录已到期或被删除，请重新选择。", true); return; } current.add(found); }
            transferBusy = true; setStatus("正在加密导出…", false);
            new Thread(() -> {
                byte[] result = null; String error = null;
                try { result = HistoryTransfer.encrypt(current, password); } catch (Exception e) { error = "加密导出失败，请确认所选记录尚未到期。"; }
                final byte[] encrypted = result; final String message = error;
                runOnUiThread(() -> {
                    transferBusy = false; if (isDestroyed()) return;
                    if (message != null) { setStatus(message, true); return; }
                    pendingExport = encrypted;
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "账号验证管家-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date()) + ".avmx"), HISTORY_EXPORT);
                });
            }, "history-encrypt").start();
        }));
    }
    private void importHistory(android.net.Uri uri, String password) {
        if (transferBusy) return; transferBusy = true; setStatus("正在解密文件…", false);
        new Thread(() -> {
            List<HistoryEntry> result = null; String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (in == null) throw new IOException("无法读取文件。"); byte[] buffer = new byte[4096]; int count;
                while ((count = in.read(buffer)) != -1) { if (bytes.size() + count > HistoryTransfer.MAX_BYTES) throw new IOException("文件超过 2 MB。"); bytes.write(buffer, 0, count); }
                result = HistoryTransfer.decrypt(bytes.toByteArray(), password);
            } catch (Exception e) { error = e instanceof IOException || e instanceof IllegalArgumentException ? e.getMessage() : "密码错误或文件已损坏。"; }
            final List<HistoryEntry> decoded = result; final String message = error;
            runOnUiThread(() -> {
                transferBusy = false; if (isDestroyed()) return;
                if (decoded == null) { setStatus(message == null ? "导入失败，请重试。" : message, true); return; }
                TransferDialogs.select(this, "选择导入的记录", decoded, chosen -> {
                    try { HistoryModel.importEntries(history.entries(), chosen, System.currentTimeMillis()); }
                    catch (IllegalArgumentException e) { setStatus(e.getMessage(), true); return; }
                    int before = history.entries().size();
                    history.importEntries(chosen, success -> { if (isDestroyed()) return; changed(); setStatus(success ? "已导入 " + Math.max(0, history.entries().size() - before) + " 条；重复或到期记录已跳过。" : "导入失败，现有历史已保留。请检查容量后重试。", !success); });
                });
            });
        }, "history-decrypt").start();
    }

    private String expiryButtonText() {
        String retention = newRetention == null ? getString(R.string.history_retention_permanent) : newRetention.label();
        return getString(R.string.history_retention_first_save, retention);
    }
    private void refreshExpiryButton() {
        if (expiryButton != null) expiryButton.setText(expiryButtonText());
    }
    private boolean ensureCurrentRecord() {
        if (!selectedId.isEmpty() && history.loaded() && history.find(selectedId) == null) {
            editVersion++; selectedId = ""; resetResult(); input.setText("");
            if (replacementEditor != null) { replacementEditor.close(); replacementEditor = null; }
            setStatus("当前记录已到期或被删除，内容已清除。", true); return false;
        }
        return true;
    }
    private void managementResult(boolean success) {
        if (isDestroyed()) return;
        changed(); setStatus(success ? "历史记录已更新。" : "保存失败，原历史已保留。请刷新后重试。", !success);
    }
    private void manageHistory(String id) {
        HistoryEntry e = history.find(id); if (e == null) { changed(); return; }
        PrivacyProtection.show(new AlertDialog.Builder(this).setTitle(e.displayName())
                .setItems(new String[]{"管理到期时间", e.pinned ? "取消置顶" : "置顶", "删除记录", "登录并生成 CPA / sub2api"}, (dialog, which) -> {
                    if (which == 0) ExpirationEditor.show(this, null, e.expiresAt, true, value -> history.manage(id, value == null ? 0 : value.expiresAt(System.currentTimeMillis()), null, this::managementResult));
                    if (which == 1) history.manage(id, null, !e.pinned, this::managementResult);
                    if (which == 2) PrivacyProtection.show(new AlertDialog.Builder(this).setTitle("删除记录？")
                            .setMessage("将删除这条记录及其账号、密码和 2FA 密钥。")
                            .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> history.delete(id, this::managementResult)));
                    if (which == 3) startActivity(new Intent(this, LoginActivity.class).putExtra("record_id", id));
                }).setNegativeButton("取消", null));
    }

    private void setMode(boolean auto) {
        if (automatic == auto) return;
        automatic = auto; renderMode(); invalidateResult("分隔模式已更改，请重新解析。");
    }

    private void nameHistory(HistoryEntry entry) {
        if (namingDialog != null && namingDialog.isShowing()) return;
        LinearLayout content = vertical(); content.setPadding(dp(24), dp(8), dp(24), 0);
        TextView identity = text(entry.defaultDisplayName(), 12, muted, false); content.addView(identity);
        EditText name = new EditText(this); name.setId(R.id.history_name_input); name.setSingleLine(true);
        name.setText(entry.name); name.setHint("留空可恢复默认名称"); name.setContentDescription("历史记录名称");
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(HistoryEntry.NAME_LIMIT)});
        name.setSaveEnabled(false); name.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        content.addView(name, new LinearLayout.LayoutParams(-1, dp(54)));
        TextView hint = text("最多 40 个字符；名称与历史一起在本机加密保存。", 11, muted, false); content.addView(hint);
        AlertDialog dialog = PrivacyProtection.show(new AlertDialog.Builder(this).setTitle("给历史记录命名")
                .setView(content).setNegativeButton("取消", null).setPositiveButton("保存", null));
        namingDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (namingDialog == dialog) namingDialog = null; });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String value;
            try { value = HistoryEntry.normalizeName(name.getText().toString()); }
            catch (IllegalArgumentException error) { name.setError(error.getMessage()); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false); dialog.setCancelable(false);
            history.rename(entry.id, value, success -> {
                if (isDestroyed()) return;
                if (success) { dialog.dismiss(); setStatus(value.isEmpty() ? "已恢复默认名称。" : "已保存记录名称。", false); }
                else {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true); dialog.setCancelable(true);
                    name.setError("名称保存失败，记录可能已被移除，请重试。");
                }
            });
        });
        name.requestFocus(); name.setSelection(name.length());
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    private void renderMode() {
        autoButton.setBackground(ripple(automatic ? surface : subtle, 9)); autoButton.setTextColor(automatic ? accent : muted); autoButton.setSelected(automatic);
        manualButton.setBackground(ripple(automatic ? subtle : surface, 9)); manualButton.setTextColor(automatic ? muted : accent); manualButton.setSelected(!automatic);
        manualBox.setVisibility(automatic ? View.GONE : View.VISIBLE);
        detected.setText(automatic ? "自动区分账号记录、纯密钥和 Authenticator URI" : "账号记录按分隔符拆分；纯密钥和 URI 仍会智能识别");
    }
    private void invalidateResult(String message) { editVersion++; resetResult(); setStatus(message, false); }
    private void resetResult() {
        if (data != null) { data.close(); data = null; }
        selectedId = ""; if (replaceOtpButton != null) { replaceOtpButton.setEnabled(false); replaceOtpButton.setAlpha(.4f); }
        lastStep = -1; passwordVisible = false;
        if (account == null) return;
        account.setText("等待解析"); password.setText("等待解析"); secret.setText("等待解析"); issuer.setText("");
        fieldVisible(issuer, false); fieldVisible(account, false); fieldVisible(password, false); copyUri.setVisibility(View.GONE);
        otpBadge.setText(OtpParameters.DEFAULT.description()); otpIdentity.setText("等待智能识别");
        password.setTransformationMethod(null); showPassword.setText("显示"); showPassword.setEnabled(false);
        code.setText("--- ---"); code.setContentDescription("当前验证码，等待解析"); countdown.setText("解析后自动生成");
        track.fraction = 0; track.invalidate();
        for (TextView b : resultButtons) { b.setEnabled(false); b.setAlpha(.4f); }
    }
    private void showResult() {
        account.setText(data.account); password.setText(data.password); secret.setText(data.secret); issuer.setText(data.issuer);
        fieldVisible(issuer, !data.issuer.isEmpty()); fieldVisible(account, !data.account.isEmpty()); fieldVisible(password, !data.password.isEmpty());
        copyUri.setVisibility(data.kind == AccountData.Kind.URI ? View.VISIBLE : View.GONE);
        otpBadge.setText(data.otp.description()); otpIdentity.setText(data.displayName());
        showPassword.setEnabled(true); passwordVisible = false; renderPassword();
        for (TextView b : resultButtons) { b.setEnabled(true); b.setAlpha(1f); }
        findViewById(R.id.copy_account).setEnabled(!data.account.isEmpty()); findViewById(R.id.copy_password).setEnabled(!data.password.isEmpty());
        findViewById(R.id.copy_issuer).setEnabled(!data.issuer.isEmpty());
        lastStep = -1; updateCode();
    }
    private void fieldVisible(TextView field, boolean visible) {
        View group = (View) field.getParent().getParent().getParent();
        group.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
    private void renderPassword() {
        if (data == null) return;
        password.setTransformationMethod(passwordVisible ? null : PasswordTransformationMethod.getInstance());
        showPassword.setText(passwordVisible ? "隐藏" : "显示"); showPassword.setContentDescription(passwordVisible ? "隐藏密码" : "显示密码");
    }
    private void updateCode() {
        if (data == null) return;
        long now = Totp.now();
        int period = data.otp.period;
        if (now / period != lastStep) {
            String value = data.code(now); int split = value.length() / 2;
            code.setText(getString(R.string.otp_grouped, value.substring(0, split), value.substring(split)));
            code.setContentDescription("当前验证码 " + value); lastStep = now / period;
        }
        int remaining = period - (int) (now % period);
        countdown.setText(getString(R.string.otp_countdown, remaining)); countdown.setTextColor(Color.parseColor(remaining <= 5 ? "#FFD292" : "#A6C8CA"));
        track.fraction = remaining / (float) period; track.warning = remaining <= 5; track.invalidate();
    }
    private void parseInput() {
        editVersion++; final long version = editVersion; resetResult();
        try {
            data = SmartParser.parse(input.getText().toString(), automatic ? null : separator.getText().toString());
            showResult(); hideKeyboard();
            setStatus("已识别：" + data.typeDescription() + (data.kind == AccountData.Kind.RECORD ? " · 分隔符 " + preview(data.firstSeparator)
                    + (data.firstSeparator.equals(data.lastSeparator) ? "" : " / " + preview(data.lastSeparator)) : " · " + data.otp.description()), false);
            boolean duplicate = history.entries().stream().anyMatch(e -> HistoryModel.matches(e, data));
            if (!duplicate) {
                if (newRetention != null) newRetention.expiresAt(System.currentTimeMillis());
                if (history.entries().size() >= HistoryModel.LIMIT && history.entries().stream().allMatch(e -> e.pinned)) {
                    setStatus("解析成功；十条历史均已置顶，请先取消置顶或删除一条再保存。", true); return;
                }
            }
            history.recordWithRetention(data, automatic, newRetention, success -> {
                if (isDestroyed() || editVersion != version) return;
                if (success) { for (HistoryEntry e : history.entries()) if (HistoryModel.matches(e, data)) { selectedId = e.id; break; } newRetention = null; refreshExpiryButton(); changed(); }
                else setStatus("解析成功，但历史保存失败。原文件已保留，请前往历史页查看。", true);
            });
        } catch (IllegalArgumentException e) { setStatus(e.getMessage(), true); }
    }
    private void pasteAndParse() {
        try {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) { setStatus("剪贴板没有文本，请复制账号记录、密钥或 URI。", true); return; }
            String value = clip.getItemAt(0).getText().toString();
            if (value.length() > AccountParser.MAX_INPUT) { setStatus("内容过长，请每次只粘贴一条账号记录。", true); return; }
            input.setText(value); parseInput();
        } catch (SecurityException e) { setStatus("暂时无法读取剪贴板，请手动粘贴后解析。", true); }
    }
    private void openQr(String mode) {
        hideKeyboard();
        startActivityForResult(new Intent(this, QrImportActivity.class).putExtra(QrImportActivity.MODE, mode), QR_IMPORT);
    }
    private void promptReplacement() {
        for (HistoryEntry entry : history.entries()) if (entry.id.equals(selectedId)) { openReplacement(new OtpReplacementEditor.Draft(entry)); return; }
        setStatus("请先识别或打开一条已保存的历史记录。", true);
    }
    private void openReplacement(OtpReplacementEditor.Draft draft) {
        if (replacementEditor != null && replacementEditor.snapshot() != null) return;
        replacementEditor = new OtpReplacementEditor(this, draft, history, mode -> {
            hideKeyboard(); startActivityForResult(new Intent(this, QrImportActivity.class).putExtra(QrImportActivity.MODE, mode), QR_REPLACE);
        }, () -> {
            for (HistoryEntry entry : history.entries()) if (entry.id.equals(draft.expected.id)) {
                restore(entry); setStatus("2FA 已更换，账号、密码和名称已保留。", false); break;
            }
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == HISTORY_IMPORT) {
            if (result == RESULT_OK && intent != null && intent.getData() != null) {
                android.net.Uri uri = intent.getData();
                TransferDialogs.password(this, false, password -> importHistory(uri, password));
            } return;
        }
        if (request == HISTORY_EXPORT) {
            byte[] encrypted = pendingExport; pendingExport = null;
            if (result == RESULT_OK && intent != null && intent.getData() != null && encrypted != null) {
                android.net.Uri uri = intent.getData(); transferBusy = true;
                new Thread(() -> {
                    String error = null;
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) { if (out == null) throw new IOException(); out.write(encrypted); out.flush(); }
                    catch (Exception e) { error = "导出文件写入失败，请重试。"; }
                    final String message = error; runOnUiThread(() -> { transferBusy = false; if (!isDestroyed()) setStatus(message == null ? "加密文件已导出。" : message, message != null); });
                }, "history-export-write").start();
            } return;
        }
        if (request == QR_REPLACE) {
            if (replacementEditor == null || intent == null) return;
            if (result == RESULT_OK) {
                String text = intent.getStringExtra(QrImportActivity.TEXT);
                if (text == null || text.length() > AccountParser.MAX_INPUT) replacementEditor.error("二维码内容过长或无效。");
                else replacementEditor.acceptQr(text);
            } else if (intent.hasExtra(QrImportActivity.ERROR)) replacementEditor.error(intent.getStringExtra(QrImportActivity.ERROR));
            return;
        }
        if (request != QR_IMPORT || intent == null) return;
        if (result == RESULT_OK) {
            String text = intent.getStringExtra(QrImportActivity.TEXT);
            if (text == null || text.length() > AccountParser.MAX_INPUT) { invalidateResult("二维码内容过长或无效。"); return; }
            automatic = true; renderMode(); input.setText(text); switchPage(false); parseInput();
            parsePage.smoothScrollTo(0, 0);
        } else if (intent.hasExtra(QrImportActivity.ERROR)) {
            invalidateResult(intent.getStringExtra(QrImportActivity.ERROR));
        }
    }
    private void restore(HistoryEntry e) {
        e = history.find(e.id);
        if (e == null) { changed(); setStatus("记录已到期或被移除。", true); return; }
        editVersion++; resetResult(); suppress = true; automatic = e.automatic;
        if (e.kind == AccountData.Kind.RECORD) separator.setText(e.firstSeparator);
        input.setText(e.originalText()); suppress = false;
        renderMode(); data = e.open(); selectedId = e.id; showResult(); changed(); switchPage(false);
        parsePage.smoothScrollTo(0, 0); hideKeyboard(); setStatus("已恢复历史记录，可直接复制当前验证码。", false);
    }
    private void copy(String value, String label, TextView source) {
        try {
            ClipData clip = ClipData.newPlainText("账号验证管家", value);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            getSystemService(ClipboardManager.class).setPrimaryClip(clip);
            CharSequence old = (CharSequence) source.getTag(R.id.copy_all); source.setText("已复制");
            source.postDelayed(() -> source.setText(old), 1100);
            setStatus(label + "已复制", false);
        } catch (SecurityException e) { setStatus("复制失败，请重试。", true); }
    }
    private void confirmClearHistory() {
        PrivacyProtection.show(new AlertDialog.Builder(this).setTitle("清空全部历史记录？")
                .setMessage("将删除本机保存的全部历史，并清除已恢复的当前记录。")
                .setNegativeButton("取消", null).setPositiveButton("清空历史", (dialog, which) -> history.clear(success -> {
                    if (isDestroyed()) return;
                    if (success) {
                        // onStop removes the history listener; reconcile the selected ID in this callback too.
                        changed();
                        setStatus("历史记录已清空", false);
                    }
                    else setStatus("清空失败，请重试。", true);
                })));
    }
    private void showHelp() {
        PrivacyProtection.show(new AlertDialog.Builder(this).setTitle("账号验证管家  " + BuildConfig.VERSION_NAME)
                .setMessage("可拍照或上传二维码图片识别 2FA，也可直接粘贴账号记录、纯 Base32 2FA 密钥或 Authenticator URI，点击“识别并生成”。\n\n账号记录沿用连续 3 个及以上符号自动分隔；复杂边界可手动指定。密码原文完整保留。\n\n纯密钥使用 SHA1 / 6 位 / 30 秒；支持大小写、分组空白和标准末尾填充。\n\n支持 otpauth://totp/ URI，识别账号和发行方，并读取 algorithm、digits、period。算法支持 SHA1/SHA256/SHA512，位数支持 6/8，周期为正整数秒。缺省值是 SHA1 / 6 位 / 30 秒。HOTP 和批量迁移 URI 暂不支持。\n\n验证码按 URI 实际参数刷新，复制时重新计算。请保持手机系统时间准确。\n\n最近 10 条记录在本机加密保存，升级保留旧历史。纯密钥和 URI 不会虚构密码；历史只显示适用的复制按钮。“复制全部”含密钥，“整组”不含密钥。\n\n解析与验证码离线运行；登录功能按需连接官方网页和授权服务，可生成 CPA / sub2api 文件。卸载会删除历史。复制内容受系统和输入法剪贴板设置影响。")
                .setPositiveButton("知道了", null));
    }
    private void setStatus(String message, boolean error) {
        status.setText(message); status.setTextColor(error ? danger : accent);
        if (historyVisible) {
            historyNotice.setText(message); historyNotice.setTextColor(error ? danger : accent); historyNotice.setVisibility(View.VISIBLE);
        }
    }
    private void switchPage(boolean showHistory) {
        historyVisible = showHistory; parsePage.setVisibility(showHistory ? View.GONE : View.VISIBLE); historyPage.setVisibility(showHistory ? View.VISIBLE : View.GONE);
        parseNav.setBackground(ripple(showHistory ? surface : tint, 14)); parseNav.setTextColor(showHistory ? muted : accent); parseNav.setSelected(!showHistory);
        historyNav.setBackground(ripple(showHistory ? tint : surface, 14)); historyNav.setTextColor(showHistory ? accent : muted); historyNav.setSelected(showHistory);
        if (showHistory) { hideKeyboard(); changed(); }
    }
    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0); input.clearFocus(); separator.clearFocus();
    }
    private static String preview(String s) { String value = s.replace("\t", "\\t"); return "「" + (value.length() > 12 ? value.substring(0, 12) + "…" : value) + "」"; }
    @Override protected void onStart() { super.onStart(); history.listen(this); history.load(); changed(); timer.post(tick); }
    @Override protected void onStop() { timer.removeCallbacksAndMessages(null); history.unlisten(this); passwordVisible = false; renderPassword(); super.onStop(); }
    @Override protected void onDestroy() { if (replacementEditor != null) replacementEditor.close(); if (data != null) data.close(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putBoolean("history", historyVisible); super.onSaveInstanceState(out); }
    @Override public Object onRetainNonConfigurationInstance() {
        Session s = new Session(); s.input = input.getText().toString(); s.separator = separator.getText().toString(); s.auto = automatic; s.history = historyVisible; s.selected = selectedId; s.newRetention = newRetention; s.pendingExport = pendingExport;
        if (data != null) s.result = HistoryEntry.fromData("", data, automatic, 0);
        if (replacementEditor != null) s.replacement = replacementEditor.retainedDraft();
        return s;
    }

    private interface Value { String get(); }
    private TextView resultButton(String title, int id, Value value, String label, int fill, int foreground) {
        TextView b = button(title, fill, foreground, 13, null); b.setId(id);
        b.setOnClickListener(v -> { if (data != null && ensureCurrentRecord()) { updateCode(); if (data != null) copy(value.get(), label, b); } }); resultButtons.add(b); return b;
    }
    private TextView navItem(String title, String icon, int id, boolean history) {
        TextView b = button(title, surface, muted, 14, () -> switchPage(history)); b.setId(id); return b;
    }
    private TextView button(String title, int fill, int color, int sp, Runnable action) {
        TextView v = text(title, sp, color, true); v.setTag(R.id.copy_all, title); v.setGravity(Gravity.CENTER); v.setMinHeight(dp(44));
        v.setPadding(dp(6), dp(6), dp(6), dp(6)); v.setBackground(ripple(fill, 11));
        v.setFocusable(true); v.setClickable(true); v.setMaxLines(2); v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
            }
        });
        if (action != null) v.setOnClickListener(w -> action.run());
        return v;
    }
    private TextView label(String value) { TextView t = text(value, 11, muted, false); t.setPadding(0, dp(8), 0, dp(8)); return t; }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); t.setIncludeFontPadding(false); t.setLineSpacing(dp(3), 1f);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL)); return t;
    }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout card() { LinearLayout l = vertical(); l.setPadding(dp(16), dp(12), dp(16), dp(16)); l.setBackground(shape(surface, 18, line)); return l; }
    private ScrollView scroll() { ScrollView s = new ScrollView(this); s.setFillViewport(false); s.setClipToPadding(false); s.setVerticalScrollBarEnabled(false); s.setLayoutParams(new FrameLayout.LayoutParams(-1, -1)); return s; }
    private GradientDrawable shape(int fill, int radius, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); if (stroke != 0) d.setStroke(dp(1), stroke); return d; }
    private RippleDrawable ripple(int fill, int radius) { return new RippleDrawable(android.content.res.ColorStateList.valueOf(Color.parseColor("#227A9E98")), shape(fill, radius, 0), shape(Color.WHITE, radius, 0)); }
    private LinearLayout.LayoutParams size(int width, int height) { return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height)); }
    private LinearLayout.LayoutParams spaced(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); return p; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextWatcher watcher(Runnable action) { return new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        public void onTextChanged(CharSequence s, int start, int before, int count) { action.run(); }
        public void afterTextChanged(Editable e) {}
    }; }
    private final class Track extends View {
        float fraction; boolean warning; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Track() { super(MainActivity.this); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas c) {
            float y = getHeight() / 2f; paint.setColor(Color.parseColor("#36535C")); c.drawRoundRect(0, y - dp(2), getWidth(), y + dp(2), dp(2), dp(2), paint);
            paint.setColor(Color.parseColor(warning ? "#FFD292" : "#7DD4C2")); c.drawRoundRect(0, y - dp(2), getWidth() * fraction, y + dp(2), dp(2), dp(2), paint);
        }
    }
    private final class Glyph extends View {
        private final String kind; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shield = new Path(), check = new Path();
        Glyph(String kind, int color) {
            super(MainActivity.this); this.kind = kind; p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            shield.moveTo(0, -13); shield.lineTo(11, -9); shield.lineTo(11, 0); shield.cubicTo(11, 7, 5, 11, 0, 14); shield.cubicTo(-5, 11, -11, 7, -11, 0); shield.lineTo(-11, -9); shield.close();
            check.moveTo(-5, 0); check.lineTo(-1, 4); check.lineTo(6, -4);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas c) {
            c.save(); c.translate(getWidth() / 2f, getHeight() / 2f); float scale = Math.min(getWidth(), getHeight()) / 32f; c.scale(scale, scale); p.setStrokeWidth(2);
            if (kind.equals("shield")) {
                c.drawPath(shield, p); c.drawPath(check, p);
            } else { c.drawArc(-11, -11, 11, 11, -170, 310, false, p); c.drawLine(-11, -9, -11, -2, p); c.drawLine(-11, -2, -4, -2, p); c.drawLine(0, -6, 0, 0, p); c.drawLine(0, 0, 5, 3, p); }
            c.restore();
        }
    }
}
