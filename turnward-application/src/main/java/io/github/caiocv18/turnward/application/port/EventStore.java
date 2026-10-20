package io.github.caiocv18.turnward.application.port;

import io.github.caiocv18.turnward.application.eventstore.ConcurrencyConflictException;
import io.github.caiocv18.turnward.application.eventstore.RecordedEvent;
import io.github.caiocv18.turnward.domain.TurnId;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import java.util.List;

/**
 * Append-only store of turn events, one stream per turn (ADR-0002).
 *
 * <p>Versions start at 1 and have no gaps: the n-th event of a stream has version n, so a stream's version is the
 * {@code TurnState.version} of its fold.
 */
public interface EventStore {

    /**
     * Appends {@code events} after {@code expectedVersion}, all or nothing.
     *
     * @param tenantId owner of the turn, written on every row; the caller reads it from the folded state, because
     *     only {@code TurnStarted} carries it
     * @param expectedVersion the stream version the caller folded, 0 for a new stream
     * @throws ConcurrencyConflictException when the stream is not at {@code expectedVersion}, including when
     *     another writer appended first; nothing is written. Inside a caller's transaction the conflict rolls that
     *     transaction back too, so the retry (reload, fold, decide) starts a new one.
     */
    void append(TurnId streamId, String tenantId, long expectedVersion, List<TurnEvent> events);

    /** Every event of the stream in version order; empty when the stream does not exist. */
    List<RecordedEvent> load(TurnId streamId);
}
