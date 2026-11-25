package cn.local.accountmanager;

import android.os.FileObserver;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A FileProvider destination that holds the app's real write until the rotated page is ready. */
final class BlockedExportDocument implements AutoCloseable {
    final File pipe;
    private final CountDownLatch opened = new CountDownLatch(1);
    private final FileObserver observer;
    private FileDescriptor reader;
    private int padding;

    @SuppressWarnings("deprecation") // The path constructor also supports API 26.
    BlockedExportDocument(File directory) throws Exception {
        pipe = new File(directory, "rotating-login-" + UUID.randomUUID() + ".json");
        observer = new FileObserver(pipe.getAbsolutePath(), FileObserver.OPEN) {
            @Override public void onEvent(int event, String path) {
                if ((event & FileObserver.OPEN) != 0) opened.countDown();
            }
        };
        Os.mkfifo(pipe.getPath(), 0600);
        try {
            reader = Os.open(pipe.getPath(), OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
            FileDescriptor filler = Os.open(pipe.getPath(), OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
            try {
                byte[] block = new byte[4096];
                while (true) padding += Os.write(filler, block, 0, block.length);
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EAGAIN) throw e;
            } finally { Os.close(filler); }
            if (padding == 0) throw new IOException("Test pipe could not be primed.");
            observer.startWatching();
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    void awaitWriter() throws Exception {
        if (!opened.await(10, TimeUnit.SECONDS)) throw new AssertionError("The app never opened the selected export document.");
    }

    void captureTo(File document) throws Exception {
        StructPollfd poll = new StructPollfd();
        poll.fd = reader;
        poll.events = (short) (OsConstants.POLLIN | OsConstants.POLLHUP);
        long deadline = SystemClock.elapsedRealtime() + 10000;
        try (FileOutputStream output = new FileOutputStream(document)) {
            byte[] chunk = new byte[4096];
            while (true) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0 || Os.poll(new StructPollfd[]{poll}, (int) remaining) == 0)
                    throw new AssertionError("The app did not finish writing the selected export document.");
                int count = Os.read(reader, chunk, 0, chunk.length);
                if (count == 0) break;
                int skip = Math.min(padding, count);
                padding -= skip;
                output.write(chunk, skip, count - skip);
            }
        }
        if (padding != 0) throw new IOException("Incomplete test pipe.");
    }

    @Override public void close() throws Exception {
        observer.stopWatching();
        try { if (reader != null) Os.close(reader); }
        finally { java.nio.file.Files.deleteIfExists(pipe.toPath()); }
    }
}
