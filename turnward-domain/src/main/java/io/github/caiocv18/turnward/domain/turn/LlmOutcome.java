package io.github.caiocv18.turnward.domain.turn;

/**
 * What the last finished model call left behind.
 */
public enum LlmOutcome {
    /** No model call has finished yet. */
    NONE,
    /** The model answered without asking for tools: the turn can complete. */
    ANSWERED,
    /** The model asked for tools: their results go back to it. */
    REQUESTED_TOOLS,
    FAILED
}
