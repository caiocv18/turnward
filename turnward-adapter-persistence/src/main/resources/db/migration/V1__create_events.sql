-- Event store (ADR-0002). One stream per turn; the n-th event of a stream has version n.
create table events (
    event_id    uuid        primary key,
    stream_id   uuid        not null,
    version     bigint      not null check (version > 0),
    tenant_id   text        not null,
    type        text        not null,
    payload     jsonb       not null,
    occurred_at timestamptz not null,
    -- Optimistic concurrency: two writers that append the same version cannot both commit.
    constraint events_stream_version_uk unique (stream_id, version)
);

create index events_tenant_id_idx on events (tenant_id);
