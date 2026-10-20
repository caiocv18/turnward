package io.github.caiocv18.turnward.domain.turn.command;

import io.github.caiocv18.turnward.domain.tool.ToolPolicy;
import io.github.caiocv18.turnward.domain.turn.ToolCall;
import io.github.caiocv18.turnward.domain.turn.ToolCallPhase;
import io.github.caiocv18.turnward.domain.turn.TurnState;
import io.github.caiocv18.turnward.domain.turn.TurnStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides the next effect from the folded state alone (design note, section 5). Pure: the same state always yields
 * the same command, so a worker that reloads the stream after a crash decides exactly what the dead one would have.
 * It returns {@link TurnCommand.Halt} exactly when the status is idle, by its own rules.
 */
public final class TurnDecider {

    private TurnDecider() {
    }

    public static TurnCommand decide(TurnState state) {
        Objects.requireNonNull(state, "state");
        if (state.status() == TurnStatus.NOT_STARTED) {
            throw new IllegalStateException("turn not started");
        }
        // Only the statuses set by events are read here; the waiting ones are reached by the rules below, on their
        // own, so the property that compares them with the fold's status checks two derivations, not one.
        if (state.status().isTerminal() || state.status() == TurnStatus.SUSPENDED) {
            return new TurnCommand.Halt();
        }
        // An effect that may have happened comes first: nothing else runs until its outcome is known.
        Optional<ToolCall> cut = state.firstCallIn(ToolCallPhase.STARTED);
        if (cut.isPresent()) {
            return ToolPolicy.safeToRetryAfterCrash(cut.get().riskLevel())
                    ? new TurnCommand.ExecuteTool(cut.get(), true)
                    : new TurnCommand.Reconcile(cut.get());
        }
        if (state.firstCallIn(ToolCallPhase.OUTCOME_UNKNOWN).isPresent()) {
            return new TurnCommand.Halt();
        }
        if (state.llmCallInFlight().isPresent()) {
            return new TurnCommand.AbandonLlmCall(state.llmCallInFlight().get());
        }
        if (!state.pendingProposals().isEmpty()) {
            return new TurnCommand.ProposeTool(state.pendingProposals().getFirst());
        }
        Optional<ToolCall> runnable = state.toolCalls().stream()
                .filter(call -> call.phase() == ToolCallPhase.APPROVED
                        || call.phase() == ToolCallPhase.PROPOSED && !ToolPolicy.requiresApproval(call.riskLevel()))
                .findFirst();
        if (runnable.isPresent()) {
            return new TurnCommand.ExecuteTool(runnable.get(), false);
        }
        Optional<ToolCall> toAsk = state.firstCallIn(ToolCallPhase.PROPOSED);
        if (toAsk.isPresent()) {
            return new TurnCommand.AwaitApproval(toAsk.get());
        }
        if (state.firstCallIn(ToolCallPhase.APPROVAL_REQUESTED).isPresent()) {
            return new TurnCommand.Halt();
        }
        return switch (state.lastLlmOutcome()) {
            case ANSWERED -> new TurnCommand.Complete();
            case NONE, REQUESTED_TOOLS, FAILED -> new TurnCommand.CallLlm();
        };
    }
}
