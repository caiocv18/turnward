package io.github.caiocv18.turnward.domain.turn;

import io.github.caiocv18.turnward.domain.tool.RiskLevel;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything that can happen to a turn (design note, section 4). One stream is one turn, so no event carries the
 * turn id. Every tool and approval event carries its {@code toolCallId}, because a turn may hold several calls.
 *
 * <p>A model call lost in a crash is closed with {@link LlmCallFailed} before a new one starts, so every attempt
 * stays in the stream with its own {@code callId}.
 */
public sealed interface TurnEvent {

    /** Carries the alert itself, because nothing else in the stream can rebuild the prompt after a crash. */
    record TurnStarted(String alertFingerprint, String tenantId, String severity, AlertContext alert)
            implements TurnEvent {
        public TurnStarted {
            Objects.requireNonNull(alertFingerprint, "alertFingerprint");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(alert, "alert");
        }
    }

    record RunbookContextRetrieved(List<String> chunkIds, List<Double> scores) implements TurnEvent {
        public RunbookContextRetrieved {
            chunkIds = List.copyOf(Objects.requireNonNull(chunkIds, "chunkIds"));
            scores = List.copyOf(Objects.requireNonNull(scores, "scores"));
            if (chunkIds.size() != scores.size()) {
                throw new IllegalArgumentException("one score per chunk");
            }
        }
    }

    record LlmCallStarted(String callId, String model) implements TurnEvent {
        public LlmCallStarted {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(model, "model");
        }
    }

    /**
     * @param text what the model wrote, empty when it only asked for tools; a turn's summary comes from here, so a
     *     crash between this event and {@link TurnCompleted} does not lose the answer
     */
    record LlmCallCompleted(String callId, long tokensIn, long tokensOut, List<LlmToolCall> toolCalls, String text)
            implements TurnEvent {
        public LlmCallCompleted {
            Objects.requireNonNull(callId, "callId");
            toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
            Objects.requireNonNull(text, "text");
        }
    }

    record LlmCallFailed(String callId, String error) implements TurnEvent {
        public LlmCallFailed {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(error, "error");
        }
    }

    record ToolCallProposed(String toolCallId, String tool, Map<String, String> args, RiskLevel riskLevel)
            implements TurnEvent {
        public ToolCallProposed {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(tool, "tool");
            args = Map.copyOf(Objects.requireNonNull(args, "args"));
            Objects.requireNonNull(riskLevel, "riskLevel");
        }
    }

    record ApprovalRequested(String toolCallId) implements TurnEvent {
        public ApprovalRequested {
            Objects.requireNonNull(toolCallId, "toolCallId");
        }
    }

    record ApprovalGranted(String toolCallId, String by) implements TurnEvent {
        public ApprovalGranted {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(by, "by");
        }
    }

    record ApprovalDenied(String toolCallId, String by, String reason) implements TurnEvent {
        public ApprovalDenied {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(by, "by");
            Objects.requireNonNull(reason, "reason");
        }
    }

    record ApprovalExpired(String toolCallId) implements TurnEvent {
        public ApprovalExpired {
            Objects.requireNonNull(toolCallId, "toolCallId");
        }
    }

    record ToolCallStarted(String toolCallId, String idempotencyKey) implements TurnEvent {
        public ToolCallStarted {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        }
    }

    /** @param result what the tool returned, inline and bounded by the caller; the next model call reads it */
    record ToolCallCompleted(String toolCallId, String result) implements TurnEvent {
        public ToolCallCompleted {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(result, "result");
        }
    }

    record ToolCallFailed(String toolCallId, String error) implements TurnEvent {
        public ToolCallFailed {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(error, "error");
        }
    }

    record ToolOutcomeUnknown(String toolCallId) implements TurnEvent {
        public ToolOutcomeUnknown {
            Objects.requireNonNull(toolCallId, "toolCallId");
        }
    }

    record BudgetExceeded(String tenantId, long balance) implements TurnEvent {
        public BudgetExceeded {
            Objects.requireNonNull(tenantId, "tenantId");
        }
    }

    record TurnSuspended(String reason) implements TurnEvent {
        public TurnSuspended {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Lifts one cause of suspension; the turn stays suspended while another cause holds. */
    record TurnResumed(SuspensionCause cause) implements TurnEvent {
        public TurnResumed {
            Objects.requireNonNull(cause, "cause");
        }
    }

    record TurnCompleted(String summary, String resolution) implements TurnEvent {
        public TurnCompleted {
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(resolution, "resolution");
        }
    }

    record TurnFailed(String cause) implements TurnEvent {
        public TurnFailed {
            Objects.requireNonNull(cause, "cause");
        }
    }

    record TurnCancelled(String by) implements TurnEvent {
        public TurnCancelled {
            Objects.requireNonNull(by, "by");
        }
    }
}
