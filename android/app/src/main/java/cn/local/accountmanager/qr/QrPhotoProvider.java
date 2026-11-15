package cn.local.accountmanager.qr;

import androidx.core.content.FileProvider;
import cn.local.accountmanager.R;

public final class QrPhotoProvider extends FileProvider {
    public QrPhotoProvider() { super(R.xml.qr_photo_paths); }
}
