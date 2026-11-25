package cn.local.accountmanager;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import cn.local.accountmanager.core.AccountData;
import cn.local.accountmanager.core.HistoryEntry;
import cn.local.accountmanager.core.HistoryModel;
import cn.local.accountmanager.core.LoginJson;
import cn.local.accountmanager.core.OAuthLogin;
import cn.local.accountmanager.storage.HistoryRepository;
import java.util.Collections;

/** Seeds a fixed QA session for external picker and process-recovery tests, then leaves the task. */
public final class ExportFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        PrivacyProtection.apply(this);
        if (state != null) { finish(); return; } // A restored task must never acquire new credentials from the fixture.

        HistoryRepository history = ((ManagerApplication) getApplication()).history;
        HistoryEntry sample = new HistoryEntry("", "alice@example.com", "SyntheticQaOnly", "JBSWY3DPEHPK3PXP",
                "----", "----", true, System.currentTimeMillis());
        history.importEntries(Collections.singletonList(sample), success -> {
            if (isFinishing() || isDestroyed()) return;
            if (success) {
                try (AccountData expected = sample.open()) {
                    for (HistoryEntry entry : history.entries()) {
                        if (HistoryModel.matches(entry, expected)) {
                            openLogin(history, entry);
                            return;
                        }
                    }
                }
            }
            TextView error = new TextView(this);
            error.setText("QA fixture could not load the synthetic history record.");
            setContentView(error);
        });
    }

    private void openLogin(HistoryRepository history, HistoryEntry entry) {
        LoginFlow previous = LoginFlow.active;
        if (previous != null) previous.close();
        LoginFlow flow = new LoginFlow(history, entry, OAuthLogin.Provider.CLAUDE, false);
        String result = LoginJson.encode(LoginJson.object(
                "access_token", "rotation-access", "refresh_token", "rotation-refresh", "expires_in", 3600,
                "account", LoginJson.object("email_address", entry.account, "uuid", "rotation-account"),
                "organization", LoginJson.object("uuid", "rotation-org")));
        flow.credential = flow.login.parseTokens(result, System.currentTimeMillis());
        LoginFlow.active = flow;
        startActivity(new Intent(this, LoginActivity.class).putExtra("record_id", entry.id));
        finish();
    }
}
