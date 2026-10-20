package io.github.caiocv18.turnward.application.eventstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.caiocv18.turnward.domain.TurnId;
import io.github.caiocv18.turnward.domain.turn.TurnEvent.TurnCancelled;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordedEventTest {

    private static final UUID EVENT_ID = UUID.fromString("0192f0c1-7a3b-7c00-8000-000000000001");
    private static final Instant AT = Instant.parse("2026-10-20T12:00:00Z");

    @Test
    void keepsWhatTheStoreStamped() {
        RecordedEvent recorded = new RecordedEvent(EVENT_ID, 1, AT, new TurnCancelled("ops"));

        assertThat(recorded.version()).isEqualTo(1);
        assertThat(recorded.event()).isEqualTo(new TurnCancelled("ops"));
    }

    @Test
    void rejectsAVersionBeforeTheFirstEvent() {
        assertThatThrownBy(() -> new RecordedEvent(EVENT_ID, 0, AT, new TurnCancelled("ops")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingParts() {
        assertThatThrownBy(() -> new RecordedEvent(null, 1, AT, new TurnCancelled("ops")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecordedEvent(EVENT_ID, 1, null, new TurnCancelled("ops")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecordedEvent(EVENT_ID, 1, AT, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void conflictNamesTheStreamAndTheExpectedVersion() {
        TurnId stream = TurnId.of("0192f0c1-7a3b-7c00-8000-0000000000aa");

        ConcurrencyConflictException conflict = new ConcurrencyConflictException(stream, 3, "stream is at 5");

        assertThat(conflict.streamId()).isEqualTo(stream);
        assertThat(conflict.expectedVersion()).isEqualTo(3);
        assertThat(conflict).hasMessageContaining(stream.toString()).hasMessageContaining("version 3");
    }
}
