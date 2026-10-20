package io.github.caiocv18.turnward.domain.turn;

import io.github.caiocv18.turnward.domain.tool.RiskLevel;
import java.util.Map;
import java.util.Objects;

/**
 * A proposed tool call as the fold sees it.
 */
public record ToolCall(String toolCallId, String tool, Map<String, String> args, RiskLevel riskLevel,
        ToolCallPhase phase) {

    public ToolCall {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(tool, "tool");
        args = Map.copyOf(Objects.requireNonNull(args, "args"));
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(phase, "phase");
    }

    ToolCall withPhase(ToolCallPhase next) {
        return new ToolCall(toolCallId, tool, args, riskLevel, next);
    }
}
