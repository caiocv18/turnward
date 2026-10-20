package io.github.caiocv18.turnward.domain.turn.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.caiocv18.turnward.domain.tool.RiskLevel;
import io.github.caiocv18.turnward.domain.turn.LlmToolCall;
import io.github.caiocv18.turnward.domain.turn.SuspensionCause;
import io.github.caiocv18.turnward.domain.turn.ToolCall;
import io.github.caiocv18.turnward.domain.turn.Turn;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalDenied;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalGranted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalRequested;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.BudgetExceeded;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallCompleted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallFailed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallStarted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ToolCallCompleted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ToolCallProposed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ToolCallStarted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ToolOutcomeUnknown;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnCompleted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnResumed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnStarted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnSuspended;
import io.github.caiocv18.turnward.domain.turn.TurnState;
import io.github.caiocv18.turnward.domain.turn.TurnStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TurnDeciderTest {

    private static final TurnStarted STARTED = new TurnStarted("fp-1", "tenant-a", "critical");
    private static final LlmToolCall QUERY = new LlmToolCall("t1", "query_metrics", Map.of("q", "errors"));
    private static final LlmToolCall ROLLBACK = new LlmToolCall("t2", "rollback_deployment", Map.of());
    private static final LlmToolCall RESTART = new LlmToolCall("t3", "restart_pod", Map.of());

    private static TurnCommand decide(TurnEvent... events) {
        return TurnDecider.decide(Turn.fold(List.of(events)));
    }

    private static TurnEvent[] asked(TurnEvent... more) {
        List<TurnEvent> all = new ArrayList<>(List.of(STARTED, new LlmCallStarted("c1", "haiku"),
                new LlmCallCompleted("c1", 100, 20, List.of(QUERY, ROLLBACK, RESTART))));
        all.addAll(List.of(more));
        return all.toArray(TurnEvent[]::new);
    }

    private static TurnEvent[] append(TurnEvent[] events, TurnEvent... more) {
        List<TurnEvent> all = new ArrayList<>(List.of(events));
        all.addAll(List.of(more));
        return all.toArray(TurnEvent[]::new);
    }

    private static ToolCallProposed propose(LlmToolCall call, RiskLevel risk) {
        return new ToolCallProposed(call.toolCallId(), call.tool(), call.args(), risk);
    }

    private static ToolCall call(TurnState state, String toolCallId) {
        return state.toolCall(toolCallId).orElseThrow();
    }

    @Test
    void refusesToDecideForATurnThatDoesNotExist() {
        assertThatIllegalStateException().isThrownBy(() -> TurnDecider.decide(TurnState.initial()));
    }

    @Test
    void freshTurnCallsTheModel() {
        assertThat(decide(STARTED)).isEqualTo(new TurnCommand.CallLlm());
    }

    @Test
    void modelCallLostInACrashIsAbandonedThenStartedAfresh() {
        assertThat(decide(STARTED, new LlmCallStarted("c1", "haiku")))
                .isEqualTo(new TurnCommand.AbandonLlmCall("c1"));
        assertThat(decide(STARTED, new LlmCallStarted("c1", "haiku"), new LlmCallFailed("c1", "lost in crash")))
                .isEqualTo(new TurnCommand.CallLlm());
    }

    @Test
    void answerWithoutToolsCompletesTheTurn() {
        assertThat(decide(STARTED, new LlmCallStarted("c1", "haiku"),
                new LlmCallCompleted("c1", 100, 20, List.of()))).isEqualTo(new TurnCommand.Complete());
    }

    @Test
    void toolsAreProposedInTheModelsOrder() {
        assertThat(decide(asked())).isEqualTo(new TurnCommand.ProposeTool(QUERY));
        assertThat(decide(asked(propose(QUERY, RiskLevel.READ_ONLY)))).isEqualTo(new TurnCommand.ProposeTool(ROLLBACK));
    }

    @Test
    void runnableToolGoesBeforeAskingForApproval() {
        TurnState state = Turn.fold(List.of(asked(propose(QUERY, RiskLevel.READ_ONLY),
                propose(ROLLBACK, RiskLevel.DANGEROUS), propose(RESTART, RiskLevel.DANGEROUS))));

        assertThat(TurnDecider.decide(state)).isEqualTo(new TurnCommand.ExecuteTool(call(state, "t1"), false));
    }

    @Test
    void everyApprovalIsAskedForBeforeTheTurnParks() {
        TurnEvent[] proposed = asked(propose(QUERY, RiskLevel.READ_ONLY), propose(ROLLBACK, RiskLevel.DANGEROUS),
                propose(RESTART, RiskLevel.DANGEROUS), new ToolCallStarted("t1", "k1"),
                new ToolCallCompleted("t1", "r1"));
        TurnState first = Turn.fold(List.of(proposed));
        assertThat(TurnDecider.decide(first)).isEqualTo(new TurnCommand.AwaitApproval(call(first, "t2")));

        TurnState second = Turn.fold(List.of(append(proposed, new ApprovalRequested("t2"))));
        assertThat(second.status()).isEqualTo(TurnStatus.ACTIVE);
        assertThat(TurnDecider.decide(second)).isEqualTo(new TurnCommand.AwaitApproval(call(second, "t3")));

        TurnEvent[] bothAsked = append(proposed, new ApprovalRequested("t2"), new ApprovalRequested("t3"));
        TurnState parked = Turn.fold(List.of(bothAsked));
        assertThat(parked.status()).isEqualTo(TurnStatus.AWAITING_APPROVAL);
        assertThat(TurnDecider.decide(parked)).isEqualTo(new TurnCommand.Halt());

        TurnState approved = Turn.fold(List.of(append(bothAsked, new ApprovalGranted("t3", "ana"))));
        assertThat(approved.status()).isEqualTo(TurnStatus.ACTIVE);
        assertThat(TurnDecider.decide(approved)).isEqualTo(new TurnCommand.ExecuteTool(call(approved, "t3"), false));
    }

    @Test
    void verdictsGoBackToTheModelOnceNothingIsLeft() {
        TurnEvent[] settled = asked(propose(QUERY, RiskLevel.READ_ONLY), propose(ROLLBACK, RiskLevel.DANGEROUS),
                propose(RESTART, RiskLevel.SIDE_EFFECT), new ToolCallStarted("t1", "k1"),
                new ToolCallCompleted("t1", "r1"), new ToolCallStarted("t3", "k3"), new ToolCallCompleted("t3", "r3"),
                new ApprovalRequested("t2"), new ApprovalDenied("t2", "ana", "no"));

        assertThat(decide(settled)).isEqualTo(new TurnCommand.CallLlm());
    }

    @Test
    void readOnlyCallCutByACrashIsRetried() {
        TurnState state = Turn.fold(List.of(asked(propose(QUERY, RiskLevel.READ_ONLY),
                new ToolCallStarted("t1", "k1"))));

        assertThat(TurnDecider.decide(state)).isEqualTo(new TurnCommand.ExecuteTool(call(state, "t1"), true));
    }

    @Test
    void callWithSideEffectsCutByACrashIsReconciledThenParked() {
        TurnEvent[] cut = asked(propose(QUERY, RiskLevel.SIDE_EFFECT), new ToolCallStarted("t1", "k1"));
        TurnState started = Turn.fold(List.of(cut));
        assertThat(TurnDecider.decide(started)).isEqualTo(new TurnCommand.Reconcile(call(started, "t1")));

        TurnState unknown = Turn.fold(List.of(append(cut, new ToolOutcomeUnknown("t1"))));
        assertThat(unknown.status()).isEqualTo(TurnStatus.AWAITING_RECONCILIATION);
        assertThat(TurnDecider.decide(unknown)).isEqualTo(new TurnCommand.Halt());
    }

    @Test
    void suspendedOrEndedTurnHalts() {
        assertThat(decide(STARTED, new TurnSuspended("operator"))).isEqualTo(new TurnCommand.Halt());
        assertThat(decide(STARTED, new BudgetExceeded("tenant-a", 0))).isEqualTo(new TurnCommand.Halt());
        assertThat(decide(STARTED, new TurnCompleted("ok", "done"))).isEqualTo(new TurnCommand.Halt());
        assertThat(decide(STARTED, new TurnSuspended("operator"), new TurnResumed(SuspensionCause.OPERATOR)))
                .isEqualTo(new TurnCommand.CallLlm());
    }
}
