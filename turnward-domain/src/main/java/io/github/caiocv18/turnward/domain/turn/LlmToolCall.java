package io.github.caiocv18.turnward.domain.turn;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A tool call as the model asked for it, before the turn assigns a risk level and proposes it.
 */
public record LlmToolCall(String toolCallId, String tool, Map<String, String> args) {

    public LlmToolCall {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(tool, "tool");
        // Sorted, because these args are sent back to the model on every later call, and Map.copyOf's order changes
        // between JVMs: the same stream must give the same prompt.
        args = Collections.unmodifiableSortedMap(new TreeMap<>(Map.copyOf(Objects.requireNonNull(args, "args"))));
    }
}
