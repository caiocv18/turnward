package io.github.caiocv18.turnward.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TurnIdTest {

    private static final String RAW = "0199f3a2-7c4e-7a10-8b2d-5f6e7d8c9b0a";

    @Test
    void rejectsNullValue() {
        assertThatNullPointerException().isThrownBy(() -> new TurnId(null));
        assertThatNullPointerException().isThrownBy(() -> TurnId.of(null));
    }

    @Test
    void rejectsMalformedValue() {
        assertThatIllegalArgumentException().isThrownBy(() -> TurnId.of("not-a-uuid"));
    }

    @Test
    void theSameFiringOfTheSameAlertIsTheSameTurn() {
        Instant startsAt = Instant.parse("2026-11-02T12:00:00Z");

        assertThat(TurnId.forAlert("acme", "fp-1", startsAt))
                .isEqualTo(TurnId.forAlert("acme", "fp-1", Instant.parse("2026-11-02T09:00:00-03:00")));
    }

    @Test
    void anotherFiringAlertOrTenantIsAnotherTurn() {
        Instant startsAt = Instant.parse("2026-11-02T12:00:00Z");
        TurnId turn = TurnId.forAlert("acme", "fp-1", startsAt);

        assertThat(TurnId.forAlert("acme", "fp-1", startsAt.plusSeconds(1))).isNotEqualTo(turn);
        assertThat(TurnId.forAlert("acme", "fp-2", startsAt)).isNotEqualTo(turn);
        assertThat(TurnId.forAlert("globex", "fp-1", startsAt)).isNotEqualTo(turn);
        assertThat(TurnId.forAlert("a|b", "c", startsAt))
                .as("the separator inside a name does not merge two tenants")
                .isNotEqualTo(TurnId.forAlert("a", "b|c", startsAt));
    }

    @Test
    void anAlertTurnNeedsEveryPart() {
        Instant startsAt = Instant.parse("2026-11-02T12:00:00Z");

        assertThatNullPointerException().isThrownBy(() -> TurnId.forAlert(null, "fp-1", startsAt));
        assertThatNullPointerException().isThrownBy(() -> TurnId.forAlert("acme", null, startsAt));
        assertThatNullPointerException().isThrownBy(() -> TurnId.forAlert("acme", "fp-1", null));
    }

    @Test
    void roundTripsThroughItsStringForm() {
        TurnId id = TurnId.of(RAW);

        assertThat(id.value()).isEqualTo(UUID.fromString(RAW));
        assertThat(id).hasToString(RAW);
        assertThat(TurnId.of(id.toString())).isEqualTo(id);
    }
}
