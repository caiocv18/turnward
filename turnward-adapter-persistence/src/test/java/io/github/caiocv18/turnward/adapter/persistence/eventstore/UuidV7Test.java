package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    private static final Instant AT = Instant.parse("2026-10-20T12:34:56.789Z");

    @Test
    void isVersion7WithTheIetfVariant() {
        UUID id = generatorAt(AT).next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheClockMillisecondsInTheFirst48Bits() {
        UUID id = generatorAt(AT).next();

        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(AT.toEpochMilli());
        // 1792499696789 ms = 0x01a1594ec495, then the version nibble.
        assertThat(id.toString()).startsWith("01a1594e-c495-7");
    }

    @Test
    void sortsByMillisecond() {
        UUID earlier = generatorAt(AT).next();
        UUID later = generatorAt(AT.plusMillis(1)).next();

        // UUID.compareTo compares signed longs; the textual form is the order databases use.
        assertThat(earlier.toString()).isLessThan(later.toString());
    }

    @Test
    void idsInTheSameMillisecondDiffer() {
        UuidV7 generator = generatorAt(AT);
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.next());
        }

        assertThat(ids).hasSize(10_000);
    }

    private static UuidV7 generatorAt(Instant instant) {
        return new UuidV7(Clock.fixed(instant, ZoneOffset.UTC), new SecureRandom());
    }
}
