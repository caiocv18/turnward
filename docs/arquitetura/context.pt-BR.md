# Contexto (C4 nível 1)

*[Read in English](context.md)*

```mermaid
flowchart TB
    operator([Operador de plantão<br/>aprova ou nega uma tool perigosa])
    turnward[Turnward<br/>control plane durável para agentes de IA:<br/>turnos como eventos, risco por tool,<br/>aprovação humana]
    monitoring[Grafana / Alertmanager<br/>webhooks de alerta]
    vortex[Vortex<br/>o sistema de estoque governado:<br/>alertas e tools MCP]
    ollama[Ollama<br/>modelo local, dev e teste]
    anthropic[Anthropic Messages API<br/>modelo em produção]
    github[GitHub<br/>repositório, Actions]
    aws[AWS<br/>instância da demo ao lado do Vortex,<br/>SSM SecureString para a chave]

    monitoring -.->|alerta abre um turno<br/>item 20| turnward
    vortex -.->|estoque baixo ou anomalia<br/>abre um turno, itens 21 e 27| turnward
    turnward -.->|chama as tools dele por MCP<br/>itens 26, 30 e 34| vortex
    turnward -.->|chamadas ao modelo<br/>item 32| ollama
    turnward -.->|chamadas ao modelo, com Resilience4j<br/>item 42| anthropic
    operator -.->|aprova ou nega<br/>item 37| turnward
    github -->|guarda o código,<br/>roda o check de arquitetura| turnward
    aws -.->|hospeda a demo<br/>item 45| turnward

    classDef person fill:#08427b,color:#fff,stroke:#052e56
    classDef system fill:#1168bd,color:#fff,stroke:#0b4884
    classDef external fill:#999,color:#fff,stroke:#6b6b6b
    classDef planned fill:#fff,color:#333,stroke:#6b6b6b,stroke-dasharray:6 4
    class operator person
    class turnward system
    class github external
    class monitoring,vortex,ollama,anthropic,aws planned
```

| Legenda | |
|---|---|
| Azul-escuro, arredondado | Pessoa |
| Azul | O sistema descrito aqui |
| Cinza | Sistema externo em uso hoje |
| Branco, borda tracejada | Sistema externo planejado; a seta diz o item do programa de portfólio |
| Seta contínua | Existe hoje |
| Seta tracejada | Planejada, com o item que a traz |

Hoje nenhum sistema externo conversa com o Turnward em execução: o código é o domínio, os casos de uso e o
event store, testados com Testcontainers e fakes em memória. A API da Anthropic só responde ao
`scripts/smoke-anthropic-api.sh`, que confere a chave de produção fora da aplicação.

- Ollama em dev e teste, a API da Anthropic em produção, as duas atrás do Spring AI
  ([ADR-0011](../adr/0011-ollama-dev-anthropic-api-prod.md)).
- O Vortex é outro repositório ([caiocv18/vortex](https://github.com/caiocv18/vortex)); o Turnward chega
  nele só pelos alertas e pelas tools MCP.
- Os itens são os blocos datados do programa de portfólio. Um elemento planejado sai desta página, ou fica
  contínuo, no pull request que o constrói.
