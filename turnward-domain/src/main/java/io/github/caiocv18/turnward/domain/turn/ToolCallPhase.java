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
    /** A call with side effects has no known outcome, after a crash or a timeout; a human must reconcile it. */
    OUTCOME_UNKNOWN;

    /** No further step is owed to this call. */
    public boolean isSettled() {
        return this == DENIED || this == EXPIRED || this == COMPLETED || this == FAILED;
    }
}
