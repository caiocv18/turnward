package io.github.caiocv18.turnward.domain.turn;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.caiocv18.turnward.domain.tool.RiskLevel;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ApprovalGranted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.BudgetExceeded;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallCompleted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallFailed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.LlmCallStarted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.ToolCallStarted;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnResumed;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnSuspended;
import io.github.caiocv18.turnward.domain.turn.command.TurnCommand;
import io.github.caiocv18.turnward.domain.turn.command.TurnDecider;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.statistics.Statistics;

/**
 * Properties of the fold and the decider over streams built by {@link TurnScenarios}. Each property also checks
 * that the case it is about actually shows up, so a weak generator fails the build instead of passing empty.
 */
class TurnPropertiesTest {

    /**
     * Folding is deterministic and incremental, and the parts of the state that can be read straight off the events
     * (version, model call in flight, causes of suspension) agree with a replay that does not use the fold.
     */
    @Property(tries = 1000)
    void foldOfEveryPrefixIsDeterministicIncrementalAndTrueToTheEvents(
            @ForAll @Size(max = 60) List<@IntRange(min = 0, max = 99) Integer> choices) {
        List<TurnEvent> events = TurnScenarios.play(choices);
        TurnState previous = TurnState.initial();
        Optional<String> inFlight = Optional.empty();
        Set<SuspensionCause> causes = EnumSet.noneOf(SuspensionCause.class);
        boolean resumedOnce = false;
        for (int end = 1; end <= events.size(); end++) {
            TurnEvent last = events.get(end - 1);
            switch (last) {
                case LlmCallStarted started -> inFlight = Optional.of(started.callId());
                case LlmCallCompleted completed -> inFlight = Optional.empty();
                case LlmCallFailed failed -> inFlight = Optional.empty();
                case TurnSuspended suspended -> causes.add(SuspensionCause.OPERATOR);
                case BudgetExceeded exceeded -> causes.add(SuspensionCause.BUDGET);
                case TurnResumed resumed -> {
                    causes.remove(resumed.cause());
                    resumedOnce = true;
                }
                default -> {
                }
            }
            TurnState folded = Turn.fold(events.subList(0, end));

            assertThat(Turn.fold(List.copyOf(events.subList(0, end)))).isEqualTo(folded);
            assertThat(Turn.evolve(previous, last)).isEqualTo(folded);
            assertThat(folded.version()).isEqualTo(end);
            assertThat(folded.llmCallInFlight()).isEqualTo(inFlight);
            assertThat(folded.suspendedBy()).isEqualTo(causes);
            if (!folded.status().isTerminal()) {
                assertThat(folded.status() == TurnStatus.SUSPENDED).isEqualTo(!causes.isEmpty());
            }
            previous = folded;
        }
        Statistics.label("ends terminal").collect(previous.status().isTerminal());
        Statistics.label("ends terminal").coverage(check -> check.check(true).percentage(p -> p > 10));
        Statistics.label("resumed a suspension").collect(resumedOnce);
        Statistics.label("resumed a suspension").coverage(check -> check.check(true).percentage(p -> p > 5));
    }

    @Property(tries = 1000)
    void noDangerousToolRunsWithoutItsApproval(
            @ForAll @Size(max = 60) List<@IntRange(min = 0, max = 99) Integer> choices) {
        List<TurnEvent> events = TurnScenarios.play(choices);
        boolean dangerousRan = false;
        for (int end = 1; end <= events.size(); end++) {
            List<TurnEvent> prefix = events.subList(0, end);
            TurnState state = Turn.fold(prefix);
            if (TurnDecider.decide(state) instanceof TurnCommand.ExecuteTool execute
                    && execute.call().riskLevel() == RiskLevel.DANGEROUS) {
                assertThat(prefix).contains(new ApprovalGranted(execute.call().toolCallId(), "approver"));
            }
            if (events.get(end - 1) instanceof ToolCallStarted started
                    && state.toolCall(started.toolCallId()).orElseThrow().riskLevel() == RiskLevel.DANGEROUS) {
                assertThat(prefix).contains(new ApprovalGranted(started.toolCallId(), "approver"));
                dangerousRan = true;
            }
        }
        Statistics.label("dangerous tool ran").collect(dangerousRan);
        Statistics.label("dangerous tool ran").coverage(check -> check.check(true).percentage(p -> p > 5));
    }

    /**
     * Right after a {@code ToolCallStarted} the effect may or may not have happened. Only a read-only call is run
     * again; any other risk level goes to reconciliation. The list of risks is written here on purpose, not taken
     * from {@code ToolPolicy}, so a wrong policy cannot make this property agree with itself.
     */
    @Property(tries = 1000)
    void callCutAfterItsStartIsNeverRunBlindly(
            @ForAll @Size(max = 60) List<@IntRange(min = 0, max = 99) Integer> choices) {
        List<TurnEvent> events = TurnScenarios.play(choices);
        Set<RiskLevel> cut = EnumSet.noneOf(RiskLevel.class);
        for (int end = 1; end <= events.size(); end++) {
            if (!(events.get(end - 1) instanceof ToolCallStarted started)) {
                continue;
            }
            TurnState state = Turn.fold(events.subList(0, end));
            ToolCall call = state.toolCall(started.toolCallId()).orElseThrow();
            TurnCommand expected = call.riskLevel() == RiskLevel.READ_ONLY ? new TurnCommand.ExecuteTool(call, true)
                    : new TurnCommand.Reconcile(call);
            assertThat(TurnDecider.decide(state)).isEqualTo(expected);
            cut.add(call.riskLevel());
        }
        for (RiskLevel risk : RiskLevel.values()) {
            Statistics.label("cut after start: " + risk).collect(cut.contains(risk));
            Statistics.label("cut after start: " + risk).coverage(check -> check.check(true).percentage(p -> p > 2));
        }
    }

    /**
     * The worker drops the lease exactly when the status is idle, so the decider must have nothing to run then, and
     * something to run otherwise. A waiting status also has to name a call that is really waiting.
     */
    @Property(tries = 1000)
    void turnIsIdleExactlyWhenTheDeciderHalts(
            @ForAll @Size(max = 60) List<@IntRange(min = 0, max = 99) Integer> choices) {
        List<TurnEvent> events = TurnScenarios.play(choices);
        Set<TurnStatus> seen = EnumSet.noneOf(TurnStatus.class);
        for (int end = 1; end <= events.size(); end++) {
            TurnState state = Turn.fold(events.subList(0, end));
            boolean halts = TurnDecider.decide(state) instanceof TurnCommand.Halt;

            assertThat(halts).isEqualTo(state.status().isIdle());
            // Waiting for approval means every other call is settled or waiting too.
            if (state.status() == TurnStatus.AWAITING_APPROVAL) {
                assertThat(state.llmCallInFlight()).isEmpty();
                assertThat(state.pendingProposals()).isEmpty();
                assertThat(state.toolCalls()).extracting(ToolCall::phase).contains(ToolCallPhase.APPROVAL_REQUESTED)
                        .doesNotContain(ToolCallPhase.PROPOSED, ToolCallPhase.APPROVED, ToolCallPhase.STARTED,
                                ToolCallPhase.OUTCOME_UNKNOWN);
            }
            // An unknown outcome blocks everything else on purpose, except a cut effect, which is handled first.
            if (state.status() == TurnStatus.AWAITING_RECONCILIATION) {
                assertThat(state.toolCalls()).extracting(ToolCall::phase).contains(ToolCallPhase.OUTCOME_UNKNOWN)
                        .doesNotContain(ToolCallPhase.STARTED);
            }
            seen.add(state.status());
        }
        for (TurnStatus waiting : List.of(TurnStatus.AWAITING_APPROVAL, TurnStatus.AWAITING_RECONCILIATION,
                TurnStatus.SUSPENDED)) {
            Statistics.label("reached " + waiting).collect(seen.contains(waiting));
            Statistics.label("reached " + waiting).coverage(check -> check.check(true).percentage(p -> p > 5));
        }
    }
}
