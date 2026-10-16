package app.localcache;

import app.localcache.storage.HttpTransfer;
import java.io.IOException;

public final class HttpTransferChecks {
    public static void main(String[] args) throws Exception { runAll(); }

    public static void runAll() throws Exception {
        long gib = 1024L * 1024 * 1024;
        HttpTransfer large = HttpTransfer.response(206, "bytes " + 80 * gib + "-" + (90 * gib - 1) + "/" + 90 * gib, 80 * gib, 10 * gib);
        large.verifyComplete(90 * gib);
        rejected(() -> large.verifyComplete(89 * gib));
        rejected(() -> HttpTransfer.response(206, "bytes 0-9/100", 50, 10));
        rejected(() -> HttpTransfer.response(206, "bytes 50-59/100", 50, 9));
        rejected(() -> HttpTransfer.response(206, "bytes 50-100/100", 50, 51));
        rejected(() -> HttpTransfer.response(206, "bytes 50-59/*", 50, 10));
        rejected(() -> HttpTransfer.response(206, "bytes 50-9223372036854775808/100", 50, -1));
        rejected(() -> HttpTransfer.response(206, null, 0, 100));
        HttpTransfer shortRange = HttpTransfer.response(206, "bytes 0-9/100", 0, 10);
        rejected(() -> shortRange.verifyComplete(10));
        HttpTransfer restarted = HttpTransfer.response(200, null, 80 * gib, 90 * gib);
        if (restarted.offset != 0 || restarted.total != 90 * gib) throw new AssertionError("ignored range was appended");
        restarted.verifyComplete(90 * gib);
        HttpTransfer.response(200, null, 0, -1).verifyComplete(90 * gib); // Chunked full response.
        System.out.println("11 HTTP size/range checks passed");
    }

    private interface Action { void run() throws IOException; }
    private static void rejected(Action action) throws IOException {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("invalid/unfinished transfer was accepted");
    }
}
