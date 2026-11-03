package io.github.caiocv18.turnward.application.llm;

import io.github.caiocv18.turnward.domain.turn.LlmToolCall;
import java.util.List;
import java.util.Objects;

/** One entry of the conversation sent to the model, rebuilt from the stream on every call. */
public sealed interface LlmMessage {

    record User(String text) implements LlmMessage {
        public User {
            Objects.requireNonNull(text, "text");
        }
    }

    record Assistant(String text, List<LlmToolCall> toolCalls) implements LlmMessage {
        public Assistant {
            Objects.requireNonNull(text, "text");
            toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        }
    }

    /** What came of a tool call: its result, or why it failed or never ran. */
    record ToolResult(String toolCallId, String tool, String content) implements LlmMessage {
        public ToolResult {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(content, "content");
        }
    }
}
