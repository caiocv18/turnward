package io.github.caiocv18.turnward.domain.turn;

import io.github.caiocv18.turnward.domain.tool.ToolPolicy;
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
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnFailed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnResumed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnStarted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnSuspended;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The turn aggregate as a pure fold. The fold is strict: an event the current state cannot accept is a corrupted
 * stream, so it throws instead of producing a state nobody can trust.
 */
public final class Turn {

    private Turn() {
    }

    public static TurnState fold(List<? extends TurnEvent> events) {
        Objects.requireNonNull(events, "events");
        TurnState state = TurnState.initial();
        for (TurnEvent event : events) {
            state = evolve(state, event);
        }
        return state;
    }

    public static TurnState evolve(TurnState state, TurnEvent event) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(event, "event");
        TurnStatus status = state.status();
        require(!status.isTerminal(), state, event, "turn already ended");
        require(status != TurnStatus.NOT_STARTED || event instanceof TurnStarted, state, event,
                "turn not started");
        TurnState next = switch (event) {
            case TurnStarted started -> {
                require(status == TurnStatus.NOT_STARTED, state, event, "turn already started");
                yield state.withTenant(started.tenantId()).withStatus(TurnStatus.ACTIVE);
            }
            case RunbookContextRetrieved retrieved -> state;
            case LlmCallStarted started -> {
                requireNotSuspended(state, event);
                require(state.llmCallInFlight().isEmpty(), state, event, "a model call is already in flight");
                require(!state.hasOpenToolCalls(), state, event, "tool calls still open");
                yield state.withLlm(Optional.of(started.callId()), state.lastLlmOutcome(), List.of());
            }
            case LlmCallCompleted completed -> {
                requireInFlight(state, event, completed.callId());
                long distinct = completed.toolCalls().stream().map(LlmToolCall::toolCallId).distinct().count();
                require(distinct == completed.toolCalls().size()
                        && completed.toolCalls().stream().noneMatch(call -> state.knowsToolCall(call.toolCallId())),
                        state, event, "tool call id reused");
                LlmOutcome outcome = completed.toolCalls().isEmpty() ? LlmOutcome.ANSWERED
                        : LlmOutcome.REQUESTED_TOOLS;
                yield state.withLlm(Optional.empty(), outcome, completed.toolCalls());
            }
            case LlmCallFailed failed -> {
                requireInFlight(state, event, failed.callId());
                yield state.withLlm(Optional.empty(), LlmOutcome.FAILED, List.of());
            }
            case ToolCallProposed proposed -> {
                requireNotSuspended(state, event);
                LlmToolCall asked = state.pendingProposals().stream()
                        .filter(call -> call.toolCallId().equals(proposed.toolCallId()))
                        .findFirst()
                        .orElseThrow(() -> invalid(state, event, "the model did not ask for this call"));
                require(asked.tool().equals(proposed.tool()) && asked.args().equals(proposed.args()), state, event,
                        "call differs from the model's");
                yield state.withProposal(asked, new ToolCall(proposed.toolCallId(), proposed.tool(),
                        proposed.args(), proposed.riskLevel(), ToolCallPhase.PROPOSED));
            }
            case ApprovalRequested requested -> {
                ToolCall call = existing(state, event, requested.toolCallId());
                require(ToolPolicy.requiresApproval(call.riskLevel()), state, event, "call needs no approval");
                yield advance(state, event, requested.toolCallId(), ToolCallPhase.APPROVAL_REQUESTED,
                        ToolCallPhase.PROPOSED);
            }
            case ApprovalGranted granted ->
                    advance(state, event, granted.toolCallId(), ToolCallPhase.APPROVED,
                            ToolCallPhase.APPROVAL_REQUESTED);
            case ApprovalDenied denied ->
                    advance(state, event, denied.toolCallId(), ToolCallPhase.DENIED,
                            ToolCallPhase.APPROVAL_REQUESTED);
            case ApprovalExpired expired ->
                    advance(state, event, expired.toolCallId(), ToolCallPhase.EXPIRED,
                            ToolCallPhase.APPROVAL_REQUESTED);
            case ToolCallStarted started -> {
                requireNotSuspended(state, event);
                ToolCall call = existing(state, event, started.toolCallId());
                boolean mayStart = switch (call.phase()) {
                    case APPROVED -> true;
                    case PROPOSED -> !ToolPolicy.requiresApproval(call.riskLevel());
                    case STARTED -> ToolPolicy.safeToRetryAfterCrash(call.riskLevel());
                    default -> false;
                };
                require(mayStart, state, event, "call may not start from " + call.phase());
                yield state.withToolCall(call.withPhase(ToolCallPhase.STARTED));
            }
            case ToolCallCompleted completed ->
                    advance(state, event, completed.toolCallId(), ToolCallPhase.COMPLETED, ToolCallPhase.STARTED,
                            ToolCallPhase.OUTCOME_UNKNOWN);
            case ToolCallFailed failed ->
                    advance(state, event, failed.toolCallId(), ToolCallPhase.FAILED, ToolCallPhase.STARTED,
                            ToolCallPhase.OUTCOME_UNKNOWN);
            case ToolOutcomeUnknown unknown -> {
                ToolCall call = existing(state, event, unknown.toolCallId());
                require(!ToolPolicy.safeToRetryAfterCrash(call.riskLevel()), state, event,
                        "a read-only call is retried, not reconciled");
                yield advance(state, event, unknown.toolCallId(), ToolCallPhase.OUTCOME_UNKNOWN,
                        ToolCallPhase.STARTED);
            }
            case BudgetExceeded exceeded -> {
                require(state.tenantId().filter(exceeded.tenantId()::equals).isPresent(), state, event,
                        "budget event for another tenant");
                yield state.withSuspension(SuspensionCause.BUDGET, true);
            }
            case TurnSuspended suspended -> {
                require(!state.suspendedBy().contains(SuspensionCause.OPERATOR), state, event,
                        "already suspended by an operator");
                yield state.withSuspension(SuspensionCause.OPERATOR, true);
            }
            case TurnResumed resumed -> {
                require(state.suspendedBy().contains(resumed.cause()), state, event,
                        "not suspended by " + resumed.cause());
                yield state.withSuspension(resumed.cause(), false);
            }
            case TurnCompleted completed -> {
                requireNotSuspended(state, event);
                yield state.withStatus(TurnStatus.COMPLETED);
            }
            case TurnFailed failed -> state.withStatus(TurnStatus.FAILED);
            case TurnCancelled cancelled -> state.withStatus(TurnStatus.CANCELLED);
        };
        return settle(next).withVersion(state.version() + 1);
    }

    /**
     * Derives every non-terminal status from the state, in the decider's order of priority: the turn waits for a
     * human only when nothing else is left to run, so status and decider never disagree about the lease.
     */
    private static TurnState settle(TurnState state) {
        if (state.status().isTerminal() || state.status() == TurnStatus.NOT_STARTED) {
            return state;
        }
        if (!state.suspendedBy().isEmpty()) {
            return state.withStatus(TurnStatus.SUSPENDED);
        }
        if (state.firstCallIn(ToolCallPhase.STARTED).isPresent()) {
            return state.withStatus(TurnStatus.ACTIVE);
        }
        if (state.firstCallIn(ToolCallPhase.OUTCOME_UNKNOWN).isPresent()) {
            return state.withStatus(TurnStatus.AWAITING_RECONCILIATION);
        }
        boolean workLeft = state.llmCallInFlight().isPresent()
                || !state.pendingProposals().isEmpty()
                || state.firstCallIn(ToolCallPhase.APPROVED).isPresent()
                || state.firstCallIn(ToolCallPhase.PROPOSED).isPresent();
        if (!workLeft && state.firstCallIn(ToolCallPhase.APPROVAL_REQUESTED).isPresent()) {
            return state.withStatus(TurnStatus.AWAITING_APPROVAL);
        }
        return state.withStatus(TurnStatus.ACTIVE);
    }

    private static TurnState advance(TurnState state, TurnEvent event, String toolCallId, ToolCallPhase to,
            ToolCallPhase... from) {
        ToolCall call = existing(state, event, toolCallId);
        require(List.of(from).contains(call.phase()), state, event, "call is " + call.phase());
        return state.withToolCall(call.withPhase(to));
    }

    private static ToolCall existing(TurnState state, TurnEvent event, String toolCallId) {
        return state.toolCall(toolCallId).orElseThrow(() -> invalid(state, event, "unknown tool call"));
    }

    private static void requireInFlight(TurnState state, TurnEvent event, String callId) {
        require(state.llmCallInFlight().filter(callId::equals).isPresent(), state, event,
                "no such model call in flight");
    }

    private static void requireNotSuspended(TurnState state, TurnEvent event) {
        require(state.suspendedBy().isEmpty(), state, event, "turn is suspended");
    }

    private static void require(boolean condition, TurnState state, TurnEvent event, String reason) {
        if (!condition) {
            throw invalid(state, event, reason);
        }
    }

    private static IllegalStateException invalid(TurnState state, TurnEvent event, String reason) {
        return new IllegalStateException(
                "%s rejected at version %d (%s): %s".formatted(event.getClass().getSimpleName(), state.version(),
                        state.status(), reason));
    }
}
