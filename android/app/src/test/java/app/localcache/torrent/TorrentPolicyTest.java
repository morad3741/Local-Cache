package app.localcache.torrent;

import org.junit.Test;

public class TorrentPolicyTest {
    @Test
    public void timePieceAndFileSafety() throws Exception {
        TorrentPolicyChecks.runAll();
    }
}
