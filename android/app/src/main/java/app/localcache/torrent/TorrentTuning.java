package app.localcache.torrent;

/** Limits are independent of movie size and use actual device/app memory, not a model name. */
public final class TorrentTuning {
    public final int connections, diskQueueBytes, receiveBufferBytes, checkingBlocks, metadataBytes;
    public final int ioThreads, hashingThreads;

    public TorrentTuning(long totalMemory, long heapLimit, int cores) {
        boolean constrained = totalMemory <= 2L * 1024 * 1024 * 1024 || heapLimit <= 192L * 1024 * 1024;
        connections = constrained ? 40 : 64;
        diskQueueBytes = (constrained ? 4 : 8) * 1024 * 1024;
        receiveBufferBytes = (constrained ? 256 : 512) * 1024;
        checkingBlocks = constrained ? 256 : 512; // libtorrent units: 16 KiB blocks.
        metadataBytes = 16 * 1024 * 1024;
        ioThreads = cores <= 2 ? 1 : 2;
        hashingThreads = 1; // Leave CPU for video decoding and the TV UI.
    }

    @Override public String toString() {
        return "connections=" + connections + " diskQueue=" + diskQueueBytes + " peerRecv=" + receiveBufferBytes
            + " checkBlocks=" + checkingBlocks + " metadataLimit=" + metadataBytes
            + " ioThreads=" + ioThreads + " hashThreads=" + hashingThreads;
    }
}
