package cn.local.accountmanager;

import android.app.*;
import android.content.*;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.function.Consumer;
import cn.local.accountmanager.core.*;
import cn.local.accountmanager.storage.HistoryRepository;
import cn.local.accountmanager.qr.QrImportActivity;

/** Draft credentials stay in memory, including across configuration changes. */
public final class OtpReplacementEditor {
    public static final class Draft {
        final HistoryEntry expected;
        String text = "";
        boolean saving;
        Boolean result;
        boolean discarded;
        Consumer<Boolean> completion;
        public Draft(HistoryEntry expected) { this.expected = expected; }
    }
    final AlertDialog dialog;
    private final Activity activity;
    private final Draft draft;
    private final AccountData original;
    private AccountData preview;
    private final EditText input;
    private final TextView code, parameters, feedback;
    private final Button copyNew;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Consumer<Boolean> completion;
    private boolean closed;
    private final int normalColor;
    private final Runnable tick = new Runnable() { public void run() { if (!closed) { updateCode(); timer.postDelayed(this, 200); } } };

    public OtpReplacementEditor(Activity activity, Draft draft, HistoryRepository history, Consumer<String> qr, Runnable updated) {
        this.activity = activity; this.draft = draft; original = draft.expected.open();
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24), dp(8), dp(24), dp(12));
        TextView identity = text(draft.expected.displayName(), 15); identity.setMaxLines(2); content.addView(identity);
        content.addView(text("保留账号、密码、名称和列表位置", 12));
        Button oldCopy = new Button(activity); oldCopy.setId(R.id.replace_otp_old_copy); oldCopy.setText("复制旧验证码"); oldCopy.setOnClickListener(v -> copy(original.code(Totp.now()))); content.addView(oldCopy);
        input = new EditText(activity); input.setId(R.id.replace_otp_input); input.setHint("新 Base32 密钥或 otpauth:// URI"); input.setText(draft.text);
        input.setMinLines(2); input.setMaxLines(4); input.setTextSize(14); input.setSaveEnabled(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AccountParser.MAX_INPUT)}); content.addView(input);
        Button paste = new Button(activity); paste.setId(R.id.replace_otp_paste); paste.setText("粘贴新密钥"); paste.setOnClickListener(v -> {
            if (draft.saving) return;
            try { ClipData clip = activity.getSystemService(ClipboardManager.class).getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) error("剪贴板中没有文本。");
                else if (clip.getItemAt(0).getText().length() > AccountParser.MAX_INPUT) error("内容过长，请只粘贴一个新密钥或 URI。");
                else input.setText(clip.getItemAt(0).getText());
            } catch (SecurityException error) { error("剪贴板暂时不可用，请手动粘贴。"); }
        }); content.addView(paste);
        LinearLayout images = new LinearLayout(activity);
        for (String mode : new String[]{QrImportActivity.PHOTO, QrImportActivity.UPLOAD}) {
            Button button = new Button(activity); button.setText(mode.equals(QrImportActivity.PHOTO) ? "拍照二维码" : "上传二维码");
            button.setId(mode.equals(QrImportActivity.PHOTO) ? R.id.replace_otp_photo : R.id.replace_otp_upload);
            button.setOnClickListener(v -> { if (!draft.saving) qr.accept(mode); }); images.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        } content.addView(images);
        parameters = text("", 12); content.addView(parameters);
        code = text("--- ---", 30); code.setId(R.id.replace_otp_code); code.setGravity(Gravity.CENTER); content.addView(code);
        copyNew = new Button(activity); copyNew.setText("复制新验证码"); copyNew.setId(R.id.replace_otp_copy); copyNew.setOnClickListener(v -> { if (preview != null) copy(preview.code(Totp.now())); }); content.addView(copyNew);
        feedback = text("", 12); normalColor = feedback.getCurrentTextColor(); content.addView(feedback);
        content.addView(text("确认服务端换绑成功后，再替换本机记录。", 12));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        dialog = PrivacyProtection.show(new AlertDialog.Builder(activity).setTitle("更换 2FA").setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存替换", null));
        completion = success -> {
            if (closed || activity.isDestroyed()) return;
            if (success) { draft.discarded = true; updated.run(); dialog.dismiss(); }
            else { setSaving(false); error("更换失败，原记录已保留。记录可能已更新，请重试或重新打开。"); }
        }; draft.completion = completion;
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { draft.discarded = true; dialog.dismiss(); });
        dialog.setOnCancelListener(ignored -> { if (!activity.isChangingConfigurations()) draft.discarded = true; });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (preview == null || draft.saving) return;
            draft.result = null; setSaving(true); final String value = input.getText().toString();
            history.replaceOtp(draft.expected, value, success -> { draft.saving = false; draft.result = success; if (draft.completion != null) draft.completion.accept(success); });
        });
        dialog.setOnDismissListener(ignored -> close());
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { draft.text = s.toString(); updatePreview(); }
            public void afterTextChanged(Editable e) {}
        }); updatePreview(); setSaving(draft.saving); timer.post(tick);
        input.requestFocus(); input.setSelection(input.length());
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (draft.result != null) completion.accept(draft.result);
    }
    private void setSaving(boolean saving) {
        draft.saving = saving; input.setEnabled(!saving); dialog.setCancelable(!saving);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!saving);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!saving && preview != null);
    }
    private void updatePreview() {
        if (preview != null) { preview.close(); preview = null; }
        code.setText("--- ---"); parameters.setText("新验证码预览"); feedback.setText("纯密钥沿用当前参数；URI 采用新参数。"); feedback.setTextColor(normalColor);
        if (!input.getText().toString().trim().isEmpty()) try {
            preview = original.replaceOtp(input.getText().toString()); parameters.setText(preview.otp.description()); updateCode();
        } catch (IllegalArgumentException error) { error(error.getMessage()); }
        copyNew.setEnabled(preview != null); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(preview != null && !draft.saving);
    }
    private void updateCode() { if (preview != null) { String value = preview.code(Totp.now()); code.setText(value.substring(0, value.length()/2) + " " + value.substring(value.length()/2)); } }
    public void acceptQr(String text) { if (!closed && !draft.saving) input.setText(text); }
    public void error(String text) { if (!closed) { feedback.setText(text); feedback.setTextColor(Color.rgb(197,64,77)); } }
    public Draft snapshot() { return closed || draft.discarded || !dialog.isShowing() ? null : draft; }
    public Draft retainedDraft() { return draft.discarded ? null : draft; }
    public void close() {
        if (closed) return; closed = true; timer.removeCallbacksAndMessages(null);
        if (draft.completion == completion) draft.completion = null;
        if (preview != null) preview.close(); original.close(); if (dialog.isShowing()) dialog.dismiss();
    }
    private void copy(String value) {
        ClipData clip = ClipData.newPlainText("账号验证管家", value); PersistableBundle extras = new PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
        try { activity.getSystemService(ClipboardManager.class).setPrimaryClip(clip); feedback.setText("验证码已复制"); feedback.setTextColor(normalColor); }
        catch (SecurityException error) { error("复制失败，请重试。"); }
    }
    private TextView text(String value, int size) { TextView result = new TextView(activity); result.setText(value); result.setTextSize(size); result.setPadding(0, dp(4), 0, dp(4)); return result; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
