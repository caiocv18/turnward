package io.github.caiocv18.turnward.application.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.caiocv18.turnward.domain.tool.RiskLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolCatalogTest {

    private static final ToolDefinition QUERY =
            new ToolDefinition("query_metrics", "Reads a metric", List.of("service"), RiskLevel.READ_ONLY);

    @Test
    void aKnownToolHasTheRiskItWasGiven() {
        assertThat(new ToolCatalog(List.of(QUERY)).riskOf("query_metrics")).isEqualTo(RiskLevel.READ_ONLY);
    }

    @Test
    void aToolTheCatalogDoesNotKnowIsDangerous() {
        assertThat(new ToolCatalog(List.of(QUERY)).riskOf("delete_everything")).isEqualTo(RiskLevel.DANGEROUS);
    }

    @Test
    void twoToolsCannotShareAName() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCatalog(List.of(QUERY, QUERY)));
    }
}
