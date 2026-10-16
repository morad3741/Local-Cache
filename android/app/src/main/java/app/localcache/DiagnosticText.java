package app.localcache;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preserve hosts and error details without exporting signed links or tracker passkeys. */
public final class DiagnosticText {
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|udp|wss?)://[^\\s<>\"']+");
    private static final Pattern MAGNET = Pattern.compile("(?i)magnet:\\?[^\\s<>\"']+");
    private static final Pattern SECRET = Pattern.compile(
        "(?i)(authorization|token|api[_-]?key|passkey|password|secret)([=:]\\s*)[^\\s,;]+");
    private static final Pattern AUTH = Pattern.compile("(?i)\\bauthorization\\s*[:=]\\s*(?:(?:bearer|basic)\\s+)?[^\\s,;]+");

    private DiagnosticText() {}

    public static String sanitize(String message) {
        String input = AUTH.matcher(MAGNET.matcher(message).replaceAll("magnet:[redacted]"))
            .replaceAll("authorization=[redacted]");
        Matcher matcher = URL.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = "[url redacted]";
            try {
                URI uri = new URI(matcher.group());
                if (uri.getHost() != null) replacement = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + "/[redacted]";
            } catch (Exception ignored) { }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        String safe = SECRET.matcher(result.toString()).replaceAll("$1$2[redacted]")
            .replace('\n', ' ').replace('\r', ' ');
        return safe.length() > 2048 ? safe.substring(0, 2048) + "…" : safe;
    }
}
