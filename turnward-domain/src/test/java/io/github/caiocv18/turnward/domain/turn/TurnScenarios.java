package io.github.caiocv18.turnward.domain.turn;

import io.github.caiocv18.turnward.domain.tool.RiskLevel;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalDenied;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalExpired;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalGranted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalRequested;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.BudgetExceeded;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallCompleted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallFailed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallStarted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.RunbookContextRetrieved;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ToolCallCompleted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ToolCallFailed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ToolCallProposed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ToolCallStarted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ToolOutcomeUnknown;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnCancelled;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnCompleted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnResumed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnStarted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnSuspended;
import io.github.caiocv18.turnward.domain.turn.command.TurnCommand;
import io.github.caiocv18.turnward.domain.turn.command.TurnDecider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds valid event streams from a list of choices in [0, 99]. Each step asks the decider what the worker would do
 * and lets the choice pick the outside world's answer (model reply, human verdict, tool outcome) or some noise
 * (suspension, budget, cancellation). Every prefix of the result is a stream a crash could leave behind.
 *
 * <p>The stream is a function of the choices only, so jqwik shrinks a failing stream by shrinking the list.
 */
final class TurnScenarios {

    static final String TENANT = "tenant-a";
    private static final int NOISE_FROM = 94;

    private TurnScenarios() {
    }

    static List<TurnEvent> play(List<Integer> choices) {
        List<TurnEvent> events = new ArrayList<>();
        TurnState state = append(events, TurnState.initial(), new TurnStarted("fp-1", TENANT, "critical"));
        int toolCalls = 0;
        for (int choice : choices) {
            if (state.status().isTerminal()) {
                break;
            }
            int askedSoFar = toolCalls;
            String nextLlmCall = "llm-" + (events.size() + 1);
            TurnState current = state;
            List<TurnEvent> step = noise(state, choice).map(List::of).orElseGet(() ->
                    switch (TurnDecider.decide(current)) {
                        case TurnCommand.CallLlm callLlm -> effect(new LlmCallStarted(nextLlmCall, "haiku"), choice,
                                llmOutcome(nextLlmCall, choice, askedSoFar));
                        case TurnCommand.AbandonLlmCall abandon ->
                                List.of(new LlmCallFailed(abandon.callId(), "lost in crash"));
                        case TurnCommand.ProposeTool propose -> List.of(new ToolCallProposed(
                                propose.call().toolCallId(), propose.call().tool(), propose.call().args(),
                                RiskLevel.values()[choice % 3]));
                        case TurnCommand.AwaitApproval await -> List.of(new ApprovalRequested(await.call().toolCallId()));
                        case TurnCommand.ExecuteTool execute -> effect(
                                new ToolCallStarted(execute.call().toolCallId(), "key-" + execute.call().toolCallId()),
                                choice, toolOutcome(execute.call(), choice));
                        case TurnCommand.Reconcile reconcile ->
                                List.of(new ToolOutcomeUnknown(reconcile.call().toolCallId()));
                        case TurnCommand.Complete complete -> List.of(new TurnCompleted("resolved", "rolled back"));
                        case TurnCommand.Halt halt -> List.of(outsideWorld(current, choice));
                    });
            for (TurnEvent event : step) {
                if (event instanceof LlmCallCompleted completed) {
                    toolCalls += completed.toolCalls().size();
                }
                state = append(events, state, event);
            }
        }
        return List.copyOf(events);
    }

    /**
     * While the turn is idle the worker does nothing, and only the outside world moves it: a human verdict, a
     * reconciliation, an outcome that arrives during a suspension, or the end of the suspension.
     */
    private static TurnEvent outsideWorld(TurnState state, int choice) {
        Optional<ToolCall> unknown = state.firstCallIn(ToolCallPhase.OUTCOME_UNKNOWN);
        Optional<ToolCall> requested = state.firstCallIn(ToolCallPhase.APPROVAL_REQUESTED);
        Optional<ToolCall> cut = state.firstCallIn(ToolCallPhase.STARTED);
        boolean suspended = state.status() == TurnStatus.SUSPENDED;
        if (suspended && (choice < 30 || unknown.isEmpty() && requested.isEmpty() && cut.isEmpty())) {
            return new TurnResumed(state.suspendedBy().contains(SuspensionCause.OPERATOR) ? SuspensionCause.OPERATOR
                    : SuspensionCause.BUDGET);
        }
        if (cut.isPresent()) {
            return toolOutcome(cut.get(), choice);
        }
        if (unknown.isPresent()) {
            return toolOutcome(unknown.get(), choice);
        }
        return verdict(requested.orElseThrow(), choice);
    }

    private static Optional<TurnEvent> noise(TurnState state, int choice) {
        if (choice < NOISE_FROM) {
            return Optional.empty();
        }
        boolean byOperator = state.suspendedBy().contains(SuspensionCause.OPERATOR);
        return Optional.ofNullable(switch (choice) {
            case 94 -> new RunbookContextRetrieved(List.of("chunk-1"), List.of(0.9));
            case 95 -> new BudgetExceeded(TENANT, 0);
            case 96 -> byOperator ? new TurnResumed(SuspensionCause.OPERATOR) : new TurnSuspended("operator");
            case 97 -> byOperator ? null : new TurnSuspended("operator");
            case 98 -> new TurnCancelled("operator");
            default -> null;
        });
    }

    /**
     * A worker appends {@code *Started}, performs the effect and appends the outcome without reloading the stream.
     * One choice in five makes it crash in between, leaving the {@code *Started} without outcome.
     */
    private static List<TurnEvent> effect(TurnEvent started, int choice, TurnEvent outcome) {
        return choice % 5 == 0 ? List.of(started) : List.of(started, outcome);
    }

    private static TurnEvent llmOutcome(String callId, int choice, int toolCallsSoFar) {
        if (choice < 15) {
            return new LlmCallFailed(callId, "timeout");
        }
        if (choice < 40) {
            return new LlmCallCompleted(callId, 100, 20, List.of());
        }
        List<LlmToolCall> asked = new ArrayList<>();
        for (int i = 1; i <= choice % 3 + 1; i++) {
            asked.add(new LlmToolCall("tool-" + (toolCallsSoFar + i), "tool" + choice % 5, Map.of("arg", "v")));
        }
        return new LlmCallCompleted(callId, 100, 20, asked);
    }

    private static TurnEvent verdict(ToolCall call, int choice) {
        if (choice < 50) {
            return new ApprovalGranted(call.toolCallId(), "approver");
        }
        return choice < 75 ? new ApprovalDenied(call.toolCallId(), "approver", "too risky")
                : new ApprovalExpired(call.toolCallId());
    }

    private static TurnEvent toolOutcome(ToolCall call, int choice) {
        return choice < 70 ? new ToolCallCompleted(call.toolCallId(), "result-" + call.toolCallId())
                : new ToolCallFailed(call.toolCallId(), "boom");
    }

    private static TurnState append(List<TurnEvent> events, TurnState state, TurnEvent event) {
        events.add(event);
        return Turn.evolve(state, event);
    }
}
