package io.github.caiocv18.turnward.domain.tool;

/**
 * How much harm a tool can do, assigned when the call is proposed.
 */
public enum RiskLevel {
    /** Reads only; repeating it is harmless. */
    READ_ONLY,
    /** Changes something outside the turn; repeating it blindly is not safe. */
    SIDE_EFFECT,
    /** Changes something that matters enough to need a human first. */
    DANGEROUS
}
