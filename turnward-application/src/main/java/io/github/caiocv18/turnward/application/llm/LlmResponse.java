package io.github.caiocv18.turnward.application.llm;

import io.github.caiocv18.turnward.domain.turn.LlmToolCall;
import java.util.List;
import java.util.Objects;

/**
 * What the model answered.
 *
 * @param toolCalls what it asked for, not run; the ids in here are the provider's and are not used
 */
public record LlmResponse(String text, long tokensIn, long tokensOut, List<LlmToolCall> toolCalls) {

    public LlmResponse {
        Objects.requireNonNull(text, "text");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    }
}
