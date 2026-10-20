package io.github.caiocv18.turnward.domain.turn;

/**
 * Life of one proposed tool call inside a turn.
 */
public enum ToolCallPhase {
    PROPOSED,
    APPROVAL_REQUESTED,
    APPROVED,
    DENIED,
    EXPIRED,
    /** The effect may have happened: the call started and no outcome was recorded yet. */
    STARTED,
    COMPLETED,
    FAILED,
    /** A crash left a call with side effects without outcome; a human must reconcile it. */
    OUTCOME_UNKNOWN;

    /** No further step is owed to this call. */
    public boolean isSettled() {
        return this == DENIED || this == EXPIRED || this == COMPLETED || this == FAILED;
    }
}
