package app.localcache.torrent;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

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
