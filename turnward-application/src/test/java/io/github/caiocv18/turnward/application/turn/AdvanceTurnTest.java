package io.github.caiocv18.turnward.application.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.caiocv18.turnward.application.eventstore.RecordedEvent;
import io.github.caiocv18.turnward.application.llm.LlmMessage;
import io.github.caiocv18.turnward.application.llm.LlmRequest;
import io.github.caiocv18.turnward.application.llm.LlmResponse;
import io.github.caiocv18.turnward.application.port.LlmPort;
import io.github.caiocv18.turnward.application.port.ToolPort;
import io.github.caiocv18.turnward.application.tool.ToolCatalog;
import io.github.caiocv18.turnward.application.tool.ToolDefinition;
import io.github.caiocv18.turnward.application.tool.ToolFailedException;
import io.github.caiocv18.turnward.application.tool.ToolInvocation;
import io.github.caiocv18.turnward.domain.TurnId;
import io.github.caiocv18.turnward.domain.tool.RiskLevel;
import io.github.caiocv18.turnward.domain.turn.AlertContext;
import io.github.caiocv18.turnward.domain.turn.LlmToolCall;
import io.github.caiocv18.turnward.domain.turn.SuspensionCause;
import io.github.caiocv18.turnward.domain.turn.Turn;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalDenied;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalExpired;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalGranted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalRequested;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallCompleted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallFailed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallStarted;
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
import io.github.caiocv18.turnward.domain.turn.TurnState;
import io.github.caiocv18.turnward.domain.turn.TurnStatus;
import io.github.caiocv18.turnward.domain.turn.command.TurnDecider;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdvanceTurnTest {

    private static final String MODEL = "test-model";
    private static final Map<String, String> CHECKOUT = Map.of("service", "checkout-demo");
    private static final ToolCatalog CATALOG = new ToolCatalog(List.of(
            new ToolDefinition("query_metrics", "Reads a metric", List.of("service"), RiskLevel.READ_ONLY),
            new ToolDefinition("restart_pod", "Restarts a pod", List.of("service"), RiskLevel.SIDE_EFFECT),
            new ToolDefinition("rollback_deployment", "Rolls back", List.of("service"), RiskLevel.DANGEROUS)));

    private final InMemoryEventStore store = new InMemoryEventStore();
    private final ScriptedLlm llm = new ScriptedLlm();
    private final ScriptedTools tools = new ScriptedTools();
    private final AdvanceTurn advanceTurn = new AdvanceTurn(store, llm, tools, CATALOG, "You handle incidents.");
    private TurnId turn;

    @BeforeEach
    void startATurn() {
        turn = new StartTurn(store).start(new TurnStarted("fp-1", "acme", "critical", new AlertContext("HighErrorRate",
                Map.of("service", "checkout-demo", "env", "demo"), Map.of("summary", "5xx above 5%"),
                Instant.parse("2026-11-02T12:00:00Z")))).turnId();
    }

    private static LlmResponse asks(String... toolNames) {
        List<LlmToolCall> calls = new ArrayList<>();
        for (String tool : toolNames) {
            calls.add(new LlmToolCall("provider-id", tool, CHECKOUT));
        }
        return new LlmResponse("", 300, 50, calls);
    }

    private static LlmResponse answers(String text) {
        return new LlmResponse(text, 400, 60, List.of());
    }

    private List<TurnEvent> events() {
        return store.load(turn).stream().map(RecordedEvent::event).toList();
    }

    private List<Class<?>> eventTypes() {
        return events().stream().<Class<?>>map(TurnEvent::getClass).toList();
    }

    private <T extends TurnEvent> List<T> eventsOf(Class<T> type) {
        return events().stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Writes what the outside world would, at the stream's current version. */
    private void record(TurnEvent... outside) {
        store.append(turn, "acme", events().size(), List.of(outside));
    }

    /** Appends what a worker that died mid-way would have left in the stream. */
    private void leftByACrash(TurnEvent... tail) {
        record(tail);
    }

    private static List<LlmMessage.ToolResult> toolResults(LlmRequest request) {
        return request.messages().stream()
                .filter(LlmMessage.ToolResult.class::isInstance)
                .map(LlmMessage.ToolResult.class::cast)
                .toList();
    }

    @Test
    void aQueryAndThenAnAnswerCompleteTheTurn() {
        llm.script(() -> asks("query_metrics"), () -> answers("Errors are back to normal."));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(eventTypes()).containsExactly(TurnStarted.class, LlmCallStarted.class, LlmCallCompleted.class,
                ToolCallProposed.class, ToolCallStarted.class, ToolCallCompleted.class, LlmCallStarted.class,
                LlmCallCompleted.class, TurnCompleted.class);
        assertThat(events().getLast())
                .isEqualTo(new TurnCompleted("Errors are back to normal.", "tools run: query_metrics"));
        assertThat(eventsOf(LlmCallStarted.class)).extracting(LlmCallStarted::model).containsOnly(MODEL);
        assertThat(eventsOf(LlmCallCompleted.class).getFirst().tokensIn()).isEqualTo(300);
        assertThat(eventsOf(ToolCallCompleted.class).getFirst().result()).isEqualTo("result of query_metrics");
    }

    @Test
    void theModelReceivesTheSystemPromptTheAlertAndTheTools() {
        llm.script(() -> answers("Nothing to do."));

        advanceTurn.run(turn);

        LlmRequest request = llm.requests.getFirst();
        assertThat(request.system()).isEqualTo("You handle incidents.");
        assertThat(request.tools()).isEqualTo(CATALOG.tools());
        assertThat(request.messages()).containsExactly(new LlmMessage.User("""
                Alert: HighErrorRate
                Severity: critical
                Firing since: 2026-11-02T12:00:00Z
                Labels: {env=demo, service=checkout-demo}
                Annotations: {summary=5xx above 5%}"""));
    }

    @Test
    void anAnswerWithoutToolsSaysNoToolRan() {
        llm.script(() -> answers("Nothing to do."));

        advanceTurn.run(turn);

        assertThat(events().getLast()).isEqualTo(new TurnCompleted("Nothing to do.", "no tool ran"));
    }

    @Test
    void aRollbackParksUntilAHumanApprovesAndThenResumes() {
        llm.script(() -> asks("rollback_deployment"), () -> answers("Rolled back."));

        TurnState parked = advanceTurn.run(turn);

        assertThat(parked.status()).isEqualTo(TurnStatus.AWAITING_APPROVAL);
        assertThat(events().getLast()).isInstanceOf(ApprovalRequested.class);
        assertThat(tools.invocations).isEmpty();

        int size = events().size();
        assertThat(advanceTurn.run(turn).status()).isEqualTo(TurnStatus.AWAITING_APPROVAL);
        assertThat(events()).as("another run while parked writes nothing").hasSize(size);

        record(new ApprovalGranted("llm-2_0", "approver-a"));
        TurnState resumed = advanceTurn.run(turn);

        assertThat(resumed.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(tools.invocations).extracting(ToolInvocation::tool).containsExactly("rollback_deployment");
        assertThat(tools.invocations.getFirst().idempotencyKey()).isEqualTo(turn + ":llm-2_0");
        assertThat(events().getLast()).isEqualTo(new TurnCompleted("Rolled back.", "tools run: rollback_deployment"));
    }

    @Test
    void aDenialGoesBackToTheModelWithTheReasonAndTheToolNeverRuns() {
        llm.script(() -> asks("rollback_deployment"), () -> answers("Left as it is."));
        advanceTurn.run(turn);

        record(new ApprovalDenied("llm-2_0", "approver-a", "not during peak"));
        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(tools.invocations).isEmpty();
        assertThat(toolResults(llm.requests.getLast())).extracting(LlmMessage.ToolResult::content)
                .containsExactly("The tool did not run: denied by approver-a: not during peak");
        assertThat(events().getLast()).isEqualTo(new TurnCompleted("Left as it is.", "no tool ran"));
    }

    @Test
    void anExpiredApprovalGoesBackToTheModelAndTheToolNeverRuns() {
        llm.script(() -> asks("rollback_deployment"), () -> answers("Nobody answered."));
        advanceTurn.run(turn);

        record(new ApprovalExpired("llm-2_0"));
        advanceTurn.run(turn);

        assertThat(tools.invocations).isEmpty();
        assertThat(toolResults(llm.requests.getLast())).extracting(LlmMessage.ToolResult::content)
                .containsExactly("The tool did not run: nobody approved it in time.");
    }

    @Test
    void aToolTheModelMadeUpWaitsForAHuman() {
        llm.script(() -> asks("delete_everything"));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.AWAITING_APPROVAL);
        assertThat(eventsOf(ToolCallProposed.class).getFirst().riskLevel()).isEqualTo(RiskLevel.DANGEROUS);
        assertThat(tools.invocations).isEmpty();
    }

    @Test
    void aModelCallLostInACrashIsClosedAndStartsAgain() {
        leftByACrash(new LlmCallStarted("llm-2", MODEL));
        llm.script(() -> answers("Back."));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(eventsOf(LlmCallFailed.class)).containsExactly(new LlmCallFailed("llm-2", "lost in crash"));
        assertThat(eventsOf(LlmCallStarted.class)).extracting(LlmCallStarted::callId)
                .as("the new attempt has its own id")
                .containsExactly("llm-2", "llm-4");
    }

    @Test
    void aReadOnlyCallCutByACrashRunsAgainWithTheSameKey() {
        LlmToolCall query = new LlmToolCall("llm-2_0", "query_metrics", CHECKOUT);
        String key = turn + ":llm-2_0";
        leftByACrash(new LlmCallStarted("llm-2", MODEL), new LlmCallCompleted("llm-2", 1, 1, List.of(query), ""),
                new ToolCallProposed("llm-2_0", "query_metrics", CHECKOUT, RiskLevel.READ_ONLY),
                new ToolCallStarted("llm-2_0", key));
        llm.script(() -> answers("Done."));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(tools.invocations).extracting(ToolInvocation::idempotencyKey).containsExactly(key);
        assertThat(eventsOf(ToolCallStarted.class)).hasSize(2);
    }

    @Test
    void anEffectCutByACrashGoesToReconciliationAndNeverRunsAgain() {
        LlmToolCall restart = new LlmToolCall("llm-2_0", "restart_pod", CHECKOUT);
        leftByACrash(new LlmCallStarted("llm-2", MODEL), new LlmCallCompleted("llm-2", 1, 1, List.of(restart), ""),
                new ToolCallProposed("llm-2_0", "restart_pod", CHECKOUT, RiskLevel.SIDE_EFFECT),
                new ToolCallStarted("llm-2_0", turn + ":llm-2_0"));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.AWAITING_RECONCILIATION);
        assertThat(events().getLast()).isEqualTo(new ToolOutcomeUnknown("llm-2_0"));
        assertThat(tools.invocations).isEmpty();
    }

    @Test
    void aFailedModelCallEndsTheRunAndTheNextRunIsTheRetry() {
        llm.script(() -> {
            throw new IllegalStateException("connection refused");
        }, () -> answers("Back."));

        TurnState failed = advanceTurn.run(turn);

        assertThat(failed.status()).isEqualTo(TurnStatus.ACTIVE);
        assertThat(eventsOf(LlmCallFailed.class)).extracting(LlmCallFailed::error)
                .containsExactly("connection refused");
        assertThat(llm.requests).hasSize(1);

        assertThat(advanceTurn.run(turn).status()).isEqualTo(TurnStatus.COMPLETED);
    }

    @Test
    void aModelThatNeverComesBackFailsTheTurnAtTheLimit() {
        for (int i = 0; i < TurnDecider.MAX_LLM_CALLS; i++) {
            llm.script(() -> {
                throw new IllegalStateException("connection refused");
            });
            assertThat(advanceTurn.run(turn).status()).isEqualTo(TurnStatus.ACTIVE);
        }

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(eventsOf(LlmCallStarted.class)).hasSize(TurnDecider.MAX_LLM_CALLS);
        assertThat(events().getLast()).isInstanceOf(TurnFailed.class);
    }

    @Test
    void aModelThatAsksForToolsForeverFailsTheTurnAtTheLimit() {
        for (int i = 0; i < TurnDecider.MAX_LLM_CALLS; i++) {
            llm.script(() -> asks("query_metrics"));
        }

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(eventsOf(LlmCallStarted.class)).hasSize(TurnDecider.MAX_LLM_CALLS);
        assertThat(tools.invocations).as("what the last call asks for does not run")
                .hasSize(TurnDecider.MAX_LLM_CALLS - 1);
    }

    @Test
    void anEmptyAnswerIsAFailureAndDoesNotCompleteTheTurn() {
        llm.script(() -> answers(" "), () -> answers("Back."));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.ACTIVE);
        assertThat(eventsOf(LlmCallFailed.class)).extracting(LlmCallFailed::error)
                .containsExactly("the model answered nothing");
        assertThat(eventsOf(TurnCompleted.class)).isEmpty();

        assertThat(advanceTurn.run(turn).status()).isEqualTo(TurnStatus.COMPLETED);
    }

    @Test
    void toolCallsWithNoTextAreNotAnEmptyAnswer() {
        llm.script(() -> asks("query_metrics"), () -> answers("Done."));

        assertThat(advanceTurn.run(turn).status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(eventsOf(LlmCallFailed.class)).isEmpty();
    }

    @Test
    void resultsReachTheModelInTheOrderItAskedNotTheOrderTheyRan() {
        llm.script(() -> asks("rollback_deployment", "query_metrics"), () -> answers("Done."));
        advanceTurn.run(turn);
        assertThat(tools.invocations).extracting(ToolInvocation::tool)
                .as("the query runs first, while the rollback waits")
                .containsExactly("query_metrics");

        record(new ApprovalGranted("llm-2_0", "approver-a"));
        advanceTurn.run(turn);

        assertThat(toolResults(llm.requests.getLast())).extracting(LlmMessage.ToolResult::tool)
                .containsExactly("rollback_deployment", "query_metrics");
        assertThat(events().getLast())
                .isEqualTo(new TurnCompleted("Done.", "tools run: query_metrics, rollback_deployment"));
    }

    @Test
    void theProvidersToolCallIdsAreNotUsed() {
        llm.script(() -> asks("query_metrics", "query_metrics"), () -> asks("query_metrics"),
                () -> answers("Done."));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(eventsOf(ToolCallProposed.class)).extracting(ToolCallProposed::toolCallId)
                .doesNotContain("provider-id")
                .doesNotHaveDuplicates()
                .hasSize(3);
    }

    @Test
    void aToolThatCertainlyFailedIsToldToTheModel() {
        tools.onCall("restart_pod", invocation -> {
            throw new ToolFailedException("pod not found");
        });
        llm.script(() -> asks("restart_pod"), () -> answers("Could not restart."));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(eventsOf(ToolCallFailed.class)).extracting(ToolCallFailed::error).containsExactly("pod not found");
        assertThat(toolResults(llm.requests.getLast())).extracting(LlmMessage.ToolResult::content)
                .containsExactly("The tool failed: pod not found");
    }

    @Test
    void anEffectWithAnUnknownOutcomeGoesToReconciliationAndNotToTheModel() {
        tools.onCall("restart_pod", invocation -> {
            throw new IllegalStateException("read timed out");
        });
        llm.script(() -> asks("restart_pod"));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.AWAITING_RECONCILIATION);
        assertThat(eventsOf(ToolOutcomeUnknown.class)).hasSize(1);
        assertThat(llm.requests).hasSize(1);
    }

    @Test
    void theOutcomeAHumanReconciledIsWhatTheModelReads() {
        tools.onCall("restart_pod", invocation -> {
            throw new IllegalStateException("read timed out");
        });
        llm.script(() -> asks("restart_pod"), () -> answers("Restarted."));
        advanceTurn.run(turn);

        record(new ToolCallCompleted("llm-2_0", "it did restart"));
        assertThat(advanceTurn.run(turn).status()).isEqualTo(TurnStatus.COMPLETED);

        assertThat(toolResults(llm.requests.getLast())).extracting(LlmMessage.ToolResult::content)
                .containsExactly("it did restart");
    }

    @Test
    void aReadOnlyToolThatThrowsIsSimplyAFailureNamedByItsClassWhenItHasNoMessage() {
        tools.onCall("query_metrics", invocation -> {
            throw new IllegalArgumentException();
        });
        llm.script(() -> asks("query_metrics"), () -> answers("No metrics."));

        advanceTurn.run(turn);

        assertThat(eventsOf(ToolCallFailed.class)).extracting(ToolCallFailed::error)
                .containsExactly(IllegalArgumentException.class.getName());
    }

    @Test
    void aLongResultIsCutBeforeItReachesTheStream() {
        tools.onCall("query_metrics", invocation -> "x".repeat(AdvanceTurn.MAX_TEXT_LENGTH + 1));
        llm.script(() -> asks("query_metrics"), () -> answers("Done."));

        advanceTurn.run(turn);

        assertThat(eventsOf(ToolCallCompleted.class).getFirst().result())
                .startsWith("xxx")
                .endsWith(" [truncated]")
                .hasSize(AdvanceTurn.MAX_TEXT_LENGTH + " [truncated]".length());
    }

    @Test
    void losingARaceOnAnOutcomeRecordsItWithoutRunningTheEffectAgain() {
        store.loseTheRaceOn(ToolCallCompleted.class::isInstance, new TurnSuspended("operator"));
        llm.script(() -> asks("restart_pod"));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.SUSPENDED);
        assertThat(tools.invocations).hasSize(1);
        assertThat(eventsOf(ToolCallCompleted.class)).hasSize(1);
        assertThat(eventsOf(ToolOutcomeUnknown.class)).as("the outcome was at hand, so nothing is unknown").isEmpty();

        record(new TurnResumed(SuspensionCause.OPERATOR));
        llm.script(() -> answers("Done."));
        assertThat(advanceTurn.run(turn).status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(tools.invocations).hasSize(1);
    }

    @Test
    void losingARaceOnAModelAnswerKeepsItAndDoesNotCallAgain() {
        store.loseTheRaceOn(LlmCallCompleted.class::isInstance, new TurnSuspended("operator"));
        llm.script(() -> answers("Done."));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.SUSPENDED);
        assertThat(llm.requests).as("the answer was paid for and at hand").hasSize(1);
        assertThat(eventsOf(LlmCallCompleted.class)).hasSize(1);
        assertThat(eventsOf(LlmCallFailed.class)).isEmpty();
    }

    @Test
    void losingARaceOnAFailedModelCallRecordsTheFailureOnce() {
        store.loseTheRaceOn(LlmCallFailed.class::isInstance, new TurnSuspended("operator"));
        llm.script(() -> {
            throw new IllegalStateException("connection refused");
        });

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.SUSPENDED);
        assertThat(llm.requests).hasSize(1);
        assertThat(eventsOf(LlmCallFailed.class)).extracting(LlmCallFailed::error)
                .containsExactly("connection refused");
    }

    @Test
    void losingARaceOnAStartDecidesAgainAndRunsNothing() {
        store.loseTheRaceOn(ToolCallStarted.class::isInstance, new TurnSuspended("operator"));
        llm.script(() -> asks("restart_pod"));

        TurnState state = advanceTurn.run(turn);

        assertThat(state.status()).isEqualTo(TurnStatus.SUSPENDED);
        assertThat(tools.invocations).isEmpty();
        assertThat(eventsOf(ToolCallStarted.class)).isEmpty();
    }

    @Test
    void anOutcomeTheStreamCanNoLongerTakeIsAnErrorAndIsNotWritten() {
        store.loseTheRaceOn(ToolCallCompleted.class::isInstance, new TurnCancelled("operator"));
        llm.script(() -> asks("restart_pod"));

        assertThatIllegalStateException().isThrownBy(() -> advanceTurn.run(turn));

        assertThat(eventsOf(ToolCallCompleted.class)).isEmpty();
        assertThat(Turn.fold(events()).status()).isEqualTo(TurnStatus.CANCELLED);
    }

    /** Answers from a script, in order, and fails the test when asked more than the script allows. */
    private static final class ScriptedLlm implements LlmPort {

        private final Deque<Supplier<LlmResponse>> script = new ArrayDeque<>();
        final List<LlmRequest> requests = new ArrayList<>();

        @SafeVarargs
        final void script(Supplier<LlmResponse>... steps) {
            script.addAll(List.of(steps));
        }

        @Override
        public String model() {
            return MODEL;
        }

        @Override
        public LlmResponse call(LlmRequest request) {
            requests.add(request);
            if (script.isEmpty()) {
                throw new AssertionError("the model was called more times than scripted");
            }
            return script.removeFirst().get();
        }
    }

    /** Records every invocation; a tool returns {@code result of <name>} unless told otherwise. */
    private static final class ScriptedTools implements ToolPort {

        private final Map<String, Function<ToolInvocation, String>> behaviour = new HashMap<>();
        final List<ToolInvocation> invocations = new ArrayList<>();

        void onCall(String tool, Function<ToolInvocation, String> what) {
            behaviour.put(tool, what);
        }

        @Override
        public String execute(ToolInvocation invocation) {
            invocations.add(invocation);
            return behaviour.getOrDefault(invocation.tool(), call -> "result of " + call.tool()).apply(invocation);
        }
    }
}
