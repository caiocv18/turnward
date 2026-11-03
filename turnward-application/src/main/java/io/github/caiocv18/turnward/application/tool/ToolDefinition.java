package io.github.caiocv18.turnward.application.tool;

import io.github.caiocv18.turnward.domain.tool.RiskLevel;
import java.util.List;
import java.util.Objects;

/**
 * A tool the model may ask for.
 *
 * @param parameters names of its arguments, all strings, because a tool call's args are {@code Map<String, String>}
 * @param riskLevel assigned here, by the system, and never taken from the model
 */
public record ToolDefinition(String name, String description, List<String> parameters, RiskLevel riskLevel) {

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        Objects.requireNonNull(riskLevel, "riskLevel");
    }
}
