# Context (C4 level 1)

*[Leia em português](context.pt-BR.md)*

```mermaid
flowchart TB
    operator([On-call operator<br/>approves or denies a dangerous tool])
    turnward[Turnward<br/>durable control plane for AI agents:<br/>turns as events, tool risk,<br/>human approval]
    monitoring[Grafana / Alertmanager<br/>alert webhooks]
    vortex[Vortex<br/>the governed inventory system:<br/>alerts and MCP tools]
    ollama[Ollama<br/>local model, dev and test]
    anthropic[Anthropic Messages API<br/>model in production]
    github[GitHub<br/>repository, Actions]
    aws[AWS<br/>demo instance next to Vortex,<br/>SSM SecureString for the key]

    monitoring -.->|alert opens a turn<br/>item 20| turnward
    vortex -.->|low stock or anomaly<br/>opens a turn, items 21 and 27| turnward
    turnward -.->|calls its tools over MCP<br/>items 26, 30 and 34| vortex
    turnward -.->|model calls<br/>item 32| ollama
    turnward -.->|model calls, with Resilience4j<br/>item 42| anthropic
    operator -.->|approves or denies<br/>item 37| turnward
    github -->|hosts the code,<br/>runs the architecture check| turnward
    aws -.->|hosts the demo<br/>item 45| turnward

    classDef person fill:#08427b,color:#fff,stroke:#052e56
    classDef system fill:#1168bd,color:#fff,stroke:#0b4884
    classDef external fill:#999,color:#fff,stroke:#6b6b6b
    classDef planned fill:#fff,color:#333,stroke:#6b6b6b,stroke-dasharray:6 4
    class operator person
    class turnward system
    class github external
    class monitoring,vortex,ollama,anthropic,aws planned
```

| Legend | |
|---|---|
| Dark blue, rounded | Person |
| Blue | The system described here |
| Grey | External system in use today |
| White, dashed border | External system planned; the arrow names its item in the portfolio program |
| Solid arrow | Exists today |
| Dashed arrow | Planned, with the item that brings it |

Today no external system talks to the running Turnward: the code is the domain, the use cases and the
event store, tested with Testcontainers and in-memory fakes. The Anthropic API answers only
`scripts/smoke-anthropic-api.sh`, which checks the production key outside the application.

- Ollama in dev and test, the Anthropic API in production, both behind Spring AI
  ([ADR-0011](../adr/0011-ollama-dev-anthropic-api-prod.md)).
- Vortex is a separate repository ([caiocv18/vortex](https://github.com/caiocv18/vortex)); Turnward reaches
  it only through its alerts and its MCP tools.
- The items are the dated blocks of the portfolio program. A planned element leaves this page, or turns
  solid, in the pull request that builds it.
