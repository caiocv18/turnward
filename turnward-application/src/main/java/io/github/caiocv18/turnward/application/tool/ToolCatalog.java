package io.github.caiocv18.turnward.application.tool;

import io.github.caiocv18.turnward.domain.tool.RiskLevel;
import java.util.List;
import java.util.Objects;

/** The tools a turn may use and the risk of each one. */
public record ToolCatalog(List<ToolDefinition> tools) {

    public ToolCatalog {
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        long distinct = tools.stream().map(ToolDefinition::name).distinct().count();
        if (distinct != tools.size()) {
            throw new IllegalArgumentException("tool names must be unique");
        }
    }

    /**
     * The risk of a tool. A name the catalog does not know, such as one the model made up, is {@code DANGEROUS}: it
     * waits for a human instead of running.
     */
    public RiskLevel riskOf(String tool) {
        Objects.requireNonNull(tool, "tool");
        return tools.stream()
                .filter(definition -> definition.name().equals(tool))
                .map(ToolDefinition::riskLevel)
                .findFirst()
                .orElse(RiskLevel.DANGEROUS);
    }
}
