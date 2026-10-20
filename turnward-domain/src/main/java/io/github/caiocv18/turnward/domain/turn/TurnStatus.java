package io.github.caiocv18.turnward.domain.turn;

/**
 * Where the turn is in its life. The last three are terminal: no event is accepted after them.
 *
 * <p>The waiting statuses are derived, never set by an event: the turn waits only when no effect is left to run,
 * so a worker may drop the lease exactly when the status says so (design note, section 5).
 */
public enum TurnStatus {
    NOT_STARTED,
    /** There is work for a worker. */
    ACTIVE,
    /** Every approval has been asked for and nothing else can run until a human answers one. */
    AWAITING_APPROVAL,
    /** A call with side effects has an unknown outcome; nothing runs until a human reconciles it. */
    AWAITING_RECONCILIATION,
    /** Paused by an operator, by the token budget or both; no new effect may start. */
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** True when no worker should hold the turn: it ended, is suspended or waits for a human. */
    public boolean isIdle() {
        return this != NOT_STARTED && this != ACTIVE;
    }
}
