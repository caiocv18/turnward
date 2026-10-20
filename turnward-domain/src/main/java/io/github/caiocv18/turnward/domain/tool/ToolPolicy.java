package io.github.caiocv18.turnward.domain.tool;

import java.util.Objects;

/**
 * Pure rules derived from the risk level. Who may approve, per tenant and tool, is decided outside the domain
 * (ADR 7); whether approval is needed at all is decided here.
 */
public final class ToolPolicy {

    private ToolPolicy() {
    }

    public static boolean requiresApproval(RiskLevel riskLevel) {
        Objects.requireNonNull(riskLevel, "riskLevel");
        return riskLevel == RiskLevel.DANGEROUS;
    }

    /**
     * A call that started and has no recorded outcome may be repeated only when repeating it cannot change anything
     * (design note, section 5). Everything else goes to reconciliation.
     */
    public static boolean safeToRetryAfterCrash(RiskLevel riskLevel) {
        Objects.requireNonNull(riskLevel, "riskLevel");
        return riskLevel == RiskLevel.READ_ONLY;
    }
}
