package app.localcache.torrent;

import java.util.function.IntPredicate;

/** Bounded worker-side JNI reads; no per-torrent Java boolean/priority arrays. */
public final class TorrentPieces {
    public static final int PREFIX_SCAN_LIMIT = 4096;
    public static final int LOOKAHEAD = 1024;

    private TorrentPieces() {}

    public static final class Snapshot {
        public final int nextPiece;
        public final long contiguousBytes;
        public final boolean openingReady;
        public final boolean tailReady;
        private final boolean[] seekBits;
        private final int seekFirst;
        private final long fileOffset, fileSize, pieceLength;

        private Snapshot(int nextPiece, long contiguousBytes, boolean openingReady, boolean tailReady,
                         boolean[] seekBits, int seekFirst, long fileOffset, long fileSize, long pieceLength) {
            this.nextPiece = nextPiece;
            this.contiguousBytes = contiguousBytes;
            this.openingReady = openingReady;
            this.tailReady = tailReady;
            this.seekBits = seekBits;
            this.seekFirst = seekFirst;
            this.fileOffset = fileOffset;
            this.fileSize = fileSize;
            this.pieceLength = pieceLength;
        }

        public long readableEnd(long offset) {
            if (offset < contiguousBytes) return contiguousBytes;
            return TorrentProgress.readableEnd(seekBits, seekFirst, fileOffset, fileSize,
                pieceLength, offset, LOOKAHEAD);
        }
    }

    /** finished must come from a non-checking native status for all wanted files. */
    public static Snapshot sample(IntPredicate have, int bitCount, int cursor, int firstPiece, int lastPiece,
                                  long fileOffset, long fileSize, long pieceLength, long playhead,
                                  boolean finished) {
        int stop = (int) Math.min((long) lastPiece + 1, (long) cursor + PREFIX_SCAN_LIMIT);
        if (finished) cursor = lastPiece + 1;
        else while (cursor < stop && cursor < bitCount && have.test(cursor)) cursor++;
        long prefix = Math.max(0, Math.min(fileSize, (long) cursor * pieceLength - fileOffset));
        int seekFirst = (int) ((fileOffset + Math.max(0, Math.min(fileSize - 1, playhead))) / pieceLength);
        int count = Math.max(0, Math.min(LOOKAHEAD + 1, Math.min(lastPiece + 1, bitCount) - seekFirst));
        boolean[] seek = new boolean[count];
        for (int i = 0; i < count; i++) seek[i] = finished || have.test(seekFirst + i);
        boolean opening = finished || (firstPiece < bitCount && have.test(firstPiece));
        boolean tail = finished || (lastPiece < bitCount && have.test(lastPiece));
        return new Snapshot(cursor, prefix, opening, tail, seek, seekFirst, fileOffset, fileSize, pieceLength);
    }
}
