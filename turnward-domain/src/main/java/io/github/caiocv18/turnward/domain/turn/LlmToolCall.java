package io.github.caiocv18.turnward.domain.turn;

import java.util.Map;
import java.util.Objects;

/**
 * A tool call as the model asked for it, before the turn assigns a risk level and proposes it.
 */
public record LlmToolCall(String toolCallId, String tool, Map<String, String> args) {

    public LlmToolCall {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(tool, "tool");
        args = Map.copyOf(Objects.requireNonNull(args, "args"));
    }
}
