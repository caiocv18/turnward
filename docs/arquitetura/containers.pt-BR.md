# Containers (C4 nível 2)

*[Read in English](containers.md)*

```mermaid
flowchart TB
    operator([Operador de plantão])
    monitoring[Grafana / Alertmanager]
    vortexalerts[Vortex<br/>alertas de estoque]
    subgraph turnward [Turnward]
        api[papel api<br/>Spring Boot 4.1, Java 25<br/>turnward-app, perfil api]
        worker[papel worker<br/>Spring Boot 4.1, Java 25<br/>turnward-app, perfil worker]
        events[(Event store<br/>PostgreSQL 18 + pgvector 0.8.2<br/>tabela events, Flyway V1)]
        runbooks[(Trechos de runbook<br/>pgvector)]
    end
    ollama[Ollama<br/>dev e teste]
    anthropic[Anthropic Messages API<br/>produção]
    vortexmcp[Vortex<br/>servidor MCP]

    monitoring -.->|POST no webhook<br/>item 20| api
    vortexalerts -.->|alertas<br/>itens 21 e 27| api
    operator -.->|aprova ou nega uma tool<br/>item 37| api
    api -->|StartTurn: append de TurnStarted,<br/>versão esperada 0| events
    api -.->|ApprovalGranted ou ApprovalDenied<br/>item 37| events
    worker -->|AdvanceTurn: load, fold, decide,<br/>append antes e depois de cada efeito| events
    worker -.->|runbook top-1 no prompt<br/>item 33| runbooks
    worker -.->|LlmPort<br/>item 32| ollama
    worker -.->|LlmPort, Resilience4j<br/>item 42| anthropic
    worker -.->|ToolPort por MCP<br/>itens 26, 30 e 34| vortexmcp

    classDef person fill:#08427b,color:#fff,stroke:#052e56
    classDef container fill:#438dd5,color:#fff,stroke:#2e6295
    classDef store fill:#438dd5,color:#fff,stroke:#2e6295
    classDef planned fill:#fff,color:#333,stroke:#2e6295,stroke-dasharray:6 4
    classDef external fill:#fff,color:#333,stroke:#6b6b6b,stroke-dasharray:6 4
    class operator person
    class api,worker container
    class events store
    class runbooks planned
    class monitoring,vortexalerts,vortexmcp,ollama,anthropic external
```

| Legenda | |
|---|---|
| Azul | Construído: código no `main`, testado |
| Branco, borda tracejada | Container ou sistema externo planejado |
| Seta contínua | O código dessa chamada existe e tem teste |
| Seta tracejada | Planejada, com o item do programa de portfólio que a traz |
| Cilindro | Banco de dados |

Os dois papéis são uma aplicação Spring Boot só, `turnward-app`, iniciada com o perfil `api` ou `worker`.
Nenhum bean liga os casos de uso ainda: as setas contínuas são `StartTurn`, `AdvanceTurn` e
`JdbcEventStore`, testados contra Postgres no Testcontainers e contra fakes em memória de `LlmPort` e
`ToolPort`. O poller do worker que roda o `AdvanceTurn` chega com o item 32, o servidor web do papel `api`
com o item 20. A pilha inteira vai para a instância da demo do Vortex na AWS com o item 45.

| Container | Caminho | Notas |
|---|---|---|
| papel api | `turnward-app` (`application-api.yml`) | Endpoints de webhook, aprovação e consulta chegam com o `turnward-adapter-web` |
| papel worker | `turnward-app` (`application-worker.yml`) | Nunca serve HTTP (`web-application-type: none`) |
| Event store | `turnward-adapter-persistence` | Um stream por turno; `UNIQUE(stream_id, version)` decide entre escritores concorrentes ([ADR-0002](../adr/0002-event-sourcing-in-postgres.md)) |
| Trechos de runbook | `turnward-adapter-persistence` | Planejado, item 33: os runbooks do Vortex com embedding no pgvector |

## Dentro da aplicação

Hexagonal, um módulo Maven por parte ([ADR-0001](../adr/0001-hexagonal-maven-multimodule-archunit.md)).
O `ArchitectureTest` quebra o build quando o domínio ou a aplicação dependem de algo além do JDK e deles
mesmos, ou quando um adapter depende de outro.

| Módulo | Estado | O que guarda |
|---|---|---|
| `turnward-domain` | Construído | `Turn` (fold estrito), `TurnEvent`, `TurnDecider` (puro, no máximo 8 chamadas ao modelo), `ToolPolicy` (`DANGEROUS` exige aprovação, só `READ_ONLY` é repetida depois de um crash) |
| `turnward-application` | Construído | `StartTurn`, `AdvanceTurn`, `ToolCatalog`; portas `EventStore`, `LlmPort`, `ToolPort` |
| `turnward-adapter-persistence` | Construído | `JdbcEventStore`, `TurnEventSerializer`, `UuidV7`, Flyway `V1__create_events.sql` |
| `turnward-app` | Construído, sem ligação | Bootstrap com os perfis `api` e `worker`; `ArchitectureTest` |
| `turnward-adapter-web` | Planejado, itens 20 e 37 | Webhook de alerta, API de aprovação |
| `turnward-adapter-llm` | Planejado, itens 32 e 42 | Spring AI: Ollama, depois a API da Anthropic com Resilience4j |
| `turnward-adapter-mcp-client` | Planejado, item 34 | `ToolPort` sobre as tools MCP do Vortex, catálogo fail-closed |
| `turnward-adapter-kafka`, `-redis`, `-mcp-server` | Cascas, sem item datado | Só um `package-info`; fora dos diagramas até um item trazê-los |
