package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.caiocv18.turnward.application.eventstore.ConcurrencyConflictException;
import io.github.caiocv18.turnward.application.eventstore.RecordedEvent;
import io.github.caiocv18.turnward.application.port.EventStore;
import io.github.caiocv18.turnward.domain.TurnId;
import io.github.caiocv18.turnward.domain.turn.Turn;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import io.github.caiocv18.turnward.domain.turn.TurnStatus;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = EventStoreTestApplication.class)
class JdbcEventStoreTest {

    @Autowired
    private EventStore store;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void flywayCreatedTheEventsTable() {
        assertThat(jdbc.sql("select version from flyway_schema_history where success").query(String.class).list())
                .containsExactly("1");
    }

    @Test
    void loadsWhatWasAppendedInVersionOrder() {
        TurnId stream = newStream();

        store.append(stream, SampleEvents.TENANT, 0, SampleEvents.shortTurn().subList(0, 2));
        store.append(stream, SampleEvents.TENANT, 2, SampleEvents.shortTurn().subList(2, 4));

        List<RecordedEvent> loaded = store.load(stream);
        assertThat(loaded).extracting(RecordedEvent::event).containsExactlyElementsOf(SampleEvents.shortTurn());
        assertThat(loaded).extracting(RecordedEvent::version).containsExactly(1L, 2L, 3L, 4L);
        assertThat(loaded).extracting(RecordedEvent::eventId).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
        assertThat(loaded).extracting(RecordedEvent::occurredAt)
                .containsOnly(EventStoreTestApplication.NOW.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void theFoldOfTheLoadedStreamMatchesTheFoldOfWhatWasAppended() {
        TurnId stream = newStream();
        store.append(stream, SampleEvents.TENANT, 0, SampleEvents.shortTurn());

        var folded = Turn.fold(store.load(stream).stream().map(RecordedEvent::event).toList());

        assertThat(folded).isEqualTo(Turn.fold(SampleEvents.shortTurn()));
        assertThat(folded.version()).isEqualTo(4);
        assertThat(folded.status()).isEqualTo(TurnStatus.COMPLETED);
    }

    @Test
    void storesEveryEventTypeUnderItsStableNameWithTheTenant() {
        TurnId stream = newStream();
        store.append(stream, "tenant-b", 0, SampleEvents.oneOfEach());

        assertThat(store.load(stream)).extracting(RecordedEvent::event)
                .containsExactlyElementsOf(SampleEvents.oneOfEach());
        assertThat(jdbc.sql("select type from events where stream_id = ? order by version")
                        .param(stream.value()).query(String.class).list())
                .containsExactlyElementsOf(SampleEvents.oneOfEach().stream().map(EventTypeRegistry::nameOf).toList());
        assertThat(jdbc.sql("select distinct tenant_id from events where stream_id = ?")
                        .param(stream.value()).query(String.class).list())
                .containsExactly("tenant-b");
    }

    @Test
    void storesFreeTextCarryingNul() {
        TurnId stream = newStream();

        store.append(stream, SampleEvents.TENANT, 0,
                List.of(new TurnEvent.ToolCallFailed("tool-1", "segfault\u0000 in stderr")));

        assertThat(store.load(stream)).extracting(RecordedEvent::event)
                .containsExactly(new TurnEvent.ToolCallFailed("tool-1", "segfault� in stderr"));
    }

    @Test
    void anUnknownStreamIsEmpty() {
        assertThat(store.load(newStream())).isEmpty();
    }

    @Test
    void aStaleExpectedVersionIsAConflictAndWritesNothing() {
        TurnId stream = newStream();
        store.append(stream, SampleEvents.TENANT, 0, SampleEvents.shortTurn().subList(0, 2));

        assertThatThrownBy(() -> store.append(stream, SampleEvents.TENANT, 1, SampleEvents.shortTurn().subList(2, 4)))
                .isInstanceOf(ConcurrencyConflictException.class)
                .hasMessageContaining("stream is at 2");
        assertThat(store.load(stream)).hasSize(2);
    }

    @Test
    void anExpectedVersionAheadOfTheStreamIsAConflictAndLeavesNoGap() {
        TurnId stream = newStream();
        store.append(stream, SampleEvents.TENANT, 0, SampleEvents.shortTurn().subList(0, 2));

        assertThatThrownBy(() -> store.append(stream, SampleEvents.TENANT, 5, SampleEvents.shortTurn().subList(2, 4)))
                .isInstanceOf(ConcurrencyConflictException.class)
                .hasMessageContaining("stream is at 2");
        assertThat(store.load(stream)).extracting(RecordedEvent::version).containsExactly(1L, 2L);
    }

    @Test
    void aNewStreamMustBeAppendedFromVersionZero() {
        TurnId stream = newStream();

        assertThatThrownBy(() -> store.append(stream, SampleEvents.TENANT, 1, SampleEvents.shortTurn()))
                .isInstanceOf(ConcurrencyConflictException.class);
        assertThat(store.load(stream)).isEmpty();
    }

    @Test
    void rejectsMeaninglessAppends() {
        TurnId stream = newStream();
        List<TurnEvent> events = SampleEvents.shortTurn();

        assertThatThrownBy(() -> store.append(stream, SampleEvents.TENANT, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.append(stream, " ", 0, events)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.append(stream, SampleEvents.TENANT, -1, events))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.load(stream)).isEmpty();
    }

    private static TurnId newStream() {
        return new TurnId(UUID.randomUUID());
    }
}
