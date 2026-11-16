package cn.local.accountmanager;

import android.app.*;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;
import cn.local.accountmanager.core.*;

final class TransferDialogs {
    static AlertDialog select(Activity activity, String title, List<HistoryEntry> source, Consumer<List<HistoryEntry>> callback) {
        List<HistoryEntry> entries = HistoryModel.active(source, System.currentTimeMillis());
        String[] labels = new String[entries.size()]; boolean[] selected = new boolean[entries.size()];
        for (int i = 0; i < entries.size(); i++) labels[i] = entries.get(i).displayName() + (entries.get(i).pinned ? " · 已置顶" : "");
        AlertDialog dialog = PrivacyProtection.show(new AlertDialog.Builder(activity).setTitle(title)
                .setMultiChoiceItems(labels, selected, (d, which, checked) -> selected[which] = checked)
                .setNeutralButton("全选 / 取消", null).setNegativeButton("取消", null).setPositiveButton("继续", null));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            boolean all = true; for (boolean b : selected) all &= b;
            for (int i = 0; i < selected.length; i++) { selected[i] = !all; dialog.getListView().setItemChecked(i, !all); }
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<HistoryEntry> chosen = new ArrayList<>(); for (int i = 0; i < selected.length; i++) if (selected[i] && !entries.get(i).expired(System.currentTimeMillis())) chosen.add(entries.get(i));
            if (chosen.isEmpty()) { Toast.makeText(activity, "请选择至少一条未到期记录。", Toast.LENGTH_SHORT).show(); return; }
            dialog.dismiss(); callback.accept(chosen);
        });
        return dialog;
    }
    static AlertDialog password(Activity activity, boolean exporting, Consumer<String> callback) {
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL); int pad = (int)(24 * activity.getResources().getDisplayMetrics().density); box.setPadding(pad, 0, pad, 0);
        TextView note = new TextView(activity); note.setText("文件密码不会保存，导入时需要此密码。"); box.addView(note);
        EditText password = input(activity, "文件密码"), confirm = input(activity, "再次输入文件密码"); box.addView(password); if (exporting) box.addView(confirm);
        TextView error = new TextView(activity); box.addView(error);
        AlertDialog dialog = PrivacyProtection.show(new AlertDialog.Builder(activity).setTitle(exporting ? "设置导出文件密码" : "输入导入文件密码").setView(box).setNegativeButton("取消", null).setPositiveButton("继续", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = password.getText().toString();
            if (value.isEmpty() || (exporting && !value.equals(confirm.getText().toString()))) { error.setText("请输入密码，并确保两次输入一致。"); return; }
            password.setText(""); confirm.setText(""); dialog.dismiss(); callback.accept(value);
        });
        return dialog;
    }
    private static EditText input(Activity activity, String hint) {
        EditText edit = new EditText(activity); edit.setHint(hint); edit.setTag(hint); edit.setSingleLine(true); edit.setSaveEnabled(false);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); edit.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1024)}); return edit;
    }
}
