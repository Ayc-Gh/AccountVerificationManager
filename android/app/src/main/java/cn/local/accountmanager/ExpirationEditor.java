package cn.local.accountmanager;

import android.app.*;
import android.text.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;
import cn.local.accountmanager.core.RetentionPeriod;

final class ExpirationEditor {
    static AlertDialog show(Activity activity, RetentionPeriod initial, long existing, boolean managing, Consumer<RetentionPeriod> save) {
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * activity.getResources().getDisplayMetrics().density); box.setPadding(pad, pad / 2, pad, pad / 2);
        TextView note = new TextView(activity);
        note.setText(managing ? "当前到期：" + (existing == 0 ? "永久保留" : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(existing))) + "\n保存后从现在起按新期限重新计时。" : "输入数字并选择单位，如 30 分、2 时、7 天。\n首次保存成功时开始计时，重复解析保留原期限。"); box.addView(note);
        CheckBox enabled = new CheckBox(activity); enabled.setText("设置保存期限"); enabled.setChecked(initial != null || existing != 0); enabled.setTag("expiry-enabled"); box.addView(enabled);
        LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
        EditText amount = new EditText(activity); amount.setTag("expiry-amount"); amount.setContentDescription("保存期限数字"); amount.setSingleLine(true);
        amount.setRawInputType(InputType.TYPE_CLASS_NUMBER); amount.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)}); amount.setText(initial == null ? "1" : Integer.toString(initial.amount));
        Spinner unit = new Spinner(activity, Spinner.MODE_DIALOG) {
            // Spinner.performClick opens its own unprotected dialog. Announce this
            // click explicitly and use the app's protected, accessible choice list.
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            @Override public boolean performClick() {
                sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                PrivacyProtection.show(new AlertDialog.Builder(activity).setTitle("保存期限单位").setSingleChoiceItems(new String[]{"分", "时", "天", "周", "月"}, getSelectedItemPosition(), (dialog, selected) -> { setSelection(selected); dialog.dismiss(); }).setNegativeButton("取消", null));
                return true;
            }
        }; unit.setTag("expiry-unit"); unit.setContentDescription("保存期限单位");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, new String[]{"分", "时", "天", "周", "月"}); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); unit.setAdapter(adapter); unit.setSelection(initial == null ? 2 : initial.unit);
        row.addView(amount, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(unit, new LinearLayout.LayoutParams(pad * 4, -2)); box.addView(row);
        TextView feedback = new TextView(activity); feedback.setTag("expiry-feedback");
        String help = "不勾选表示永久保留。1天=24时，1周=7天，1月=30天。\n到期自动删除；关闭期间到期，下次启动清理。"; feedback.setText(help); box.addView(feedback);
        amount.setEnabled(enabled.isChecked()); unit.setEnabled(enabled.isChecked());
        enabled.setOnCheckedChangeListener((button, checked) -> { amount.setEnabled(checked); unit.setEnabled(checked); feedback.setText(help); if (checked) { amount.requestFocus(); amount.selectAll(); } });
        amount.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void onTextChanged(CharSequence s, int start, int before, int count) { feedback.setText(help); } public void afterTextChanged(Editable value) {} });
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        AlertDialog dialog = PrivacyProtection.show(new AlertDialog.Builder(activity).setTitle(managing ? "管理到期时间" : "保存期限").setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                RetentionPeriod period = enabled.isChecked() ? RetentionPeriod.parse(amount.getText().toString(), unit.getSelectedItemPosition()) : null;
                if (period != null) period.expiresAt(System.currentTimeMillis());
                save.accept(period); dialog.dismiss();
            } catch (IllegalArgumentException e) { feedback.setText(e.getMessage()); }
        });
        return dialog;
    }
}
