package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Stable names written to {@code events.type}. Written out by hand on purpose: renaming a record must not rename
 * what is already stored. {@code EventTypeRegistryTest} fails when a new {@link TurnEvent} has no name here.
 */
final class EventTypeRegistry {

    private static final Map<String, Class<? extends TurnEvent>> BY_NAME = Map.ofEntries(
            Map.entry("TurnStarted", TurnEvent.TurnStarted.class),
            Map.entry("RunbookContextRetrieved", TurnEvent.RunbookContextRetrieved.class),
            Map.entry("LlmCallStarted", TurnEvent.LlmCallStarted.class),
            Map.entry("LlmCallCompleted", TurnEvent.LlmCallCompleted.class),
            Map.entry("LlmCallFailed", TurnEvent.LlmCallFailed.class),
            Map.entry("ToolCallProposed", TurnEvent.ToolCallProposed.class),
            Map.entry("ApprovalRequested", TurnEvent.ApprovalRequested.class),
            Map.entry("ApprovalGranted", TurnEvent.ApprovalGranted.class),
            Map.entry("ApprovalDenied", TurnEvent.ApprovalDenied.class),
            Map.entry("ApprovalExpired", TurnEvent.ApprovalExpired.class),
            Map.entry("ToolCallStarted", TurnEvent.ToolCallStarted.class),
            Map.entry("ToolCallCompleted", TurnEvent.ToolCallCompleted.class),
            Map.entry("ToolCallFailed", TurnEvent.ToolCallFailed.class),
            Map.entry("ToolOutcomeUnknown", TurnEvent.ToolOutcomeUnknown.class),
            Map.entry("BudgetExceeded", TurnEvent.BudgetExceeded.class),
            Map.entry("TurnSuspended", TurnEvent.TurnSuspended.class),
            Map.entry("TurnResumed", TurnEvent.TurnResumed.class),
            Map.entry("TurnCompleted", TurnEvent.TurnCompleted.class),
            Map.entry("TurnFailed", TurnEvent.TurnFailed.class),
            Map.entry("TurnCancelled", TurnEvent.TurnCancelled.class));

    private static final Map<Class<? extends TurnEvent>, String> BY_CLASS = BY_NAME.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private EventTypeRegistry() {}

    static String nameOf(TurnEvent event) {
        String name = BY_CLASS.get(Objects.requireNonNull(event, "event").getClass());
        if (name == null) {
            throw new IllegalArgumentException("no stored name for " + event.getClass().getName());
        }
        return name;
    }

    static Class<? extends TurnEvent> classOf(String name) {
        Class<? extends TurnEvent> type = BY_NAME.get(Objects.requireNonNull(name, "name"));
        if (type == null) {
            throw new IllegalArgumentException("unknown event type " + name);
        }
        return type;
    }

    static Map<String, Class<? extends TurnEvent>> all() {
        return BY_NAME;
    }
}
