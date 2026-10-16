package app.localcache.torrent;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** Only the explicitly mapped cache file may become the finished video. */
public final class TorrentFiles {
    private TorrentFiles() {}

    public static void validate(File part, File target) throws IOException {
        File root = part.getAbsoluteFile().getParentFile().getCanonicalFile();
        if (!root.equals(target.getAbsoluteFile().getParentFile().getCanonicalFile())
                || part.getName().equals(target.getName())) {
            throw new IOException("Torrent cache paths must be distinct files in the cache folder");
        }
        validateCacheFile(root, part);
        validateCacheFile(root, target);
        if (target.exists()) throw new IOException("Finished cache path already exists; keeping both files");
    }

    public static void validateCacheFile(File directory, File file) throws IOException {
        File root = directory.getCanonicalFile();
        File expected = new File(root, file.getName());
        String[] names = root.list();
        boolean dangling = !file.exists() && names != null && Arrays.asList(names).contains(file.getName());
        if (!expected.equals(file.getCanonicalFile()) || dangling || (file.exists() && !file.isFile())) {
            throw new IOException("Unsafe or unusable torrent cache path: " + file.getName());
        }
    }

    /** Call only after all selected pieces are verified and native disk IO has closed. */
    public static void finish(File part, File target, long expectedSize) throws IOException {
        validate(part, target);
        if (expectedSize <= 0 || !part.isFile() || part.length() != expectedSize) {
            throw new IOException("Verified torrent cache file is missing or has an unexpected size");
        }
        if (!part.renameTo(target)) throw new IOException("Could not finalize the verified torrent cache file");
    }
}
