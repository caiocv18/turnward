package io.github.caiocv18.turnward.application.turn;

import io.github.caiocv18.turnward.application.eventstore.ConcurrencyConflictException;
import io.github.caiocv18.turnward.application.port.EventStore;
import io.github.caiocv18.turnward.domain.TurnId;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import java.util.List;
import java.util.Objects;

/**
 * Opens the turn of one firing of an alert. Idempotent without a table of its own: the turn id is derived from the
 * alert and the append expects version 0, so a redelivery finds the stream taken and returns the same turn.
 */
public final class StartTurn {

    private final EventStore store;

    public StartTurn(EventStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public Result start(TurnEvent.TurnStarted started) {
        Objects.requireNonNull(started, "started");
        TurnId turnId = TurnId.forAlert(started.tenantId(), started.alertFingerprint(), started.alert().startsAt());
        try {
            store.append(turnId, started.tenantId(), 0, List.of(started));
            return new Result(turnId, true);
        } catch (ConcurrencyConflictException redelivered) {
            return new Result(turnId, false);
        }
    }

    /** @param created false when the alert was already delivered and the turn existed */
    public record Result(TurnId turnId, boolean created) {
    }
}
