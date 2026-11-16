package cn.local.accountmanager.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HistoryModel {
    public static final int LIMIT = 10;
    private HistoryModel() {}
    public static List<HistoryEntry> record(List<HistoryEntry> old, AccountData data, boolean auto, long time) {
        return record(old, data, auto, time, 0);
    }
    public static boolean matches(HistoryEntry e, AccountData data) {
        return e.account.equals(data.account) && e.password.equals(data.password) && e.secret.equals(data.secret)
                && e.kind == data.kind && e.issuer.equals(data.issuer) && e.otp.equals(data.otp);
    }
    public static List<HistoryEntry> active(List<HistoryEntry> old, long now) {
        List<HistoryEntry> next = new ArrayList<>();
        for (HistoryEntry e : old) if (!e.expired(now)) next.add(e);
        return ordered(next);
    }
    private static List<HistoryEntry> ordered(List<HistoryEntry> old) {
        List<HistoryEntry> next = new ArrayList<>();
        for (HistoryEntry e : old) if (e.pinned) next.add(e);
        for (HistoryEntry e : old) if (!e.pinned) next.add(e);
        return next;
    }
    public static List<HistoryEntry> record(List<HistoryEntry> old, AccountData data, boolean auto, long time, long expires) {
        List<HistoryEntry> next = new ArrayList<>();
        boolean pinned = false, duplicate = false;
        String id = UUID.randomUUID().toString();
        String name = "";
        for (HistoryEntry e : active(old, time)) {
            if (e.account.equals(data.account) && e.password.equals(data.password) && e.secret.equals(data.secret)
                    && e.kind == data.kind && e.issuer.equals(data.issuer) && e.otp.equals(data.otp)) { id = e.id; name = e.name; expires = e.expiresAt; pinned = e.pinned; duplicate = true; }
            else next.add(e);
        }
        if (!duplicate && next.size() >= LIMIT && next.stream().allMatch(e -> e.pinned))
            throw new IllegalArgumentException("十条历史均已置顶，请先取消置顶或删除一条记录。");
        validateExpiry(expires, time);
        next.add(0, HistoryEntry.fromData(id, data, auto, time).withName(name).withManagement(expires, pinned));
        next = ordered(next);
        return new ArrayList<>(next.subList(0, Math.min(LIMIT, next.size())));
    }
    public static void validateExpiry(long expires, long now) {
        if (expires < 0 || (expires != 0 && expires <= now)) throw new IllegalArgumentException("到期时间必须晚于当前时间。");
    }
    public static List<HistoryEntry> delete(List<HistoryEntry> old, String id, long now) {
        List<HistoryEntry> next = active(old, now); next.removeIf(e -> e.id.equals(id)); return next;
    }
    public static List<HistoryEntry> manage(List<HistoryEntry> old, String id, Long expires, Boolean pinned, long now) {
        List<HistoryEntry> next = active(old, now);
        if (expires != null) validateExpiry(expires, now);
        for (int i = 0; i < next.size(); i++) if (next.get(i).id.equals(id)) {
            HistoryEntry e = next.get(i);
            next.set(i, e.withManagement(expires == null ? e.expiresAt : expires, pinned == null ? e.pinned : pinned));
            return ordered(next);
        }
        throw new IllegalArgumentException("记录已到期或被移除，请刷新历史。");
    }
    public static List<HistoryEntry> rename(List<HistoryEntry> old, String id, String value) {
        String name = HistoryEntry.normalizeName(value);
        List<HistoryEntry> next = new ArrayList<>(old);
        for (int i = 0; i < next.size(); i++) if (next.get(i).id.equals(id)) {
            next.set(i, next.get(i).withName(name)); return next;
        }
        throw new IllegalArgumentException("记录已不在最近 10 条历史中，请刷新后重试。");
    }

    public static List<HistoryEntry> importEntries(List<HistoryEntry> old, List<HistoryEntry> incoming, long now) {
        List<HistoryEntry> next = active(old, now);
        for (HistoryEntry e : incoming) {
            if (e.expired(now)) continue;
            try (AccountData data = e.open()) {
                if (next.stream().anyMatch(x -> matches(x, data))) continue;
                if (next.size() >= LIMIT) throw new IllegalArgumentException("导入后超过 10 条，请减少选择或先删除历史；本次未导入任何记录。");
                next.add(HistoryEntry.fromData(UUID.randomUUID().toString(), data, e.automatic, e.recognizedAt).withName(e.name).withManagement(e.expiresAt, e.pinned));
            }
        }
        return ordered(next);
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
                next.set(i, HistoryEntry.fromData(current.id, changed, current.automatic, current.recognizedAt).withName(current.name).withManagement(current.expiresAt, current.pinned));
                return next;
            }
        }
        throw new IllegalArgumentException("原记录已被移除，请刷新历史后重试。");
    }
}
