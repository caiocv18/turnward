package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import io.github.caiocv18.turnward.domain.tool.RiskLevel;
import io.github.caiocv18.turnward.domain.turn.LlmToolCall;
import io.github.caiocv18.turnward.domain.turn.SuspensionCause;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import java.util.List;
import java.util.Map;

/** Fixtures: one instance of every {@link TurnEvent}, and a short legal turn. */
final class SampleEvents {

    static final String TENANT = "tenant-a";

    private SampleEvents() {}

    /** One of each record, with every component set to a value that is not the default. */
    static List<TurnEvent> oneOfEach() {
        return List.of(
                new TurnEvent.TurnStarted("fp-1", TENANT, "critical"),
                new TurnEvent.RunbookContextRetrieved(List.of("chunk-1", "chunk-2"), List.of(0.91, 0.42)),
                new TurnEvent.LlmCallStarted("llm-1", "llama3.2"),
                new TurnEvent.LlmCallCompleted("llm-1", 3_000, 500,
                        List.of(new LlmToolCall("tool-1", "rollback_deployment", Map.of("service", "checkout")))),
                new TurnEvent.LlmCallFailed("llm-2", "timeout"),
                new TurnEvent.ToolCallProposed("tool-1", "rollback_deployment", Map.of("service", "checkout"),
                        RiskLevel.DANGEROUS),
                new TurnEvent.ApprovalRequested("tool-1"),
                new TurnEvent.ApprovalGranted("tool-1", "approver-a"),
                new TurnEvent.ApprovalDenied("tool-2", "approver-a", "not during peak"),
                new TurnEvent.ApprovalExpired("tool-3"),
                new TurnEvent.ToolCallStarted("tool-1", "stream:tool-1"),
                new TurnEvent.ToolCallCompleted("tool-1", "result-1"),
                new TurnEvent.ToolCallFailed("tool-4", "connection refused"),
                new TurnEvent.ToolOutcomeUnknown("tool-5"),
                new TurnEvent.BudgetExceeded(TENANT, -120),
                new TurnEvent.TurnSuspended("maintenance window"),
                new TurnEvent.TurnResumed(SuspensionCause.BUDGET),
                new TurnEvent.TurnCompleted("rolled back", "resolved"),
                new TurnEvent.TurnFailed("no runbook"),
                new TurnEvent.TurnCancelled("operator-a"));
    }

    /** A turn the strict fold accepts: start, one model call that asks for nothing, completion. */
    static List<TurnEvent> shortTurn() {
        return List.of(
                new TurnEvent.TurnStarted("fp-1", TENANT, "critical"),
                new TurnEvent.LlmCallStarted("llm-1", "llama3.2"),
                new TurnEvent.LlmCallCompleted("llm-1", 3_000, 500, List.of()),
                new TurnEvent.TurnCompleted("nothing to do", "resolved"));
    }
}
