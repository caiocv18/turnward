package io.github.caiocv18.turnward.domain.turn;

/**
 * Why a turn is suspended. Causes are tracked separately so that resuming one never lifts the other.
 */
public enum SuspensionCause {
    /** Set by {@code TurnSuspended}. */
    OPERATOR,
    /** Set by {@code BudgetExceeded}; lifted once the tenant has tokens again. */
    BUDGET
}
