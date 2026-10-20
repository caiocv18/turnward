package io.github.caiocv18.turnward.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ToolPolicyTest {

    @Test
    void onlyDangerousToolsNeedApproval() {
        assertThat(ToolPolicy.requiresApproval(RiskLevel.READ_ONLY)).isFalse();
        assertThat(ToolPolicy.requiresApproval(RiskLevel.SIDE_EFFECT)).isFalse();
        assertThat(ToolPolicy.requiresApproval(RiskLevel.DANGEROUS)).isTrue();
    }

    @Test
    void onlyReadOnlyToolsAreRepeatedAfterACrash() {
        assertThat(ToolPolicy.safeToRetryAfterCrash(RiskLevel.READ_ONLY)).isTrue();
        assertThat(ToolPolicy.safeToRetryAfterCrash(RiskLevel.SIDE_EFFECT)).isFalse();
        assertThat(ToolPolicy.safeToRetryAfterCrash(RiskLevel.DANGEROUS)).isFalse();
    }

    @Test
    void rejectsNullRiskLevel() {
        assertThatNullPointerException().isThrownBy(() -> ToolPolicy.requiresApproval(null));
        assertThatNullPointerException().isThrownBy(() -> ToolPolicy.safeToRetryAfterCrash(null));
    }
}
