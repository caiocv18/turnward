# Architecture

*[Leia em português](README.pt-BR.md)*

The architecture of Turnward as code, kept current by the pull requests that change it.

| Document | What it shows |
|---|---|
| [overview.svg](overview.svg) | One-page drawing of the whole system, for the READMEs. Source: [overview.excalidraw](overview.excalidraw) |
| [context.md](context.md) | C4 level 1: Turnward, who uses it and the external systems around it |
| [containers.md](containers.md) | C4 level 2: the two roles of the application, the event store and the modules inside |
| [flows.md](flows.md) | Two runtime flows: starting a turn from an alert, and advancing it through the model and the tools |

The diagrams are Mermaid `flowchart` and `sequenceDiagram` blocks with a C4 legend. Mermaid's own
`C4Context` syntax is still experimental and renders poorly on GitHub, so it is not used.

## Built and planned

Each page shows what exists in `main` and what the portfolio program has dated, nothing else.

- Built parts are filled and their arrows solid.
- Planned parts have a white, dashed border and dashed arrows, each tagged with the item of the program
  that brings it.
- Modules with no dated item (Kafka, Redis, the MCP server) stay out until an item brings them.

The pull request that builds a planned part turns it solid in the same diff.

## Keeping it current

The `architecture` check ([architecture.yml](../../.github/workflows/architecture.yml)) fails a pull
request that changes a key path without touching `docs/arquitetura/` or `docs/adr/`. Key paths are the
ones that move a box or an arrow:

- the `pom.xml` of each `turnward-*` module, where the dependencies between modules live
- the ports in `turnward-application` (`application/port/`)
- the `application*.yml` of `turnward-app` and the Flyway migrations
- `ArchitectureTest`, the rules of the hexagon
- `deploy/`, `infra/**/*.tf` and `agents/`

The root `pom.xml` (versions and plugins) and the code inside the adapters are not key paths: filling an
adapter changes what a box does, not where it is.

When a key path changes but the architecture does not (a log level, a comment in a migration), add the
label `arquitetura-sem-mudanca` to the pull request: the check re-runs and passes. Otherwise update the
diagram the change touches, in both languages, and the SVG when a box or arrow of the overview moved.

## Editing the overview

1. Open `overview.excalidraw` at [excalidraw.com](https://excalidraw.com) (File, Open) or in the
   Excalidraw plugin of VS Code or Obsidian.
2. Edit, then export as SVG with "Embed scene" off and the background on, over `overview.svg`.
3. Save the scene back over `overview.excalidraw`. Both files go in the same pull request.
