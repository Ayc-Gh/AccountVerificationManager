package cn.local.accountmanager.qr;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.*;
import java.util.List;
import cn.local.accountmanager.core.QrDecoder;

public final class QrImageReader {
    private QrImageReader() {}

    public static List<String> read(ContentResolver resolver, Uri uri) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(stream, null, options); }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("无法读取图片，请重新选择有效图片。");
        options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while ((long) Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2400 ||
                (long) options.outWidth * options.outHeight / options.inSampleSize / options.inSampleSize > 4000000)
            options.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream stream = resolver.openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(stream, null, options); }
        if (bitmap == null) throw new IOException("无法读取图片，请重新选择有效图片。");
        try {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int[] pixels = new int[width * height]; bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            for (int i = 0; i < pixels.length; i++) {
                int color = pixels[i], alpha = color >>> 24;
                if (alpha < 255) {
                    int red = ((color >> 16 & 255) * alpha + 255 * (255 - alpha)) / 255;
                    int green = ((color >> 8 & 255) * alpha + 255 * (255 - alpha)) / 255;
                    int blue = ((color & 255) * alpha + 255 * (255 - alpha)) / 255;
                    pixels[i] = 0xff000000 | red << 16 | green << 8 | blue;
                }
            }
            return QrDecoder.decode(pixels, width, height);
        } finally { bitmap.recycle(); }
    }
}
