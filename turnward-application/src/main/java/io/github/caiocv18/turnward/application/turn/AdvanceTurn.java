package io.github.caiocv18.turnward.application.turn;

import io.github.caiocv18.turnward.application.eventstore.ConcurrencyConflictException;
import io.github.caiocv18.turnward.application.eventstore.RecordedEvent;
import io.github.caiocv18.turnward.application.llm.LlmRequest;
import io.github.caiocv18.turnward.application.llm.LlmResponse;
import io.github.caiocv18.turnward.application.port.EventStore;
import io.github.caiocv18.turnward.application.port.LlmPort;
import io.github.caiocv18.turnward.application.port.ToolPort;
import io.github.caiocv18.turnward.application.tool.ToolCatalog;
import io.github.caiocv18.turnward.application.tool.ToolFailedException;
import io.github.caiocv18.turnward.application.tool.ToolInvocation;
import io.github.caiocv18.turnward.domain.TurnId;
import io.github.caiocv18.turnward.domain.tool.ToolPolicy;
import io.github.caiocv18.turnward.domain.turn.LlmToolCall;
import io.github.caiocv18.turnward.domain.turn.ToolCall;
import io.github.caiocv18.turnward.domain.turn.Turn;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import io.github.caiocv18.turnward.domain.turn.TurnState;
import io.github.caiocv18.turnward.domain.turn.command.TurnCommand;
import io.github.caiocv18.turnward.domain.turn.command.TurnDecider;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Moves a turn forward until it ends, parks or a model call fails: load, fold, ask the decider, perform, append. Every
 * effect is preceded by its {@code *Started}, so a crash at any point leaves a stream the next run knows how to read.
 *
 * <p>There must be a single runner per turn until the worker lease exists: to the decider, an effect in flight looks
 * like a crash, so a second runner would abandon or reconcile the first one's work.
 *
 * <p>No transaction spans an effect: each append has its own, and the effect runs between them.
 */
public final class AdvanceTurn {

    /** Longest tool result or error kept in a stream; the rest would be paid for on every later model call. */
    static final int MAX_TEXT_LENGTH = 16_000;

    private final EventStore store;
    private final LlmPort llm;
    private final ToolPort tools;
    private final ToolCatalog catalog;
    private final String systemPrompt;

    public AdvanceTurn(EventStore store, LlmPort llm, ToolPort tools, ToolCatalog catalog, String systemPrompt) {
        this.store = Objects.requireNonNull(store, "store");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
    }

    /**
     * Runs the turn as far as it can go now.
     *
     * <p>A model call that fails during this run ends it instead of being retried here: the next run is the retry, so
     * the caller's schedule is the backoff, and {@link TurnDecider#MAX_LLM_CALLS} ends the turn if the model never
     * comes back. A call lost in an earlier crash is closed and made again at once, because it says nothing about the
     * model. An answer with neither text nor tools counts as a failure: a refusal looks like that, and it must not
     * mark an incident as completed.
     *
     * @return the state of the stream when the run stopped
     * @throws IllegalStateException when the stream rejects an event, which is a bug or a turn ended while an effect
     *     ran; the caller must isolate it so that one turn does not stop the others
     */
    public TurnState run(TurnId turnId) {
        Objects.requireNonNull(turnId, "turnId");
        while (true) {
            List<TurnEvent> events = load(turnId);
            TurnState state = Turn.fold(events);
            try {
                if (!perform(turnId, events, state, TurnDecider.decide(state))) {
                    return Turn.fold(load(turnId));
                }
            } catch (ConcurrencyConflictException lost) {
                // Nothing ran yet, because every outcome handles its own conflict: decide again from the new state.
                continue;
            }
        }
    }

    /** @return false when this run should stop, because the turn is idle or a model call failed */
    private boolean perform(TurnId turnId, List<TurnEvent> events, TurnState state, TurnCommand command) {
        switch (command) {
            case TurnCommand.CallLlm call -> {
                return callLlm(turnId, events, state);
            }
            case TurnCommand.AbandonLlmCall abandon ->
                    append(turnId, state, new TurnEvent.LlmCallFailed(abandon.callId(), "lost in crash"));
            case TurnCommand.ProposeTool propose -> append(turnId, state, new TurnEvent.ToolCallProposed(
                    propose.call().toolCallId(), propose.call().tool(), propose.call().args(),
                    catalog.riskOf(propose.call().tool())));
            case TurnCommand.AwaitApproval await ->
                    append(turnId, state, new TurnEvent.ApprovalRequested(await.call().toolCallId()));
            case TurnCommand.ExecuteTool execute -> executeTool(turnId, state, execute.call());
            case TurnCommand.Reconcile reconcile ->
                    append(turnId, state, new TurnEvent.ToolOutcomeUnknown(reconcile.call().toolCallId()));
            case TurnCommand.Complete complete -> append(turnId, state,
                    new TurnEvent.TurnCompleted(TurnHistory.lastAnswer(events), TurnHistory.resolution(events)));
            case TurnCommand.Fail fail -> append(turnId, state, new TurnEvent.TurnFailed(fail.cause()));
            case TurnCommand.Halt halt -> {
                return false;
            }
        }
        return true;
    }

    private boolean callLlm(TurnId turnId, List<TurnEvent> events, TurnState state) {
        String callId = "llm-" + (state.version() + 1);
        TurnState started = append(turnId, state, new TurnEvent.LlmCallStarted(callId, llm.model()));
        TurnEvent outcome;
        try {
            LlmResponse response = llm.call(
                    new LlmRequest(systemPrompt, TurnHistory.messages(events), catalog.tools()));
            if (response.text().isBlank() && response.toolCalls().isEmpty()) {
                appendOutcome(turnId, started, new TurnEvent.LlmCallFailed(callId, "the model answered nothing"));
                return false;
            }
            outcome = new TurnEvent.LlmCallCompleted(callId, response.tokensIn(), response.tokensOut(),
                    withOwnIds(callId, response.toolCalls()), response.text());
        } catch (RuntimeException failure) {
            appendOutcome(turnId, started, new TurnEvent.LlmCallFailed(callId, describe(failure)));
            return false;
        }
        appendOutcome(turnId, started, outcome);
        return true;
    }

    private void executeTool(TurnId turnId, TurnState state, ToolCall call) {
        String key = turnId + ":" + call.toolCallId();
        TurnState started = append(turnId, state, new TurnEvent.ToolCallStarted(call.toolCallId(), key));
        TurnEvent outcome;
        try {
            String result = tools.execute(new ToolInvocation(call.tool(), call.args(), key));
            outcome = new TurnEvent.ToolCallCompleted(call.toolCallId(), bounded(result));
        } catch (ToolFailedException failure) {
            outcome = new TurnEvent.ToolCallFailed(call.toolCallId(), describe(failure));
        } catch (RuntimeException unknown) {
            // It may have happened anyway. Telling the model it failed could make it ask for the same effect again.
            outcome = ToolPolicy.safeToRetryAfterCrash(call.riskLevel())
                    ? new TurnEvent.ToolCallFailed(call.toolCallId(), describe(unknown))
                    : new TurnEvent.ToolOutcomeUnknown(call.toolCallId());
        }
        appendOutcome(turnId, started, outcome);
    }

    /**
     * Provider ids are dropped: some providers omit them, and the fold rejects an id repeated anywhere in the turn.
     * {@code callId} is unique in the stream, so the ids derived from it are too.
     */
    private static List<LlmToolCall> withOwnIds(String callId, List<LlmToolCall> asked) {
        List<LlmToolCall> own = new ArrayList<>();
        for (LlmToolCall call : asked) {
            own.add(new LlmToolCall(callId + "_" + own.size(), call.tool(), call.args()));
        }
        return List.copyOf(own);
    }

    /** Every event is folded before it is stored: one the fold rejects would make the stream unreadable for good. */
    private TurnState append(TurnId turnId, TurnState state, TurnEvent event) {
        TurnState next = Turn.evolve(state, event);
        store.append(turnId, state.tenantId().orElseThrow(), state.version(), List.of(event));
        return next;
    }

    /**
     * Records what came of an effect that already ran. Unlike every other conflict, this one must not lead to a new
     * decision: the decider would see the effect as cut by a crash and repeat or reconcile it, while the outcome is
     * right here. So it reloads and appends the same outcome (see {@link EventStore#append}).
     */
    private void appendOutcome(TurnId turnId, TurnState state, TurnEvent outcome) {
        TurnState current = state;
        while (true) {
            try {
                append(turnId, current, outcome);
                return;
            } catch (ConcurrencyConflictException lost) {
                current = Turn.fold(load(turnId));
            }
        }
    }

    private List<TurnEvent> load(TurnId turnId) {
        return store.load(turnId).stream().map(RecordedEvent::event).toList();
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return bounded(message == null ? failure.getClass().getName() : message);
    }

    private static String bounded(String text) {
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH) + " [truncated]";
    }
}
