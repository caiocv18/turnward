---
status: accepted
date: 2026-10-19
---

# ADR-0001: Hexagonal architecture in a Maven multi-module build, enforced by ArchUnit

## Context and problem statement

turnward has a small, valuable core (the `Turn` aggregate, its events and the pure fold that rebuilds state) surrounded by many volatile integrations: Postgres, Kafka, Redis, Keycloak, two LLM providers through Spring AI, MCP in both directions and a Python agent. The integrations will change during the project (Ollama to Bedrock, polling relay to CDC, local Kafka to MSK), and the core must stay testable without any of them.

The question is how to structure the code so that the dependency direction is guaranteed by the build, not by discipline.

## Decision drivers

- The domain must be plain Java, testable with property-based tests and without a Spring context.
- Swapping an adapter (for example, Ollama for Bedrock) must not touch the domain or the use cases.
- Adapters must not depend on each other, so each one can be replaced or tested alone.
- A violation must fail the build, visibly, in CI.
- The structure must be easy to read for someone browsing the repository.

## Considered options

1. Hexagonal architecture in a Maven multi-module build, with ArchUnit rules in CI.
2. Layered monolith in a single module (controller, service, repository packages).
3. Hexagonal architecture in a Gradle multi-project build.

## Decision outcome

Chosen option: **option 1**, hexagonal architecture in a Maven multi-module build, with ArchUnit rules in CI.

Modules:

- `turnward-domain`: aggregate, events, state machine and tool policies. Depends on nothing but the JDK.
- `turnward-application`: use cases and the ports (`EventStore`, `LlmPort`, `ToolPort`, `RunbookSearch`, `BudgetPort`, `Outbox`). Depends only on the domain.
- `turnward-adapter-*`: one module per technology, each implementing ports. Depends on the application and the domain, never on another adapter.
- `turnward-app`: the only module that knows every adapter; it wires them together and runs the `api` and `worker` roles by profile.

Maven module boundaries make illegal imports impossible at compile time across modules. ArchUnit covers what modules cannot: package rules inside a module, an allow list that keeps the domain and the application on the JDK alone, and, as ports and adapters land, their naming conventions.

### Consequences

- Good: the domain and the use cases are tested in milliseconds, without containers.
- Good: replacing a provider is a new adapter module plus a change in `turnward-app`.
- Good: the architecture is visible in the directory tree and checked on every pull request.
- Bad: more modules and more `pom.xml` files to maintain than a single-module project.
- Bad: mapping between domain types and adapter types (JPA or JDBC rows, Kafka records, DTOs) is explicit code.
- Neutral: Maven was preferred over Gradle for its fixed lifecycle and for being the default in the Spring and enterprise Java ecosystem that this project targets.
- Neutral: the build accepts only JDK 25 (`[25,26)` in the enforcer), the LTS pinned in `.sdkmanrc` and in CI. A newer JDK is refused until CI tests it.

### Confirmation

An `ArchitectureTest` suite runs in `mvn verify` and in CI. The build fails if the domain imports Spring, if an adapter imports another adapter, or if the application depends on an adapter.

## Pros and cons of the options

### Option 2: layered monolith in a single module

- Good: least ceremony, fastest to start.
- Bad: nothing stops a controller from calling a repository or the domain from importing JPA; the rule lives only in reviews.
- Bad: a provider change tends to leak into business code.

### Option 3: hexagonal in a Gradle multi-project build

- Good: same boundaries as option 1, faster incremental builds.
- Bad: build logic in a Kotlin or Groovy DSL adds a second language to review.
- Neutral: Gradle is a valid choice; Maven was chosen for convention and audience, not for a technical limit.
