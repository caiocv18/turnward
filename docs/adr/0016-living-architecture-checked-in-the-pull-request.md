---
status: accepted
date: 2026-11-20
---

# ADR-0016: Living architecture, checked in the pull request that changes it

## Context and problem statement

The only architecture picture of turnward was a Mermaid `flowchart` in both READMEs, drawn on 2026-10-19
from the design, before any code. By 2026-11-20 it showed Kafka, Redis, Keycloak, an outbox and a Python
agent that do not exist, and it did not show what does: the event store, the two roles of one application
and the hexagon checked by ArchUnit. Nothing tied a change of the modules, the ports or the migrations to
that picture.

The question is how to keep the architecture docs true to `main`, and how to show the parts the portfolio
program has planned without passing them off as built.

## Decision drivers

- A reader of the repository must tell what is built from what is planned at a glance.
- A pull request that moves a box or an arrow must carry its diagram change, reviewed in the same diff.
- The check must not fire on every feature pull request, or its escape hatch becomes a habit.
- A required check whose workflow never starts stays pending forever, so it cannot use `paths` in `on:`.
- The repository has no other workflow yet; the check must run without a build and without secrets.
- Vortex, the system turnward governs, already keeps its architecture this way (its ADR 0011); one shape
  for both repositories of the portfolio.

## Considered options

1. Architecture as code in `docs/arquitetura/`, with a required `architecture` check on key paths and a
   label as escape hatch.
2. A checkbox in a pull request template.
3. Diagrams generated from the code (Structurizr DSL, a Maven dependency graph).

## Decision outcome

Chosen option: **option 1**.

- **`docs/arquitetura/`**, in English with a `.pt-BR.md` mirror for every file: C4 level 1 (`context.md`)
  and level 2 (`containers.md`, with the modules inside the application) as Mermaid `flowchart` with a C4
  legend, and two runtime flows (`flows.md`) as `sequenceDiagram`: starting a turn from an alert, and
  advancing it through the model and the tools.
- **Built and planned:** built parts are filled with solid arrows; planned parts have a white, dashed
  border and dashed arrows tagged with the item of the portfolio program that brings them. Only dated items
  are drawn: Kafka, Redis and the MCP server stay out until an item brings them. The pull request that
  builds a part turns it solid.
- **One overview drawing:** `overview.excalidraw` is the source and `overview.svg` its export, both in the
  repository, in the same pull request, with the Excalidraw "Normal" font (Helvetica) because an SVG in an
  `<img>` cannot load web fonts. Both READMEs show the SVG in place of the old flowchart.
- **The `architecture` check** (`.github/workflows/architecture.yml`), required in the `main-protection`
  ruleset: a `changes` job runs `dorny/paths-filter` with two filters, `key` and `docs`, and the
  `architecture` job fails when a key path changed and neither `docs/arquitetura/**` nor `docs/adr/**`
  did. It runs on every pull request and on `labeled` and `unlabeled`, always reports, reads the changed
  files from the API with no checkout and fails closed when the filter breaks.
- **Key paths** are the ones that move a box or an arrow in a hexagonal multi-module build: the `pom.xml`
  of each `turnward-*` module (the dependencies between modules), the ports in `application/port/`, the
  `application*.yml` of `turnward-app`, the Flyway migrations, `ArchitectureTest`, `deploy/**`,
  `infra/**/*.tf` and `agents/**`. The root `pom.xml` and the code inside the adapters are not.
- **Escape hatch:** the label `arquitetura-sem-mudanca` passes the check when a key path changed and the
  architecture did not. The label is visible in the pull request history.

### Consequences

- Good: the diagrams follow `main`, and a planned part says which item brings it.
- Good: the check costs seconds per pull request and needs only `pull-requests: read`.
- Good: the same structure as Vortex, so a reader of one repository reads the other.
- Bad: the `.pt-BR.md` mirrors double the writing of each diagram change, and the check does not compare
  them.
- Bad: the overview SVG is exported by hand from Excalidraw; a wrong export is caught only by review.
- Bad: when the program is replanned, the item numbers on the planned parts go stale without any key path
  changing. The checkpoints of the program are where they are reviewed.
- Neutral: a module `pom.xml` also changes on a version bump. With no Dependabot in the repository today
  that is rare, and the label covers it.
- Neutral: `architecture` becomes a required check by a `PUT` of the whole ruleset after this record's pull
  request is merged, so the check never blocks the pull request that creates it. The build checks of the CI
  join the same rule later.

### Confirmation

The `architecture` check runs on every pull request. A pull request that changes a key path without
`docs/arquitetura/` or `docs/adr/` fails, and passes once the label is added. A throwaway pull request
proves both paths once, after the check becomes required.

## Pros and cons of the options

### Option 2: a checkbox in a pull request template

- Good: costs nothing.
- Bad: blocks nothing and is ticked by habit.

### Option 3: diagrams generated from the code

- Good: precise for modules and their dependencies, never stale.
- Bad: cannot draw intent (what is planned, the idempotent start, the effect between two appends).
- Bad: adds a toolchain to a repository that has no CI build yet.
