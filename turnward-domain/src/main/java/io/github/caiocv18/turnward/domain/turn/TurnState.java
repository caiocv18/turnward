package io.github.caiocv18.turnward.domain.turn;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * State of a turn, rebuilt only by {@link Turn#fold}. Immutable; {@code version} is the number of events folded.
 *
 * @param tenantId the tenant that owns the turn, known once it started
 * @param llmCallInFlight a model call that started and has no outcome yet
 * @param pendingProposals tool calls the model asked for that were not proposed yet, in the model's order
 * @param toolCalls proposed tool calls, in proposal order
 * @param suspendedBy every cause currently holding the turn suspended
 */
public record TurnState(TurnStatus status, Optional<String> tenantId, Optional<String> llmCallInFlight,
        LlmOutcome lastLlmOutcome, List<LlmToolCall> pendingProposals, List<ToolCall> toolCalls,
        Set<SuspensionCause> suspendedBy, long version) {

    public TurnState {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(llmCallInFlight, "llmCallInFlight");
        Objects.requireNonNull(lastLlmOutcome, "lastLlmOutcome");
        pendingProposals = List.copyOf(Objects.requireNonNull(pendingProposals, "pendingProposals"));
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        suspendedBy = Set.copyOf(Objects.requireNonNull(suspendedBy, "suspendedBy"));
    }

    public static TurnState initial() {
        return new TurnState(TurnStatus.NOT_STARTED, Optional.empty(), Optional.empty(), LlmOutcome.NONE, List.of(),
                List.of(), Set.of(), 0);
    }

    public Optional<ToolCall> toolCall(String toolCallId) {
        return toolCalls.stream().filter(call -> call.toolCallId().equals(toolCallId)).findFirst();
    }

    public Optional<ToolCall> firstCallIn(ToolCallPhase phase) {
        return toolCalls.stream().filter(call -> call.phase() == phase).findFirst();
    }

    public boolean budgetExceeded() {
        return suspendedBy.contains(SuspensionCause.BUDGET);
    }

    /** True when some tool call still owes a step: asked for, proposed, approved, started or unknown. */
    public boolean hasOpenToolCalls() {
        return !pendingProposals.isEmpty() || toolCalls.stream().anyMatch(call -> !call.phase().isSettled());
    }

    /** True when the id is already taken, proposed or still waiting to be proposed. */
    boolean knowsToolCall(String toolCallId) {
        return toolCall(toolCallId).isPresent()
                || pendingProposals.stream().anyMatch(call -> call.toolCallId().equals(toolCallId));
    }

    TurnState withStatus(TurnStatus next) {
        return new TurnState(next, tenantId, llmCallInFlight, lastLlmOutcome, pendingProposals, toolCalls,
                suspendedBy, version);
    }

    TurnState withTenant(String tenant) {
        return new TurnState(status, Optional.of(tenant), llmCallInFlight, lastLlmOutcome, pendingProposals,
                toolCalls, suspendedBy, version);
    }

    TurnState withLlm(Optional<String> inFlight, LlmOutcome outcome, List<LlmToolCall> proposals) {
        return new TurnState(status, tenantId, inFlight, outcome, proposals, toolCalls, suspendedBy, version);
    }

    TurnState withProposal(LlmToolCall proposed, ToolCall call) {
        List<LlmToolCall> remaining = new ArrayList<>(pendingProposals);
        remaining.remove(proposed);
        List<ToolCall> calls = new ArrayList<>(toolCalls);
        calls.add(call);
        return new TurnState(status, tenantId, llmCallInFlight, lastLlmOutcome, remaining, calls, suspendedBy,
                version);
    }

    TurnState withToolCall(ToolCall updated) {
        List<ToolCall> calls = toolCalls.stream()
                .map(call -> call.toolCallId().equals(updated.toolCallId()) ? updated : call)
                .toList();
        return new TurnState(status, tenantId, llmCallInFlight, lastLlmOutcome, pendingProposals, calls,
                suspendedBy, version);
    }

    TurnState withSuspension(SuspensionCause cause, boolean on) {
        Set<SuspensionCause> causes = suspendedBy.isEmpty() ? EnumSet.noneOf(SuspensionCause.class)
                : EnumSet.copyOf(suspendedBy);
        if (on) {
            causes.add(cause);
        } else {
            causes.remove(cause);
        }
        return new TurnState(status, tenantId, llmCallInFlight, lastLlmOutcome, pendingProposals, toolCalls, causes,
                version);
    }

    TurnState withVersion(long next) {
        return new TurnState(status, tenantId, llmCallInFlight, lastLlmOutcome, pendingProposals, toolCalls,
                suspendedBy, next);
    }
}
