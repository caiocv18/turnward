package io.github.caiocv18.turnward.application.llm;

import io.github.caiocv18.turnward.application.tool.ToolDefinition;
import java.util.List;
import java.util.Objects;

/** What the model is asked: instructions, the conversation so far and the tools it may ask for. */
public record LlmRequest(String system, List<LlmMessage> messages, List<ToolDefinition> tools) {

    public LlmRequest {
        Objects.requireNonNull(system, "system");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    }
}
