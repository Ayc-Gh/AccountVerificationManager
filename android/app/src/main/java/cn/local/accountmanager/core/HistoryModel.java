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
        String name = "";
        for (HistoryEntry e : old) {
            if (e.account.equals(data.account) && e.password.equals(data.password) && e.secret.equals(data.secret)
                    && e.kind == data.kind && e.issuer.equals(data.issuer) && e.otp.equals(data.otp)) { id = e.id; name = e.name; }
            else next.add(e);
        }
        next.add(0, HistoryEntry.fromData(id, data, auto, time).withName(name));
        return new ArrayList<>(next.subList(0, Math.min(LIMIT, next.size())));
    }
    public static List<HistoryEntry> rename(List<HistoryEntry> old, String id, String value) {
        String name = HistoryEntry.normalizeName(value);
        List<HistoryEntry> next = new ArrayList<>(old);
        for (int i = 0; i < next.size(); i++) if (next.get(i).id.equals(id)) {
            next.set(i, next.get(i).withName(name)); return next;
        }
        throw new IllegalArgumentException("记录已不在最近 10 条历史中，请刷新后重试。");
    }

    public static List<HistoryEntry> replaceOtp(List<HistoryEntry> old, HistoryEntry expected, String text) {
        List<HistoryEntry> next = new ArrayList<>(old);
        for (int i = 0; i < next.size(); i++) if (next.get(i).id.equals(expected.id)) {
            HistoryEntry current = next.get(i);
            if (!current.originalText().equals(expected.originalText())) throw new IllegalArgumentException("原记录已更新，请重新打开更换窗口。");
            try (AccountData original = current.open(); AccountData changed = original.replaceOtp(text)) {
                for (HistoryEntry other : next) if (!other.id.equals(current.id) && other.account.equals(changed.account) && other.password.equals(changed.password)
                        && other.secret.equals(changed.secret) && other.kind == changed.kind && other.issuer.equals(changed.issuer) && other.otp.equals(changed.otp))
                    throw new IllegalArgumentException("更换结果与另一条历史记录相同，请直接使用那条记录。");
                next.set(i, HistoryEntry.fromData(current.id, changed, current.automatic, current.recognizedAt).withName(current.name));
                return next;
            }
        }
        throw new IllegalArgumentException("原记录已被移除，请刷新历史后重试。");
    }
}
