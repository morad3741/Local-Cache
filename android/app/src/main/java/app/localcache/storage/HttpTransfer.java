package app.localcache.storage;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates an actual GET, including offsets beyond 4 GiB, before a partial is appended. */
public final class HttpTransfer {
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    public final long offset;
    public final long total;
    public final long responseBytes;

    private HttpTransfer(long offset, long total, long responseBytes) {
        this.offset = offset;
        this.total = total;
        this.responseBytes = responseBytes;
    }

    public static HttpTransfer response(int code, String range, long requestedOffset, long length) throws IOException {
        if (code == 200) return new HttpTransfer(0, length, length);
        if (code != 206) throw new IOException("Upstream HTTP " + code);
        Matcher match = RANGE.matcher(range == null ? "" : range.trim());
        if (!match.matches()) throw new IOException("Missing or invalid Content-Range");
        try {
            long start = Long.parseLong(match.group(1)), end = Long.parseLong(match.group(2));
            long total = "*".equals(match.group(3)) ? -1 : Long.parseLong(match.group(3));
            if (start != requestedOffset || end < start || end == Long.MAX_VALUE || (total >= 0 && end >= total)) {
                throw new IOException("Unexpected Content-Range offset or size");
            }
            long bytes = end - start + 1;
            if (length >= 0 && length != bytes) throw new IOException("Content-Length does not match Content-Range");
            // Without a representation size, EOF cannot establish that a 206 is the final range.
            if (total < 0) throw new IOException("Partial response has no total size");
            return new HttpTransfer(start, total, bytes);
        } catch (NumberFormatException e) {
            throw new IOException("Content-Range exceeds supported size", e);
        }
    }

    public void verifyComplete(long downloaded) throws IOException {
        if (responseBytes >= 0 && downloaded - offset != responseBytes) {
            throw new IOException("Incomplete HTTP response: received " + (downloaded - offset) + " of " + responseBytes);
        }
        if (total >= 0 && downloaded != total) {
            throw new IOException("Incomplete download: received " + downloaded + " of " + total);
        }
    }
}
