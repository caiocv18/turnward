package io.github.caiocv18.turnward.application.turn;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.caiocv18.turnward.domain.turn.AlertContext;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnStarted;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StartTurnTest {

    private static final Instant STARTS_AT = Instant.parse("2026-11-02T12:00:00Z");

    private final InMemoryEventStore store = new InMemoryEventStore();
    private final StartTurn startTurn = new StartTurn(store);

    private static TurnStarted firing(String tenant, Instant startsAt) {
        return new TurnStarted("fp-1", tenant, "critical",
                new AlertContext("HighErrorRate", Map.of("service", "checkout-demo"), Map.of(), startsAt));
    }

    @Test
    void anAlertOpensATurn() {
        StartTurn.Result result = startTurn.start(firing("acme", STARTS_AT));

        assertThat(result.created()).isTrue();
        assertThat(store.load(result.turnId())).hasSize(1);
        assertThat(store.load(result.turnId()).getFirst().event()).isEqualTo(firing("acme", STARTS_AT));
    }

    @Test
    void aRedeliveryReturnsTheSameTurnAndWritesNothing() {
        StartTurn.Result first = startTurn.start(firing("acme", STARTS_AT));

        StartTurn.Result second = startTurn.start(firing("acme", STARTS_AT));

        assertThat(second.created()).isFalse();
        assertThat(second.turnId()).isEqualTo(first.turnId());
        assertThat(store.load(first.turnId())).hasSize(1);
    }

    @Test
    void firingAgainAfterResolvingIsANewTurn() {
        StartTurn.Result first = startTurn.start(firing("acme", STARTS_AT));

        StartTurn.Result again = startTurn.start(firing("acme", STARTS_AT.plusSeconds(3600)));

        assertThat(again.created()).isTrue();
        assertThat(again.turnId()).isNotEqualTo(first.turnId());
    }

    @Test
    void theSameAlertInAnotherTenantIsANewTurn() {
        StartTurn.Result first = startTurn.start(firing("acme", STARTS_AT));

        StartTurn.Result other = startTurn.start(firing("globex", STARTS_AT));

        assertThat(other.created()).isTrue();
        assertThat(other.turnId()).isNotEqualTo(first.turnId());
    }
}
