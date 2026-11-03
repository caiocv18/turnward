package io.github.caiocv18.turnward.application.turn;

import io.github.caiocv18.turnward.application.llm.LlmMessage;
import io.github.caiocv18.turnward.domain.turn.AlertContext;
import io.github.caiocv18.turnward.domain.turn.LlmToolCall;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reads what the model needs out of a stream. The stream is the only memory of a turn, so a worker that reloads it
 * after a crash sends the model exactly what the dead one would have.
 */
final class TurnHistory {

    private TurnHistory() {
    }

    /**
     * The conversation so far. A failed or lost model call left no message, and each call's results follow it in the
     * order the model asked for them, not the order they ran: an approved call runs after the others.
     */
    static List<LlmMessage> messages(List<TurnEvent> events) {
        Map<String, String> outcomes = outcomes(events);
        List<LlmMessage> messages = new ArrayList<>();
        for (TurnEvent event : events) {
            switch (event) {
                case TurnEvent.TurnStarted started -> messages.add(new LlmMessage.User(describe(started)));
                case TurnEvent.LlmCallCompleted completed -> {
                    messages.add(new LlmMessage.Assistant(completed.text(), completed.toolCalls()));
                    for (LlmToolCall call : completed.toolCalls()) {
                        messages.add(new LlmMessage.ToolResult(call.toolCallId(), call.tool(),
                                Objects.requireNonNull(outcomes.get(call.toolCallId()), call.toolCallId())));
                    }
                }
                default -> {
                }
            }
        }
        return List.copyOf(messages);
    }

    /** What the model wrote last; {@code TurnCommand.Complete} only comes after it answered without tools. */
    static String lastAnswer(List<TurnEvent> events) {
        String answer = "";
        for (TurnEvent event : events) {
            if (event instanceof TurnEvent.LlmCallCompleted completed) {
                answer = completed.text();
            }
        }
        return answer;
    }

    /** How the turn ended, from what actually ran and not from what the model says it did. */
    static String resolution(List<TurnEvent> events) {
        Map<String, String> tools = new HashMap<>();
        List<String> ran = new ArrayList<>();
        for (TurnEvent event : events) {
            if (event instanceof TurnEvent.LlmCallCompleted completed) {
                completed.toolCalls().forEach(call -> tools.put(call.toolCallId(), call.tool()));
            } else if (event instanceof TurnEvent.ToolCallCompleted completed) {
                ran.add(tools.get(completed.toolCallId()));
            }
        }
        return ran.isEmpty() ? "no tool ran" : "tools run: " + String.join(", ", ran);
    }

    /** The last word on each tool call wins: an outcome recorded after {@code ToolOutcomeUnknown} replaces it. */
    private static Map<String, String> outcomes(List<TurnEvent> events) {
        Map<String, String> outcomes = new HashMap<>();
        for (TurnEvent event : events) {
            switch (event) {
                case TurnEvent.ToolCallCompleted completed ->
                        outcomes.put(completed.toolCallId(), completed.result());
                case TurnEvent.ToolCallFailed failed ->
                        outcomes.put(failed.toolCallId(), "The tool failed: " + failed.error());
                case TurnEvent.ApprovalDenied denied -> outcomes.put(denied.toolCallId(),
                        "The tool did not run: denied by " + denied.by() + ": " + denied.reason());
                case TurnEvent.ApprovalExpired expired -> outcomes.put(expired.toolCallId(),
                        "The tool did not run: nobody approved it in time.");
                default -> {
                }
            }
        }
        return outcomes;
    }

    private static String describe(TurnEvent.TurnStarted started) {
        AlertContext alert = started.alert();
        return """
                Alert: %s
                Severity: %s
                Firing since: %s
                Labels: %s
                Annotations: %s""".formatted(alert.name(), started.severity(), alert.startsAt(),
                // Sorted, because Map.copyOf has no stable order and the same stream must give the same prompt.
                new TreeMap<>(alert.labels()), new TreeMap<>(alert.annotations()));
    }
}
