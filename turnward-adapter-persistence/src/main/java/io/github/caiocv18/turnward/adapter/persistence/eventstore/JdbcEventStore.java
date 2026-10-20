package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import io.github.caiocv18.turnward.application.eventstore.ConcurrencyConflictException;
import io.github.caiocv18.turnward.application.eventstore.RecordedEvent;
import io.github.caiocv18.turnward.application.port.EventStore;
import io.github.caiocv18.turnward.domain.TurnId;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * {@link EventStore} on one Postgres table (ADR-0002).
 *
 * <p>Two guards keep a stream gap-free. Inside the transaction the current version must equal the expected one,
 * which rejects a caller that folded a stale or impossible version. Two writers that pass that check at the same
 * time both insert the same next version, and {@code UNIQUE(stream_id, version)} lets only one of them commit.
 */
public final class JdbcEventStore implements EventStore {

    static final String STREAM_VERSION_CONSTRAINT = "events_stream_version_uk";

    private static final String INSERT =
            "insert into events (event_id, stream_id, version, tenant_id, type, payload, occurred_at) values ";
    private static final String ROW = "(?, ?, ?, ?, ?, ?::jsonb, ?)";

    private final JdbcClient jdbc;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final UuidV7 ids;
    private final TurnEventSerializer serializer = new TurnEventSerializer();

    public JdbcEventStore(JdbcClient jdbc, TransactionOperations transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = new UuidV7(clock, new SecureRandom());
    }

    @Override
    public void append(TurnId streamId, String tenantId, long expectedVersion, List<TurnEvent> events) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(tenantId, "tenantId");
        List<TurnEvent> batch = List.copyOf(Objects.requireNonNull(events, "events"));
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is blank");
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion is negative: " + expectedVersion);
        }
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("nothing to append");
        }
        try {
            transactions.executeWithoutResult(status -> {
                long current = currentVersion(streamId);
                if (current != expectedVersion) {
                    throw new ConcurrencyConflictException(streamId, expectedVersion, "stream is at " + current);
                }
                insert(streamId, tenantId, expectedVersion, batch);
            });
        } catch (DuplicateKeyException e) {
            if (!isStreamVersionClash(e)) {
                throw e;
            }
            throw new ConcurrencyConflictException(streamId, expectedVersion, "another writer appended first");
        }
    }

    @Override
    public List<RecordedEvent> load(TurnId streamId) {
        Objects.requireNonNull(streamId, "streamId");
        return jdbc.sql("""
                        select event_id, version, type, payload::text as payload, occurred_at
                        from events
                        where stream_id = ?
                        order by version""")
                .param(streamId.value())
                .query((rows, n) -> new RecordedEvent(
                        rows.getObject("event_id", UUID.class),
                        rows.getLong("version"),
                        rows.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        serializer.read(rows.getString("type"), rows.getString("payload"))))
                .list();
    }

    private long currentVersion(TurnId streamId) {
        return jdbc.sql("select coalesce(max(version), 0) from events where stream_id = ?")
                .param(streamId.value())
                .query(Long.class)
                .single();
    }

    // One multi-row insert: the batch reaches the database as a single statement.
    private void insert(TurnId streamId, String tenantId, long expectedVersion, List<TurnEvent> batch) {
        // Postgres keeps microseconds; truncating here makes what load() returns equal to what was stamped.
        OffsetDateTime occurredAt =
                OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        StringJoiner rows = new StringJoiner(", ", INSERT, "");
        List<Object> params = new ArrayList<>(batch.size() * 7);
        long version = expectedVersion;
        for (TurnEvent event : batch) {
            version++;
            rows.add(ROW);
            params.add(ids.next());
            params.add(streamId.value());
            params.add(version);
            params.add(tenantId);
            params.add(serializer.typeOf(event));
            params.add(serializer.payloadOf(event));
            params.add(occurredAt);
        }
        jdbc.sql(rows.toString()).params(params).update();
    }

    private static boolean isStreamVersionClash(DuplicateKeyException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains(STREAM_VERSION_CONSTRAINT);
    }
}
