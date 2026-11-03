package io.github.caiocv18.turnward.application.turn;

import io.github.caiocv18.turnward.application.eventstore.ConcurrencyConflictException;
import io.github.caiocv18.turnward.application.eventstore.RecordedEvent;
import io.github.caiocv18.turnward.application.port.EventStore;
import io.github.caiocv18.turnward.domain.TurnId;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The port's contract without a database: versions from 1 with no gaps, and a conflict at the wrong version. A stream
 * also keeps the tenant of its first append, so every test proves that the use cases pass the right one.
 */
final class InMemoryEventStore implements EventStore {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

    private final Map<TurnId, List<RecordedEvent>> streams = new HashMap<>();
    private final Map<TurnId, String> owners = new HashMap<>();
    private Predicate<TurnEvent> raceOn = event -> false;
    private TurnEvent otherWriter;

    /** Another writer gets in right before the next append of a matching event, so that append conflicts. */
    void loseTheRaceOn(Predicate<TurnEvent> condition, TurnEvent writtenByTheOther) {
        raceOn = condition;
        otherWriter = writtenByTheOther;
    }

    @Override
    public void append(TurnId streamId, String tenantId, long expectedVersion, List<TurnEvent> events) {
        if (!owners.computeIfAbsent(streamId, id -> tenantId).equals(tenantId)) {
            throw new AssertionError("stream " + streamId + " belongs to " + owners.get(streamId) + ", not " + tenantId);
        }
        List<RecordedEvent> stream = streams.computeIfAbsent(streamId, id -> new ArrayList<>());
        if (raceOn.test(events.getFirst())) {
            raceOn = event -> false;
            add(stream, otherWriter);
        }
        if (stream.size() != expectedVersion) {
            throw new ConcurrencyConflictException(streamId, expectedVersion, "stream is at " + stream.size());
        }
        events.forEach(event -> add(stream, event));
    }

    @Override
    public List<RecordedEvent> load(TurnId streamId) {
        return List.copyOf(streams.getOrDefault(streamId, List.of()));
    }

    private static void add(List<RecordedEvent> stream, TurnEvent event) {
        stream.add(new RecordedEvent(UUID.randomUUID(), stream.size() + 1L, NOW, event));
    }
}
