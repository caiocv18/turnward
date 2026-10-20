package io.github.caiocv18.turnward.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
    void roundTripsThroughItsStringForm() {
        TurnId id = TurnId.of(RAW);

        assertThat(id.value()).isEqualTo(UUID.fromString(RAW));
        assertThat(id).hasToString(RAW);
        assertThat(TurnId.of(id.toString())).isEqualTo(id);
    }
}
