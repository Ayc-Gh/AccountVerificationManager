package cn.local.accountmanager;

import android.app.Application;
import cn.local.accountmanager.storage.*;

public final class ManagerApplication extends Application {
    public HistoryRepository history;
    @Override public void onCreate() {
        super.onCreate();
        history = new HistoryRepository(new EncryptedHistoryStore(this));
        history.load();
    }
}
