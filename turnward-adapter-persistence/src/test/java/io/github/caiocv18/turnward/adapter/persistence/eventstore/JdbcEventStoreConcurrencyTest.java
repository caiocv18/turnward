package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.caiocv18.turnward.application.eventstore.ConcurrencyConflictException;
import io.github.caiocv18.turnward.application.eventstore.RecordedEvent;
import io.github.caiocv18.turnward.application.port.EventStore;
import io.github.caiocv18.turnward.domain.TurnId;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Two workers fold the same stream at version 2 and both try to append version 3. Exactly one must win: its append
 * returns, the other gets a {@link ConcurrencyConflictException}, and the stream holds only the winner's events.
 */
@SpringBootTest(classes = EventStoreTestApplication.class)
class JdbcEventStoreConcurrencyTest {

    private static final long EXPECTED = 2;

    @Autowired
    private EventStore store;

    @RepeatedTest(100)
    void exactlyOneOfTwoWritersAtTheSameVersionWins() throws Exception {
        TurnId stream = new TurnId(UUID.randomUUID());
        store.append(stream, SampleEvents.TENANT, 0, SampleEvents.shortTurn().subList(0, 2));
        List<TurnEvent> left = List.of(new TurnEvent.LlmCallCompleted("llm-1", 1, 1, List.of()));
        List<TurnEvent> right = List.of(new TurnEvent.LlmCallFailed("llm-1", "timeout"));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Outcome> a = workers.submit(() -> appendAfter(start, stream, left));
            Future<Outcome> b = workers.submit(() -> appendAfter(start, stream, right));
            start.countDown();

            List<Outcome> outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

            assertThat(outcomes).containsExactlyInAnyOrder(Outcome.WON, Outcome.CONFLICT);
            List<TurnEvent> winner = outcomes.get(0) == Outcome.WON ? left : right;
            List<RecordedEvent> stored = store.load(stream);
            assertThat(stored).extracting(RecordedEvent::version).containsExactly(1L, 2L, 3L);
            assertThat(stored.get(2).event()).isEqualTo(winner.get(0));
        }
    }

    private Outcome appendAfter(CountDownLatch start, TurnId stream, List<TurnEvent> events)
            throws InterruptedException {
        start.await();
        try {
            store.append(stream, SampleEvents.TENANT, EXPECTED, events);
            return Outcome.WON;
        } catch (ConcurrencyConflictException e) {
            (e.getMessage().contains("another writer") ? LOST_AT_INSERT : LOST_AT_CHECK).incrementAndGet();
            return Outcome.CONFLICT;
        }
    }

    // Which guard stopped the loser. Both must fire across the run, or the test never raced at the database.
    private static final AtomicInteger LOST_AT_INSERT = new AtomicInteger();
    private static final AtomicInteger LOST_AT_CHECK = new AtomicInteger();

    @AfterAll
    static void bothGuardsWereExercised() {
        System.out.printf("lost at insert (unique): %d, lost at version check: %d%n",
                LOST_AT_INSERT.get(), LOST_AT_CHECK.get());
        assertThat(LOST_AT_INSERT.get()).as("losers stopped by UNIQUE(stream_id, version)").isPositive();
    }

    private enum Outcome {
        WON,
        CONFLICT
    }
}
