package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562, section 5.7): 48 bits of Unix epoch milliseconds, version 7, then 74 random bits behind the
 * IETF variant. Ids sort by creation millisecond; order inside one millisecond is random, which is enough because
 * the order of a stream is its {@code version}, not its ids.
 */
final class UuidV7 {

    private static final long VERSION_7 = 0x7000L;
    private static final long IETF_VARIANT = 0x8000_0000_0000_0000L;
    private static final long VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private final Clock clock;
    private final SecureRandom random;

    UuidV7(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    UUID next() {
        long millis = clock.millis();
        long mostSignificant = (millis << 16) | VERSION_7 | (random.nextInt() & 0x0FFF);
        long leastSignificant = (random.nextLong() & VARIANT_MASK) | IETF_VARIANT;
        return new UUID(mostSignificant, leastSignificant);
    }
}
