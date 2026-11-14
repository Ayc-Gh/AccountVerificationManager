package cn.local.accountmanager.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class HistoryCodec {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private HistoryCodec() {}
    public static byte[] encode(List<HistoryEntry> entries) throws IOException {
        if (entries.size() > HistoryModel.LIMIT) throw new IOException("历史记录数量异常。");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x41564D48); out.writeInt(2); out.writeInt(entries.size());
            for (HistoryEntry e : entries) {
                writeText(out, e.id); writeText(out, e.account); writeText(out, e.password); writeText(out, e.secret);
                writeText(out, e.firstSeparator); writeText(out, e.lastSeparator);
                out.writeBoolean(e.automatic); out.writeLong(e.recognizedAt);
                writeText(out, e.kind.name()); writeText(out, e.issuer); writeText(out, e.otp.algorithm);
                out.writeInt(e.otp.digits); out.writeInt(e.otp.period);
            }
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("历史记录内容过大。");
        return bytes.toByteArray();
    }
    public static List<HistoryEntry> decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("历史记录内容过大。");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x41564D48) throw new IOException("历史记录格式无效。");
            int version = in.readInt();
            if (version != 1 && version != 2) throw new IOException("历史记录版本不兼容。");
            int count = in.readInt();
            if (count < 0 || count > HistoryModel.LIMIT) throw new IOException("历史记录数量异常。");
            List<HistoryEntry> result = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String id = readText(in), account = readText(in), password = readText(in), secret = readText(in), first = readText(in), last = readText(in);
                boolean automatic = in.readBoolean(); long time = in.readLong();
                UUID.fromString(id);
                if (!ids.add(id) || time < 0)
                    throw new IOException("历史记录字段无效。");
                AccountData.Kind kind = version == 1 ? AccountData.Kind.RECORD : AccountData.Kind.valueOf(readText(in));
                String issuer = version == 1 ? "" : readText(in);
                OtpParameters otp = version == 1 ? OtpParameters.DEFAULT : new OtpParameters(readText(in), in.readInt(), in.readInt());
                try (AccountData data = new AccountData(account, password, secret, first, last, kind, issuer, otp)) {
                    result.add(HistoryEntry.fromData(id, data, automatic, time));
                }
            }
            if (in.available() != 0) throw new IOException("历史记录包含无效内容。");
            return result;
        } catch (IllegalArgumentException e) { throw new IOException("历史记录字段无效。", e); }
    }
    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        if (b.length > 131072) throw new IOException("历史记录字段过长。");
        out.writeInt(b.length); out.write(b);
    }
    private static String readText(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 131072 || size > in.available()) throw new IOException("历史记录字段长度无效。");
        byte[] b = new byte[size]; in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
