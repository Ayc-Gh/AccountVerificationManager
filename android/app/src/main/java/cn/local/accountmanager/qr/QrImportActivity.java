package cn.local.accountmanager.qr;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import cn.local.accountmanager.core.QrDecoder;
import cn.local.accountmanager.R;
import cn.local.accountmanager.PrivacyProtection;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Delegates capture/selection to system apps, then decodes on this device. */
public final class QrImportActivity extends Activity {
    public static final String MODE = "mode", PHOTO = "photo", UPLOAD = "upload", TEXT = "qr_text", ERROR = "qr_error";
    private static final int IMAGE_REQUEST = 201;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Uri photoUri, imageUri;
    private boolean launched;
    private TextView message;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        PrivacyProtection.apply(this);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(24 * getResources().getDisplayMetrics().density); layout.setPadding(padding, padding * 2, padding, padding);
        message = new TextView(this); message.setTextSize(18); message.setText(R.string.qr_import_opening); layout.addView(message);
        Button cancel = new Button(this); cancel.setText(R.string.qr_import_cancel); cancel.setOnClickListener(v -> finish()); layout.addView(cancel);
        setContentView(layout);
        if (state != null) {
            launched = state.getBoolean("launched");
            String photo = state.getString("photo_uri"), image = state.getString("image_uri");
            if (photo != null) photoUri = Uri.parse(photo);
            if (image != null) imageUri = Uri.parse(image);
        }
        if (imageUri != null) decode();
        else if (!launched) openImage();
    }

    private void openImage() {
        launched = true;
        try {
            Intent intent;
            if (PHOTO.equals(getIntent().getStringExtra(MODE))) {
                File directory = new File(getCacheDir(), "qr-photos");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException();
                File[] stale = directory.listFiles();
                if (stale != null) for (File file : stale)
                    if (file.getName().startsWith("capture-") && file.lastModified() < System.currentTimeMillis() - 86400000L) file.delete();
                File photo = File.createTempFile("capture-", ".jpg", directory);
                photoUri = FileProvider.getUriForFile(this, getPackageName() + ".qr-photos", photo);
                intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("二维码照片", photoUri));
                message.setText(R.string.qr_import_photo_hint);
            } else {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                message.setText(R.string.qr_import_upload_hint);
            }
            startActivityForResult(intent, IMAGE_REQUEST);
        } catch (ActivityNotFoundException e) { fail("未找到可用的相机或图片选择应用，请使用另一种导入方式。"); }
        catch (IOException | SecurityException e) { fail("无法打开图片，请重试。"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMAGE_REQUEST) return;
        if (result != RESULT_OK) { finish(); return; }
        imageUri = PHOTO.equals(getIntent().getStringExtra(MODE)) ? photoUri : data == null ? null : data.getData();
        if (imageUri == null) { fail("未取得图片，请重新拍照或上传。"); return; }
        decode();
    }

    private void decode() {
        message.setText(R.string.qr_import_decoding);
        worker.execute(() -> {
            try {
                List<String> choices = QrImageReader.read(getContentResolver(), imageUri);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (choices.size() == 1) complete(choices.get(0));
                    else {
                        String[] labels = new String[choices.size()];
                        for (int i = 0; i < labels.length; i++) labels[i] = (i + 1) + ". " + QrDecoder.describe(choices.get(i));
                        PrivacyProtection.show(new AlertDialog.Builder(this).setTitle("选择要识别的 2FA 二维码")
                                .setItems(labels, (dialog, which) -> complete(choices.get(which)))
                                .setNegativeButton("取消", (dialog, which) -> finish()).setOnCancelListener(dialog -> finish()));
                    }
                });
            } catch (Exception e) {
                final String error = e instanceof IllegalArgumentException ? e.getMessage() : "无法读取图片，请选择清晰且有效的图片。";
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) fail(error); });
            }
        });
    }
    private void complete(String text) { setResult(RESULT_OK, new Intent().putExtra(TEXT, text)); finish(); }
    private void fail(String error) { setResult(RESULT_CANCELED, new Intent().putExtra(ERROR, error)); finish(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("launched", launched);
        if (photoUri != null) out.putString("photo_uri", photoUri.toString());
        if (imageUri != null) out.putString("image_uri", imageUri.toString());
        super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (isFinishing() && photoUri != null) {
            revokeUriPermission(photoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().delete(photoUri, null, null); } catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }
}
