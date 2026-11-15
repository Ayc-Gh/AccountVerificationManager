package cn.local.accountmanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.view.WindowManager;

/** Apply the same capture policy to every window that can display credentials. */
public final class PrivacyProtection {
    private PrivacyProtection() {}

    public static void apply(Activity activity) {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(false);
    }

    public static AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        return dialog;
    }
}
