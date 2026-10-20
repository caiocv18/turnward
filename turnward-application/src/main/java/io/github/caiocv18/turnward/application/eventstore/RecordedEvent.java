package io.github.caiocv18.turnward.application.eventstore;

import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A stored event: the domain event plus what the store stamped on it. */
public record RecordedEvent(UUID eventId, long version, Instant occurredAt, TurnEvent event) {

    public RecordedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(event, "event");
        if (version < 1) {
            throw new IllegalArgumentException("version starts at 1, got " + version);
        }
    }
}
