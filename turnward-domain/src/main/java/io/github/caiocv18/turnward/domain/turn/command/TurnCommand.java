package io.github.caiocv18.turnward.domain.turn.command;

import io.github.caiocv18.turnward.domain.turn.LlmToolCall;
import io.github.caiocv18.turnward.domain.turn.ToolCall;
import java.util.Objects;

/**
 * The next effect a worker owes the turn. The application performs it and appends the events it produces; the
 * idempotency key ({@code streamId:toolCallId}) is built there, because the domain does not see the stream id.
 */
public sealed interface TurnCommand {

    /** Start a model call: the first one, one with tool results, or a new one after a failure. */
    record CallLlm() implements TurnCommand {
    }

    /**
     * A model call started and the worker died before its outcome: append {@code LlmCallFailed} for it, so the
     * lost attempt stays in the stream, and the next decision starts a fresh call with its own id.
     */
    record AbandonLlmCall(String callId) implements TurnCommand {
        public AbandonLlmCall {
            Objects.requireNonNull(callId, "callId");
        }
    }

    /** Assign a risk level to a call the model asked for and append {@code ToolCallProposed}. */
    record ProposeTool(LlmToolCall call) implements TurnCommand {
        public ProposeTool {
            Objects.requireNonNull(call, "call");
        }
    }

    /**
     * Append {@code ApprovalRequested} for a call that needs a human. Every such call is asked for before the turn
     * parks in {@code AWAITING_APPROVAL}; waiting itself is {@link Halt}.
     */
    record AwaitApproval(ToolCall call) implements TurnCommand {
        public AwaitApproval {
            Objects.requireNonNull(call, "call");
        }
    }

    /**
     * Run the tool. {@code retry} is true for a read-only call that started before a crash and has no outcome.
     */
    record ExecuteTool(ToolCall call, boolean retry) implements TurnCommand {
        public ExecuteTool {
            Objects.requireNonNull(call, "call");
        }
    }

    /**
     * A call with side effects started and has no outcome, so it may or may not have happened: never re-run it.
     * Append {@code ToolOutcomeUnknown}; the turn then parks in {@code AWAITING_RECONCILIATION} for a human.
     */
    record Reconcile(ToolCall call) implements TurnCommand {
        public Reconcile {
            Objects.requireNonNull(call, "call");
        }
    }

    /** The model answered without tools: append {@code TurnCompleted}. */
    record Complete() implements TurnCommand {
    }

    /** Nothing to run: the turn ended, is suspended or waits for a human. The worker drops the lease. */
    record Halt() implements TurnCommand {
    }
}
