package io.github.caiocv18.turnward.domain.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnFailed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnResumed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnStarted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnSuspended;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TurnTest {

    static final TurnStarted STARTED = new TurnStarted("fp-1", "tenant-a", "critical");
    static final LlmToolCall ROLLBACK = new LlmToolCall("t1", "rollback_deployment", Map.of("service", "checkout"));
    static final LlmToolCall QUERY = new LlmToolCall("t2", "query_metrics", Map.of("q", "errors"));

    /** Started, one model call that asked for {@code ROLLBACK}, proposed with the given risk. */
    static List<TurnEvent> proposed(RiskLevel risk) {
        return List.of(STARTED,
                new LlmCallStarted("c1", "haiku"),
                new LlmCallCompleted("c1", 100, 20, List.of(ROLLBACK)),
                new ToolCallProposed("t1", "rollback_deployment", ROLLBACK.args(), risk));
    }

    static List<TurnEvent> plus(List<TurnEvent> events, TurnEvent... more) {
        List<TurnEvent> all = new ArrayList<>(events);
        all.addAll(List.of(more));
        return all;
    }

    private static ToolCallPhase phaseOf(List<TurnEvent> events) {
        return Turn.fold(events).toolCall("t1").orElseThrow().phase();
    }

    @Test
    void emptyStreamFoldsToTheInitialState() {
        assertThat(Turn.fold(List.of())).isEqualTo(TurnState.initial());
        assertThat(TurnState.initial().status()).isEqualTo(TurnStatus.NOT_STARTED);
    }

    @Test
    void startedTurnIsActiveKeepsItsTenantAndCountsVersions() {
        TurnState state = Turn.fold(List.of(STARTED, new RunbookContextRetrieved(List.of("k1"), List.of(0.8))));

        assertThat(state.status()).isEqualTo(TurnStatus.ACTIVE);
        assertThat(state.tenantId()).contains("tenant-a");
        assertThat(state.version()).isEqualTo(2);
    }

    @Test
    void modelCallInFlightUntilItsOutcome() {
        List<TurnEvent> inFlight = List.of(STARTED, new LlmCallStarted("c1", "haiku"));
        assertThat(Turn.fold(inFlight).llmCallInFlight()).contains("c1");

        TurnState answered = Turn.fold(plus(inFlight, new LlmCallCompleted("c1", 10, 5, List.of())));
        assertThat(answered.llmCallInFlight()).isEmpty();
        assertThat(answered.lastLlmOutcome()).isEqualTo(LlmOutcome.ANSWERED);

        TurnState failed = Turn.fold(plus(inFlight, new LlmCallFailed("c1", "lost in crash")));
        assertThat(failed.lastLlmOutcome()).isEqualTo(LlmOutcome.FAILED);
        assertThat(Turn.fold(plus(inFlight, new LlmCallFailed("c1", "lost in crash"),
                new LlmCallStarted("c2", "haiku"))).llmCallInFlight())
                .as("a lost attempt is closed before the next one starts")
                .contains("c2");
    }

    @Test
    void toolCallsAskedByTheModelWaitToBeProposed() {
        TurnState state = Turn.fold(List.of(STARTED, new LlmCallStarted("c1", "haiku"),
                new LlmCallCompleted("c1", 10, 5, List.of(ROLLBACK))));

        assertThat(state.lastLlmOutcome()).isEqualTo(LlmOutcome.REQUESTED_TOOLS);
        assertThat(state.pendingProposals()).containsExactly(ROLLBACK);
        assertThat(state.hasOpenToolCalls()).isTrue();

        TurnState after = Turn.fold(proposed(RiskLevel.DANGEROUS));
        assertThat(after.pendingProposals()).isEmpty();
        assertThat(after.toolCall("t1")).get()
                .returns(RiskLevel.DANGEROUS, ToolCall::riskLevel)
                .returns(ToolCallPhase.PROPOSED, ToolCall::phase);
        assertThat(after.toolCall("missing")).isEmpty();
    }

    @Test
    void approvalRoundTripMovesTheTurnInAndOutOfWaiting() {
        List<TurnEvent> requested = plus(proposed(RiskLevel.DANGEROUS), new ApprovalRequested("t1"));
        assertThat(Turn.fold(requested).status()).isEqualTo(TurnStatus.AWAITING_APPROVAL);

        assertThat(phaseOf(plus(requested, new ApprovalGranted("t1", "ana")))).isEqualTo(ToolCallPhase.APPROVED);
        assertThat(phaseOf(plus(requested, new ApprovalDenied("t1", "ana", "no")))).isEqualTo(ToolCallPhase.DENIED);
        assertThat(phaseOf(plus(requested, new ApprovalExpired("t1")))).isEqualTo(ToolCallPhase.EXPIRED);
        assertThat(Turn.fold(plus(requested, new ApprovalGranted("t1", "ana"))).status())
                .isEqualTo(TurnStatus.ACTIVE);
    }

    @Test
    void turnDoesNotWaitForAHumanWhileAnotherCallCanRun() {
        List<TurnEvent> events = List.of(STARTED, new LlmCallStarted("c1", "haiku"),
                new LlmCallCompleted("c1", 100, 20, List.of(ROLLBACK, QUERY)),
                new ToolCallProposed("t1", "rollback_deployment", ROLLBACK.args(), RiskLevel.DANGEROUS),
                new ToolCallProposed("t2", "query_metrics", QUERY.args(), RiskLevel.READ_ONLY),
                new ApprovalRequested("t1"));

        assertThat(Turn.fold(events).status()).isEqualTo(TurnStatus.ACTIVE);
        assertThat(Turn.fold(plus(events, new ToolCallStarted("t2", "k"), new ToolCallCompleted("t2", "r"))).status())
                .isEqualTo(TurnStatus.AWAITING_APPROVAL);
    }

    @Test
    void onlyDangerousCallsAreSentForApproval() {
        assertThatIllegalStateException()
                .isThrownBy(() -> Turn.fold(plus(proposed(RiskLevel.SIDE_EFFECT), new ApprovalRequested("t1"))))
                .withMessageContaining("needs no approval");
    }

    @Test
    void toolOutcomesFollowTheStart() {
        List<TurnEvent> started = plus(proposed(RiskLevel.SIDE_EFFECT), new ToolCallStarted("t1", "k"));
        assertThat(phaseOf(started)).isEqualTo(ToolCallPhase.STARTED);
        assertThat(phaseOf(plus(started, new ToolCallCompleted("t1", "r1")))).isEqualTo(ToolCallPhase.COMPLETED);
        assertThat(phaseOf(plus(started, new ToolCallFailed("t1", "boom")))).isEqualTo(ToolCallPhase.FAILED);

        List<TurnEvent> unknown = plus(started, new ToolOutcomeUnknown("t1"));
        assertThat(phaseOf(unknown)).isEqualTo(ToolCallPhase.OUTCOME_UNKNOWN);
        assertThat(Turn.fold(unknown).status()).isEqualTo(TurnStatus.AWAITING_RECONCILIATION);

        List<TurnEvent> reconciled = plus(unknown, new ToolCallCompleted("t1", "reconciled"));
        assertThat(phaseOf(reconciled)).isEqualTo(ToolCallPhase.COMPLETED);
        assertThat(Turn.fold(reconciled).status()).isEqualTo(TurnStatus.ACTIVE);
    }

    @Test
    void readOnlyCallMayStartAgainAfterACrashAndIsNeverReconciled() {
        List<TurnEvent> started = plus(proposed(RiskLevel.READ_ONLY), new ToolCallStarted("t1", "k"));

        assertThat(phaseOf(plus(started, new ToolCallStarted("t1", "k")))).isEqualTo(ToolCallPhase.STARTED);
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(plus(started, new ToolOutcomeUnknown("t1"))))
                .withMessageContaining("retried, not reconciled");
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(
                plus(proposed(RiskLevel.SIDE_EFFECT), new ToolCallStarted("t1", "k"), new ToolCallStarted("t1", "k"))));
    }

    @Test
    void noCallStartsAgainOnceItsOutcomeIsKnownOrUnknown() {
        for (RiskLevel risk : List.of(RiskLevel.READ_ONLY, RiskLevel.SIDE_EFFECT)) {
            List<TurnEvent> started = plus(proposed(risk), new ToolCallStarted("t1", "k"));
            assertThatIllegalStateException().isThrownBy(() -> Turn.fold(plus(started,
                    new ToolCallCompleted("t1", "r"), new ToolCallStarted("t1", "k"))))
                    .withMessageContaining("call may not start from COMPLETED");
            assertThatIllegalStateException().isThrownBy(() -> Turn.fold(plus(started,
                    new ToolCallFailed("t1", "boom"), new ToolCallStarted("t1", "k"))))
                    .withMessageContaining("call may not start from FAILED");
        }
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(plus(proposed(RiskLevel.SIDE_EFFECT),
                new ToolCallStarted("t1", "k"), new ToolOutcomeUnknown("t1"), new ToolCallStarted("t1", "k"))))
                .withMessageContaining("call may not start from OUTCOME_UNKNOWN");
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(plus(proposed(RiskLevel.DANGEROUS),
                new ApprovalRequested("t1"), new ApprovalGranted("t1", "ana"), new ToolCallStarted("t1", "k"),
                new ToolCallCompleted("t1", "r"), new ToolCallStarted("t1", "k"))))
                .withMessageContaining("call may not start from COMPLETED");
    }

    @Test
    void dangerousCallCannotStartWithoutApproval() {
        assertThatIllegalStateException()
                .isThrownBy(() -> Turn.fold(plus(proposed(RiskLevel.DANGEROUS), new ToolCallStarted("t1", "k"))))
                .withMessageContaining("call may not start from PROPOSED");
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(plus(proposed(RiskLevel.DANGEROUS),
                new ApprovalRequested("t1"), new ApprovalDenied("t1", "ana", "no"), new ToolCallStarted("t1", "k"))));
    }

    @Test
    void suspensionBlocksNewEffectsUntilResumed() {
        List<TurnEvent> suspended = List.of(STARTED, new TurnSuspended("operator"));
        assertThat(Turn.fold(suspended).status()).isEqualTo(TurnStatus.SUSPENDED);
        assertThatIllegalStateException()
                .isThrownBy(() -> Turn.fold(plus(suspended, new LlmCallStarted("c1", "haiku"))));
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(plus(suspended, new TurnSuspended("again"))))
                .withMessageContaining("already suspended");

        assertThat(Turn.fold(plus(suspended, new TurnResumed(SuspensionCause.OPERATOR))).status())
                .isEqualTo(TurnStatus.ACTIVE);
        assertThatIllegalStateException()
                .isThrownBy(() -> Turn.fold(List.of(STARTED, new TurnResumed(SuspensionCause.OPERATOR))))
                .withMessageContaining("not suspended by OPERATOR");
    }

    @Test
    void outcomesOfEffectsAlreadyRunningStillLandWhileSuspended() {
        List<TurnEvent> cut = plus(proposed(RiskLevel.SIDE_EFFECT), new ToolCallStarted("t1", "k"),
                new BudgetExceeded("tenant-a", 0));

        assertThat(phaseOf(plus(cut, new ToolCallCompleted("t1", "r")))).isEqualTo(ToolCallPhase.COMPLETED);
        assertThat(Turn.fold(plus(cut, new ToolCallCompleted("t1", "r"))).status()).isEqualTo(TurnStatus.SUSPENDED);
    }

    @Test
    void eachCauseOfSuspensionIsLiftedOnlyByItsOwnResume() {
        List<TurnEvent> both = List.of(STARTED, new TurnSuspended("operator"), new BudgetExceeded("tenant-a", 0));
        TurnState state = Turn.fold(both);
        assertThat(state.suspendedBy()).containsExactlyInAnyOrder(SuspensionCause.OPERATOR, SuspensionCause.BUDGET);
        assertThat(state.budgetExceeded()).isTrue();

        TurnState operatorLifted = Turn.fold(plus(both, new TurnResumed(SuspensionCause.OPERATOR)));
        assertThat(operatorLifted.status()).isEqualTo(TurnStatus.SUSPENDED);
        assertThat(operatorLifted.budgetExceeded()).isTrue();

        TurnState allLifted = Turn.fold(plus(both, new TurnResumed(SuspensionCause.BUDGET),
                new TurnResumed(SuspensionCause.OPERATOR)));
        assertThat(allLifted.status()).isEqualTo(TurnStatus.ACTIVE);
        assertThat(allLifted.budgetExceeded()).isFalse();
    }

    @Test
    void budgetOfAnotherTenantDoesNotTouchTheTurn() {
        assertThatIllegalStateException()
                .isThrownBy(() -> Turn.fold(List.of(STARTED, new BudgetExceeded("tenant-b", 0))))
                .withMessageContaining("another tenant");
    }

    @Test
    void terminalEventsEndTheTurnForGood() {
        assertThat(Turn.fold(List.of(STARTED, new TurnCompleted("ok", "restarted"))).status())
                .isEqualTo(TurnStatus.COMPLETED);
        assertThat(Turn.fold(List.of(STARTED, new TurnFailed("bug"))).status()).isEqualTo(TurnStatus.FAILED);
        assertThat(Turn.fold(List.of(STARTED, new TurnCancelled("ana"))).status()).isEqualTo(TurnStatus.CANCELLED);

        assertThatIllegalStateException()
                .isThrownBy(() -> Turn.fold(List.of(STARTED, new TurnFailed("bug"), new TurnSuspended("late"))))
                .withMessageContaining("turn already ended");
        assertThatIllegalStateException().isThrownBy(
                () -> Turn.fold(List.of(STARTED, new TurnSuspended("op"), new TurnCompleted("ok", "done"))));
    }

    @Test
    void modelIsNotCalledAgainWhileToolCallsAreOpen() {
        for (List<TurnEvent> open : List.of(
                List.<TurnEvent>of(STARTED, new LlmCallStarted("c1", "haiku"),
                        new LlmCallCompleted("c1", 1, 1, List.of(ROLLBACK))),
                proposed(RiskLevel.READ_ONLY),
                plus(proposed(RiskLevel.DANGEROUS), new ApprovalRequested("t1")),
                plus(proposed(RiskLevel.SIDE_EFFECT), new ToolCallStarted("t1", "k")))) {
            assertThatIllegalStateException().isThrownBy(() -> Turn.fold(plus(open, new LlmCallStarted("c2", "haiku"))))
                    .withMessageContaining("tool calls still open");
        }
    }

    @Test
    void rejectsCorruptedStreams() {
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(List.of(new LlmCallStarted("c1", "haiku"))))
                .withMessageContaining("turn not started");
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(List.of(STARTED, STARTED)));
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(
                List.of(STARTED, new LlmCallStarted("c1", "haiku"), new LlmCallStarted("c2", "haiku"))));
        assertThatIllegalStateException()
                .isThrownBy(() -> Turn.fold(List.of(STARTED, new LlmCallCompleted("c9", 1, 1, List.of()))));
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(List.of(STARTED,
                new LlmCallStarted("c1", "haiku"), new LlmCallCompleted("c1", 1, 1, List.of(ROLLBACK, ROLLBACK)))));
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(plus(proposed(RiskLevel.READ_ONLY),
                new ToolCallStarted("t1", "k"), new ToolCallCompleted("t1", "r"), new LlmCallStarted("c2", "haiku"),
                new LlmCallCompleted("c2", 1, 1, List.of(ROLLBACK)))))
                .withMessageContaining("tool call id reused");
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(List.of(STARTED,
                new ToolCallProposed("t1", "rollback_deployment", Map.of(), RiskLevel.READ_ONLY))))
                .withMessageContaining("did not ask");
        List<TurnEvent> asked = List.of(STARTED, new LlmCallStarted("c1", "haiku"),
                new LlmCallCompleted("c1", 1, 1, List.of(ROLLBACK)));
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(plus(asked,
                new ToolCallProposed("t1", "other_tool", ROLLBACK.args(), RiskLevel.READ_ONLY))))
                .withMessageContaining("differs from the model's");
        assertThatIllegalStateException().isThrownBy(() -> Turn.fold(plus(asked,
                new ToolCallProposed("t1", "rollback_deployment", Map.of("service", "payments"), RiskLevel.READ_ONLY))))
                .withMessageContaining("differs from the model's");
        assertThatIllegalStateException()
                .isThrownBy(() -> Turn.fold(List.of(STARTED, new ApprovalGranted("t1", "ana"))))
                .withMessageContaining("unknown tool call");
        assertThatIllegalStateException()
                .isThrownBy(() -> Turn.fold(plus(proposed(RiskLevel.DANGEROUS), new ApprovalGranted("t1", "ana"))))
                .withMessageContaining("call is PROPOSED");
    }

    @Test
    void stateAndEventsCopyTheirCollections() {
        List<String> chunks = new ArrayList<>(List.of("k1"));
        RunbookContextRetrieved retrieved = new RunbookContextRetrieved(chunks, List.of(0.5));
        chunks.add("k2");
        assertThat(retrieved.chunkIds()).containsExactly("k1");

        TurnState state = new TurnState(TurnStatus.ACTIVE, Optional.of("tenant-a"), Optional.empty(), LlmOutcome.NONE,
                List.of(), List.of(), Set.of(), 1);
        assertThat(state.toolCalls()).isEmpty();
        assertThat(state.hasOpenToolCalls()).isFalse();
    }
}
