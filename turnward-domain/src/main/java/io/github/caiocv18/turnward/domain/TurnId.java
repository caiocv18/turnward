package io.github.caiocv18.turnward.domain;

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

    @Override
    public String toString() {
        return value.toString();
    }
}
