package io.github.caiocv18.turnward.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a turn. One turn is one event stream, so this is also the stream id.
 */
public record TurnId(UUID value) {

    public TurnId {
        Objects.requireNonNull(value, "value");
    }

    public static TurnId of(String value) {
        Objects.requireNonNull(value, "value");
        return new TurnId(UUID.fromString(value));
    }

    /**
     * The turn of one firing of one alert. The id is derived, so a redelivered alert lands on the same stream and
     * appending at version 0 is what makes the webhook idempotent. {@code startsAt} tells firings apart: an alert
     * that resolves and fires again is a new turn.
     */
    public static TurnId forAlert(String tenantId, String alertFingerprint, Instant startsAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(alertFingerprint, "alertFingerprint");
        Objects.requireNonNull(startsAt, "startsAt");
        // The length keeps ("a|b", "c") apart from ("a", "b|c"): one tenant's alert must not pass as another's
        // redelivery.
        String name = tenantId.length() + ":" + tenantId + '|' + alertFingerprint + '|' + startsAt;
        return new TurnId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
