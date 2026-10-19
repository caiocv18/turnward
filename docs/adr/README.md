# Architecture decision records

Decisions are written in the [MADR](https://adr.github.io/madr/) format. An ADR listed as **Proposed** is planned and not written yet; **Accepted** means the decision is in force.

| # | Decision | Status |
|---|---|---|
| [0001](0001-hexagonal-maven-multimodule-archunit.md) | Hexagonal architecture in a Maven multi-module build, enforced by ArchUnit | Accepted |
| 0002 | Event sourcing in Postgres (no EventStoreDB, no Axon) | Proposed |
| 0003 | Transactional outbox with a polling relay (no Debezium) | Proposed |
| 0004 | At-least-once delivery plus deduplication (no Kafka transactions) | Proposed |
| 0005 | Tool side effects: idempotency key, `ToolOutcomeUnknown` and human reconciliation | Proposed |
| 0006 | Worker lease in Postgres (`FOR UPDATE SKIP LOCKED`) | Proposed |
| 0007 | Risk-based human approval and per-tool, per-tenant authorization from Keycloak claims | Proposed |
| 0008 | Resilience4j operator order in Reactor (`onErrorResume` after `transformDeferred`) | Proposed |
| 0009 | Budget: Postgres ledger is the source of truth, Redis enforces it fail-closed | Proposed |
| 0010 | Kafka on AWS: KRaft container on Fargate, MSK Serverless optional | Proposed |
| 0011 | Ollama in dev and test, Amazon Bedrock in production, both behind Spring AI | Proposed |
| 0012 | MCP over Streamable HTTP with authentication for remote servers | Proposed |
| 0013 | Ephemeral infrastructure with a budget alarm and scheduled destroy | Proposed |
| 0014 | OpenTelemetry GenAI conventions (experimental) isolated in one adapter | Proposed |
| 0015 | Confidentiality: synthetic data only, forbidden-name checks kept outside the repository | Proposed |
