package com.ecommerce.order.logging;

/**
 * Every value that comes out of a Kafka event passes through here before it is logged (S-95, S-96):
 * control characters (CR, LF, tab, NUL, ESC, U+0085, U+2028/2029 ...) are removed so one event cannot
 * forge extra log lines, and the value is capped at {@value #MAX_LENGTH} characters followed by a visible marker.
 */
public final class LogSanitizer {

    public static final int MAX_LENGTH = 256;
    public static final String TRUNCATION_MARKER = "...[truncated]";

    private LogSanitizer() { }

    public static String clean(Object value) {
        String text = String.valueOf(value);
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_LENGTH + TRUNCATION_MARKER.length()));
        boolean truncated = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isStripped(c)) {
                continue;
            }
            if (out.length() == MAX_LENGTH) {
                truncated = true;
                break;
            }
            out.append(c);
        }
        if (truncated) {
            // do not leave half a surrogate pair at the cut
            if (Character.isHighSurrogate(out.charAt(out.length() - 1))) {
                out.setLength(out.length() - 1);
            }
            out.append(TRUNCATION_MARKER);
        }
        return out.toString();
    }

    /** ISO control characters plus the Unicode line and paragraph separators. */
    private static boolean isStripped(char c) {
        return Character.isISOControl(c) || c == 0x2028 || c == 0x2029;
    }
}
