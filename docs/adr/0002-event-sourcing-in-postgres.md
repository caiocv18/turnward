---
status: accepted
date: 2026-10-20
---

# ADR-0002: Event sourcing in Postgres (no EventStoreDB, no Axon)

## Context and problem statement

A turn is a long-running process that survives worker crashes, waits hours for a human approval and must never run a side-effecting tool twice. Its state is rebuilt by a pure fold over its events (`Turn.fold` in `turnward-domain`), and the events are also the audit trail and the source of the outbox.

The question is where the events live and how two workers that hold the same stream are kept from both writing the next event.

## Decision drivers

- The append must be atomic with other writes of the same transaction (the outbox, ADR-0003; the worker lease, ADR-0006).
- Two writers at the same stream version must not both succeed, without a distributed lock.
- One database to run, back up and pay for: the project already needs Postgres for pgvector, and the AWS budget is US$ 20 in total.
- The domain stays plain Java: no framework annotations on events or aggregates.
- Integration tests run against the same engine and pgvector version as production (RDS, pgvector 0.8.2).

## Considered options

1. A plain `events` table in Postgres, written with `JdbcClient`.
2. EventStoreDB (Kurrent) as a dedicated event database.
3. Axon Framework with Axon Server or its JPA event store.

## Decision outcome

Chosen option: **option 1**, a plain `events` table in Postgres.

```sql
create table events (
    event_id    uuid        primary key,
    stream_id   uuid        not null,
    version     bigint      not null check (version > 0),
    tenant_id   text        not null,
    type        text        not null,
    payload     jsonb       not null,
    occurred_at timestamptz not null,
    constraint events_stream_version_uk unique (stream_id, version)
);
```

- **One stream per turn.** `stream_id` is the `TurnId`; versions start at 1 with no gaps, so a stream's version equals the `TurnState.version` of its fold. The column is `bigint` to match the domain's `long`.
- **Optimistic concurrency, two guards.** Inside the append transaction the current `max(version)` must equal the caller's expected version; otherwise the append fails without writing. This rejects a stale caller and a caller ahead of the stream, which `UNIQUE` alone would accept and leave a gap. Two writers that pass the check at the same time insert the same next version, and `UNIQUE(stream_id, version)` lets only one commit. Both cases surface as `ConcurrencyConflictException`; the loser reloads, folds and decides again.
- **Stable type names.** `type` holds a name from an explicit map in the adapter (`EventTypeRegistry`), not a Java class name, so renaming a record does not orphan stored events. A test fails when a new `TurnEvent` subtype has no name.
- **Payload.** The record components as JSON, written by a Jackson 3 mapper owned by the adapter and strict on read (unknown fields fail). `jsonb` refuses U+0000, and free text from tools and models can carry it, so the mapper writes every NUL as U+FFFD: the event is stored with that one character changed instead of the turn being unable to record it.
- **`tenant_id` on every row**, passed by the caller: only `TurnStarted` carries the tenant, and the caller already holds it in the folded state.
- **`event_id` is a UUIDv7** (RFC 9562) generated in the adapter in a few lines: time-ordered keys index well, and neither the JDK nor the Boot BOM offers one.
- **`occurred_at`** comes from an injected `Clock`, truncated to the microseconds Postgres keeps, one instant per append.

### Consequences

- Good: the append, the outbox row and the lease update can share one local transaction.
- Good: no new infrastructure; Testcontainers runs the same `pgvector/pgvector:0.8.2-pg18` image as RDS.
- Good: the conflict rule is plain SQL that an interviewer can read in one screen.
- Bad: no built-in subscriptions or projections; the outbox (ADR-0003) and any read model are our code.
- Bad: one table grows forever; partitioning or archiving is future work, out of scope for the MVP.
- Neutral: snapshots are not needed while a turn has tens of events.

### Confirmation

- `JdbcEventStoreConcurrencyTest` runs 100 repetitions of two threads appending at the same expected version; each repetition must have exactly one winner, one `ConcurrencyConflictException` and only the winner's event in the stream, and across the run the `UNIQUE` constraint must have stopped at least one loser.
- `JdbcEventStoreTest` checks append and load, stale and ahead-of-stream versions, stable names, the tenant column and that the fold of the loaded stream equals the fold of what was appended.
- `EventTypeRegistryTest` compares the map with `TurnEvent.class.getPermittedSubclasses()`.

## Pros and cons of the options

### Option 2: EventStoreDB (Kurrent)

- Good: streams, expected-version appends and subscriptions are native.
- Bad: a second database to operate, and no shared transaction with the outbox, the lease or pgvector.
- Bad: one more container on Fargate against a US$ 20 budget.

### Option 3: Axon Framework

- Good: aggregates, event store, sagas and snapshots out of the box.
- Bad: annotations and framework types in the domain, which ADR-0001 forbids.
- Bad: hides the mechanics this project exists to show.
