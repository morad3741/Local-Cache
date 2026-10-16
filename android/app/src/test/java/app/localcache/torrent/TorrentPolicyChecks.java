package app.localcache.torrent;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import app.localcache.DiagnosticText;
import app.localcache.storage.StorageBudget;

/** Dependency-free behavioral checks, also called by the normal Gradle/JUnit suite. */
public final class TorrentPolicyChecks {
    private static final long MINUTE = 60_000L;
    private static int passed;

    public static void main(String[] args) throws Exception {
        runAll();
    }

    public static void runAll() throws Exception {
        passed = 0;
        test("checking progresses beyond five minutes", () -> {
            TorrentProgress p = budget();
            for (int minute = 0; minute <= 20; minute++) {
                equal(null, p.sample(minute * MINUTE, true, minute * 40_000, 0, 0));
            }
        });
        test("checking without progress is bounded", () -> {
            TorrentProgress p = budget();
            equal(null, p.sample(0, true, 100, 0, 0));
            contains(p.sample(10 * MINUTE, true, 100, 0, 0), "Storage check");
        });
        test("network gets its budget after a long check", () -> {
            TorrentProgress p = budget();
            for (int minute = 0; minute <= 20; minute++) p.sample(minute * MINUTE, true, minute, 0, 0);
            equal(null, p.sample(21 * MINUTE, false, 0, 0, 0));
            equal(null, p.sample(25 * MINUTE, false, 0, 0, 0));
            contains(p.sample(26 * MINUTE, false, 0, 0, 0), "opening data");
        });
        test("a torrent without usable bytes is bounded", () -> {
            TorrentProgress p = budget();
            equal(null, p.sample(4 * MINUTE, false, 0, 0, 100));
            contains(p.sample(5 * MINUTE, false, 0, 0, 200), "opening data");
        });
        test("a started download without progress is bounded", () -> {
            TorrentProgress p = budget();
            equal(null, p.sample(0, false, 0, 16, 16));
            equal(null, p.sample(9 * MINUTE, false, 0, 16, 16));
            contains(p.sample(10 * MINUTE, false, 0, 16, 16), "no download progress");
        });
        test("verified seek pieces keep a live transfer active", () -> {
            TorrentProgress p = budget();
            for (int minute = 0; minute <= 30; minute++) {
                equal(null, p.sample(minute * MINUTE, false, 0, 16, 16L + minute * 16));
            }
        });
        test("checking does not consume a started transfer's stall budget", () -> {
            TorrentProgress p = budget();
            p.sample(0, false, 0, 16, 16);
            p.sample(2 * MINUTE, false, 0, 16, 16);
            for (int minute = 2; minute <= 22; minute++) p.sample(minute * MINUTE, true, minute, 0, 0);
            equal(null, p.sample(23 * MINUTE, false, 0, 16, 16));
        });
        test("selected file's nonzero offset is respected", () -> {
            boolean[] bits = {true, true, true, false};
            equal(35L, TorrentProgress.readableEnd(bits, 13, 40, 16, 0, 256));
            bits[3] = true;
            equal(40L, TorrentProgress.readableEnd(bits, 13, 40, 16, 0, 256));
        });
        test("a missing shared boundary piece is not readable", () -> {
            boolean[] bits = {false, true, true, true};
            equal(0L, TorrentProgress.readableEnd(bits, 13, 40, 16, 0, 256));
        });
        test("seek reads only its verified contiguous window", () -> {
            boolean[] bits = {false, false, false, true, false};
            equal(51L, TorrentProgress.readableEnd(bits, 13, 60, 16, 40, 256));
        });
        test("a missing/rechecking snapshot exposes no bytes", () -> {
            equal(7L, TorrentProgress.readableEnd(null, 13, 60, 16, 7, 256));
            equal(7L, TorrentProgress.readableEnd(new boolean[5], 13, 60, 16, 7, 256));
        });
        test("piece lookahead stays bounded", () -> {
            boolean[] bits = new boolean[400];
            Arrays.fill(bits, true);
            equal(16L * 257, TorrentProgress.readableEnd(bits, 0, 6400, 16, 0, 256));
        });
        test("90 GiB with small pieces uses bounded JNI reads", () -> {
            long size = 90L * 1024 * 1024 * 1024, piece = 16 * 1024;
            int last = (int) ((size - 1) / piece);
            AtomicInteger reads = new AtomicInteger();
            long seek = 85L * 1024 * 1024 * 1024;
            TorrentPieces.Snapshot snapshot = TorrentPieces.sample(i -> { reads.incrementAndGet(); return true; },
                last + 1, 0, 0, last, 0, size, piece, seek, false);
            if (reads.get() > TorrentPieces.PREFIX_SCAN_LIMIT + TorrentPieces.LOOKAHEAD + 3)
                throw new AssertionError("JNI scan grew with movie size: " + reads);
            equal((long) TorrentPieces.PREFIX_SCAN_LIMIT * piece, snapshot.contiguousBytes);
            equal(seek + (TorrentPieces.LOOKAHEAD + 1L) * piece, snapshot.readableEnd(seek));
            equal(seek - piece, snapshot.readableEnd(seek - piece)); // Outside the published seek window.
        });
        test("bounded seek snapshot respects offsets and missing pieces", () -> {
            TorrentPieces.Snapshot snapshot = TorrentPieces.sample(i -> i == 1 || i == 3,
                5, 0, 0, 4, 13, 60, 16, 40, false);
            equal(0L, snapshot.contiguousBytes);
            equal(51L, snapshot.readableEnd(40));
            equal(0L, snapshot.readableEnd(0));
            equal(false, snapshot.openingReady);
        });
        test("native finished status can publish a large verified prefix without scanning it", () -> {
            long size = 90L * 1024 * 1024 * 1024, piece = 16 * 1024;
            int last = (int) ((size - 1) / piece);
            TorrentPieces.Snapshot snapshot = TorrentPieces.sample(i -> { throw new AssertionError("full scan"); },
                last + 1, 0, 0, last, 0, size, piece, 0, true);
            equal(size, snapshot.readableEnd(size - 1));
            equal(true, snapshot.openingReady && snapshot.tailReady);
        });
        test("progress below the old high-water mark after rechecking remains live", () -> {
            TorrentProgress p = budget();
            p.sample(0, false, 0, 1_000_000, 1_000_000);
            p.sample(MINUTE, true, 0, 0, 0);
            p.sample(2 * MINUTE, true, 500_000, 0, 0);
            for (int minute = 3; minute < 25; minute++)
                equal(null, p.sample(minute * MINUTE, false, 0, minute, minute));
        });
        test("a 90 GiB sparse resume still reserves unwritten physical bytes", () -> {
            long gb = 1024L * 1024 * 1024, size = 90 * gb;
            long quotaGrowth = StorageBudget.remaining(size, size);
            long diskGrowth = StorageBudget.remaining(size, 4096);
            equal(0L, quotaGrowth);
            equal(size - 4096, diskGrowth);
            equal(false, StorageBudget.fits(120 * gb, size, quotaGrowth, 20 * gb, diskGrowth, 5 * gb));
            equal(true, StorageBudget.fits(120 * gb, size, quotaGrowth, 100 * gb, diskGrowth, 5 * gb));
        });
        test("deleting sparse logical bytes does not invent physical free space", () -> {
            long gb = 1024L * 1024 * 1024;
            equal(false, StorageBudget.fits(120 * gb, 0, 90 * gb, 20 * gb, 90 * gb, 5 * gb));
            equal(false, StorageBudget.fits(80 * gb, 0, 90 * gb, 120 * gb, 90 * gb, 5 * gb));
            equal(15 * gb, StorageBudget.room(120 * gb, 0, 20 * gb, 5 * gb));
        });
        test("TV network buffers stay bounded on small heaps", () -> {
            TorrentTuning tuning = new TorrentTuning(4L * 1024 * 1024 * 1024, 128L * 1024 * 1024, 4);
            if ((long) tuning.connections * tuning.receiveBufferBytes + tuning.diskQueueBytes > 32L * 1024 * 1024)
                throw new AssertionError("Too much network buffer memory for the TV heap");
            equal(1, tuning.hashingThreads);
            if (tuning.ioThreads > 2) throw new AssertionError("Too many disk workers for USB playback");
        });
        test("diagnostics preserve network hosts and errors while removing credentials", () -> {
            String safe = DiagnosticText.sanitize("code=401 https://user:password@tracker.test/private-passkey/announce?token=SECRET "
                + "udp://tracker.test:6969/announce?passkey=HIDDEN magnet:?xt=urn:btih:privatehash&tr=SECRET "
                + "Authorization: Bearer TOPSECRET\nnext");
            contains(safe, "tracker.test");
            contains(safe, "code=401");
            for (String secret : new String[]{"password", "private-passkey", "SECRET", "HIDDEN", "privatehash", "TOPSECRET", "\n"})
                if (safe.contains(secret)) throw new AssertionError("Diagnostic leaked " + secret);
        });
        withFiles("final/partial collision keeps both sentinels", root -> {
            Path part = root.resolve("video.part"), target = root.resolve("video.mkv");
            Files.write(part, new byte[]{1, 2, 3});
            Files.write(target, new byte[]{4, 5, 6});
            rejects(() -> TorrentFiles.finish(part.toFile(), target.toFile(), 3));
            bytes(new byte[]{1, 2, 3}, part);
            bytes(new byte[]{4, 5, 6}, target);
        });
        withFiles("only the mapped file can be finalized", root -> {
            Path original = root.resolve("original.mkv"), part = root.resolve("video.part"), target = root.resolve("video.mkv");
            Files.write(original, new byte[]{7, 7, 7});
            Files.write(part, new byte[]{1, 2, 3});
            TorrentFiles.finish(part.toFile(), target.toFile(), 3);
            bytes(new byte[]{7, 7, 7}, original);
            bytes(new byte[]{1, 2, 3}, target);
            equal(false, Files.exists(part));
        });
        withFiles("missing mapped file never falls back to the original", root -> {
            Path original = root.resolve("original.mkv"), target = root.resolve("video.mkv");
            Files.write(original, new byte[]{7, 7, 7});
            rejects(() -> TorrentFiles.finish(root.resolve("video.part").toFile(), target.toFile(), 3));
            bytes(new byte[]{7, 7, 7}, original);
            equal(false, Files.exists(target));
        });
        withFiles("sparse logical length preserves data and grants no completion", root -> {
            Path part = root.resolve("video.part"), target = root.resolve("video.mkv");
            try (RandomAccessFile f = new RandomAccessFile(part.toFile(), "rw")) {
                f.write(new byte[]{3, 2, 1});
                f.setLength(97L * 1024 * 1024 * 1024);
            }
            TorrentFiles.validate(part.toFile(), target.toFile());
            equal(false, Files.exists(target));
            try (RandomAccessFile f = new RandomAccessFile(part.toFile(), "r")) {
                equal(3, f.read()); equal(2, f.read()); equal(1, f.read());
            }
        });
        withFiles("unexpected mapped size is not finalized", root -> {
            Path part = root.resolve("video.part"), target = root.resolve("video.mkv");
            Files.write(part, new byte[]{1, 2, 3});
            rejects(() -> TorrentFiles.finish(part.toFile(), target.toFile(), 4));
            bytes(new byte[]{1, 2, 3}, part);
            equal(false, Files.exists(target));
        });
        withFiles("symlink alias is refused and its sentinel survives", root -> {
            Path sentinel = root.resolve("sentinel"), part = root.resolve("video.part");
            Files.write(sentinel, new byte[]{5, 4, 3});
            Files.createSymbolicLink(part, sentinel);
            rejects(() -> TorrentFiles.validate(part.toFile(), root.resolve("video.mkv").toFile()));
            bytes(new byte[]{5, 4, 3}, sentinel);
        });
        withFiles("dangling symlink is refused", root -> {
            Path part = root.resolve("video.part");
            Files.createSymbolicLink(part, root.resolve("missing"));
            rejects(() -> TorrentFiles.validate(part.toFile(), root.resolve("video.mkv").toFile()));
            equal(true, Files.isSymbolicLink(part));
        });
        withFiles("mapped traversal and directories are refused", root -> {
            rejects(() -> TorrentFiles.validateCacheFile(root.toFile(), root.resolve("../outside.part").toFile()));
            Path part = Files.createDirectory(root.resolve("video.part"));
            rejects(() -> TorrentFiles.validate(part.toFile(), root.resolve("video.mkv").toFile()));
        });
        System.out.println("PASS: " + passed + " torrent policy/file checks (no JNI or Android exercised)");
    }

    private static TorrentProgress budget() { return new TorrentProgress(0, 5 * MINUTE, 10 * MINUTE, 10 * MINUTE); }
    private interface Checked { void run() throws Exception; }
    private interface FilesCheck { void run(Path root) throws Exception; }
    private static void test(String name, Checked check) throws Exception {
        try { check.run(); passed++; }
        catch (Throwable e) { throw new AssertionError(name, e); }
    }
    private static void withFiles(String name, FilesCheck check) throws Exception {
        test(name, () -> {
            Path root = Files.createTempDirectory("local-cache-test-");
            try { check.run(root); }
            finally {
                try (var paths = Files.walk(root)) {
                    for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
                }
            }
        });
    }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    private static void contains(String actual, String expected) {
        if (actual == null || !actual.contains(expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void bytes(byte[] expected, Path path) throws IOException {
        if (!Arrays.equals(expected, Files.readAllBytes(path))) throw new AssertionError("sentinel changed: " + path.getFileName());
    }
    private static void rejects(Checked operation) throws Exception {
        try { operation.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("Expected storage path refusal");
    }
}
