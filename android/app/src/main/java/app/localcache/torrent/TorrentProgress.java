package app.localcache.torrent;

/** Time budgets use network time only; checking has its own progress watchdog. */
public final class TorrentProgress {
    private final long firstBytesMs;
    private final long stallMs;
    private final long checkingStallMs;
    private long previousAt;
    private long networkMs;
    private long progressAt;
    private long checkProgressAt;
    private int checkProgress = -1;
    private long mostContiguous;
    private long mostVerified;
    private boolean wasChecking;

    public TorrentProgress(long now, long firstBytesMs, long stallMs, long checkingStallMs) {
        previousAt = now;
        checkProgressAt = now;
        this.firstBytesMs = firstBytesMs;
        this.stallMs = stallMs;
        this.checkingStallMs = checkingStallMs;
    }

    /** Returns a reason to stop, or null. The caller supplies a monotonic clock. */
    public String sample(long now, boolean checking, int progressPpm, long contiguous, long verified) {
        long elapsed = Math.max(0, now - previousAt);
        previousAt = now;
        if (checking) {
            if (!wasChecking || progressPpm > checkProgress) {
                checkProgressAt = now;
                checkProgress = progressPpm;
            }
            wasChecking = true;
            if (now - checkProgressAt >= checkingStallMs) {
                return "Storage check made no progress for 10 min; check the USB drive";
            }
            return null;
        }

        // The first sample after checking cannot tell when checking ended. Charge none
        // of that interval to the network rather than fail a healthy, slow USB check.
        if (!wasChecking) networkMs += elapsed;
        wasChecking = false;
        if (contiguous > mostContiguous || verified > mostVerified) progressAt = networkMs;
        mostContiguous = Math.max(mostContiguous, contiguous);
        mostVerified = Math.max(mostVerified, verified);
        if (mostContiguous == 0 && networkMs >= firstBytesMs) {
            return "Torrent sent no usable opening data in 5 min of network time";
        }
        if (mostContiguous > 0 && networkMs - progressAt >= stallMs) {
            return "Torrent made no download progress for 10 min";
        }
        return null;
    }

    /** Uses only the worker's immutable piece snapshot, including nonzero file offsets. */
    public static long readableEnd(boolean[] have, long fileOffset, long fileSize,
                                   long pieceLength, long offset, int lookahead) {
        if (have == null || pieceLength <= 0 || offset < 0 || offset >= fileSize) return offset;
        int first = (int) ((fileOffset + offset) / pieceLength);
        if (first < 0 || first >= have.length || !have[first]) return offset;
        int last = (int) ((fileOffset + fileSize - 1) / pieceLength);
        int stop = Math.min(Math.min(last, have.length - 1), first + lookahead);
        int piece = first + 1;
        while (piece <= stop && have[piece]) piece++;
        return Math.min(fileSize, Math.max(offset, (long) piece * pieceLength - fileOffset));
    }
}
