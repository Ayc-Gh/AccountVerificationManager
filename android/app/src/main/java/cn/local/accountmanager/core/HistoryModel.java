package cn.local.accountmanager.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HistoryModel {
    public static final int LIMIT = 10;
    private HistoryModel() {}
    public static List<HistoryEntry> record(List<HistoryEntry> old, AccountData data, boolean auto, long time) {
        List<HistoryEntry> next = new ArrayList<>();
        String id = UUID.randomUUID().toString();
        for (HistoryEntry e : old) {
            if (e.account.equals(data.account) && e.password.equals(data.password) && e.secret.equals(data.secret)) id = e.id;
            else next.add(e);
        }
        next.add(0, new HistoryEntry(id, data.account, data.password, data.secret, data.firstSeparator, data.lastSeparator, auto, time));
        return new ArrayList<>(next.subList(0, Math.min(LIMIT, next.size())));
    }
}
