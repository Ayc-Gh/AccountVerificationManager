package cn.local.accountmanager.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.io.*;

public class HistoryManagementTest {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private List<HistoryEntry> add(List<HistoryEntry> old, String account, long now, long expiry) {
        try (AccountData data = AccountParser.parse(account + "----Demo----" + SECRET, null)) { return HistoryModel.record(old, data, true, now, expiry); }
    }
    @Test public void metadataSurvivesDuplicateRenameReplacementAndCodec() throws Exception {
        List<HistoryEntry> entries = add(new ArrayList<>(), "one", 100, 500);
        String id = entries.get(0).id;
        entries = HistoryModel.manage(entries, id, null, true, 100);
        entries = HistoryModel.rename(entries, id, "测试名称");
        entries = add(entries, "two", 200, 0);
        assertEquals(id, entries.get(0).id);
        entries = add(entries, "one", 300, 999);
        assertEquals(500, entries.get(0).expiresAt); assertTrue(entries.get(0).pinned);
        entries = HistoryModel.replaceOtp(entries, entries.get(0), "JBSWY3DPEHPK3PXP");
        HistoryEntry e = HistoryCodec.decode(HistoryCodec.encode(entries)).get(0);
        assertEquals(id, e.id); assertEquals("测试名称", e.name); assertTrue(e.pinned); assertEquals(500, e.expiresAt);
    }
    @Test public void expiryBoundaryAndStaleActionsNeverResurrect() {
        List<HistoryEntry> entries = add(new ArrayList<>(), "one", 100, 500);
        HistoryEntry old = entries.get(0);
        entries = HistoryModel.manage(entries, old.id, null, true, 101);
        assertEquals(1, HistoryModel.active(entries, 499).size());
        List<HistoryEntry> expired = HistoryModel.active(entries, 500); assertTrue(expired.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> HistoryModel.manage(expired, old.id, 900L, null, 501));
        assertThrows(IllegalArgumentException.class, () -> HistoryModel.replaceOtp(expired, old, "JBSWY3DPEHPK3PXP"));
    }
    @Test public void pinCapacityAndSingleDeletion() {
        List<HistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            entries = add(entries, "account" + i, 100 + i, 0);
            String id = ""; for (HistoryEntry e : entries) if (e.account.equals("account" + i)) id = e.id;
            entries = HistoryModel.manage(entries, id, null, true, 200);
        }
        final List<HistoryEntry> full = entries;
        assertThrows(IllegalArgumentException.class, () -> add(full, "extra", 300, 0));
        String id = entries.get(9).id;
        entries = HistoryModel.manage(entries, id, null, false, 300);
        entries = add(entries, "extra", 301, 0);
        assertEquals(10, entries.size()); assertFalse(entries.stream().anyMatch(e -> e.id.equals(id)));
        String deleteId = entries.get(0).id;
        entries = HistoryModel.delete(entries, deleteId, 302);
        assertEquals(9, entries.size()); assertFalse(entries.stream().anyMatch(e -> e.id.equals(deleteId)));
    }
    @Test public void changingOneSettingPreservesTheOtherAndRejectsPastDate() {
        List<HistoryEntry> entries = add(new ArrayList<>(), "one", 100, 500);
        String id = entries.get(0).id;
        entries = HistoryModel.manage(entries, id, null, true, 110);
        entries = HistoryModel.manage(entries, id, 600L, null, 120);
        assertTrue(entries.get(0).pinned); assertEquals(600, entries.get(0).expiresAt);
        entries = HistoryModel.manage(entries, id, 0L, null, 130); assertEquals(0, entries.get(0).expiresAt);
        final List<HistoryEntry> saved = entries;
        assertThrows(IllegalArgumentException.class, () -> HistoryModel.manage(saved, id, 130L, null, 130));
        assertThrows(IllegalArgumentException.class, () -> HistoryModel.manage(saved, id, -1L, null, 130));
    }
    @Test public void v3UpgradeDefaultsToPermanentAndUnpinned() throws Exception {
        byte[] modern = HistoryCodec.encode(add(new ArrayList<>(), "legacy", 100, 0));
        byte[] legacy = Arrays.copyOf(modern, modern.length - 9); legacy[7] = 3;
        HistoryEntry e = HistoryCodec.decode(legacy).get(0);
        assertEquals(0, e.expiresAt); assertFalse(e.pinned);
        byte[] upgraded = HistoryCodec.encode(Collections.singletonList(e)); assertEquals(4, upgraded[7]);
        assertEquals(e.originalText(), HistoryCodec.decode(upgraded).get(0).originalText());
    }
}
