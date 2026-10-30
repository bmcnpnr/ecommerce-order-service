package com.ecommerce.order.logging;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("security")
class LogSanitizerTest {

    @Test
    void stripsCrLfAndOtherControlCharacters() {
        String forged = "alice\r\n2026-01-01 [main] ERROR forged\t\u0000\u001b[31m\u0085x y z";

        String cleaned = LogSanitizer.clean(forged);

        assertThat(cleaned).isEqualTo("alice2026-01-01 [main] ERROR forged[31mxyz");
    }

    @Test
    void nullAndNonStringValuesPrintLikeBefore() {
        assertThat(LogSanitizer.clean(null)).isEqualTo("null");
        assertThat(LogSanitizer.clean(42L)).isEqualTo("42");
        assertThat(LogSanitizer.clean(new java.math.BigDecimal("12.50"))).isEqualTo("12.50");
    }

    @Test
    void valuesUpTo256CharactersAreUntouched() {
        String exact = "a".repeat(256);

        assertThat(LogSanitizer.clean(exact)).isEqualTo(exact);
    }

    @Test
    void longerValuesAreCutAt256WithAVisibleMarker() {
        String cleaned = LogSanitizer.clean("a".repeat(257));

        assertThat(cleaned).isEqualTo("a".repeat(256) + LogSanitizer.TRUNCATION_MARKER);
        assertThat(LogSanitizer.clean("A".repeat(1_000_000))).hasSize(256 + LogSanitizer.TRUNCATION_MARKER.length());
    }

    @Test
    void controlCharactersDoNotCountTowardsTheCap() {
        String cleaned = LogSanitizer.clean("a\n".repeat(300));

        assertThat(cleaned).isEqualTo("a".repeat(256) + LogSanitizer.TRUNCATION_MARKER);
        assertThat(LogSanitizer.clean("a\n".repeat(256))).isEqualTo("a".repeat(256));
    }

    @Test
    void aSurrogatePairIsNeverSplitAtTheCut() {
        String cleaned = LogSanitizer.clean("a".repeat(255) + "😀" + "tail");

        assertThat(cleaned).isEqualTo("a".repeat(255) + LogSanitizer.TRUNCATION_MARKER);
    }
}
