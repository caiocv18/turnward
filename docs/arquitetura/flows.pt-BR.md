# Fluxos em execução

*[Read in English](flows.md)*

Os passos dentro de uma caixa cinza são planejados; a nota diz o item do programa de portfólio que os
traz. O resto é código no `main`.

## Início de um turno

```mermaid
sequenceDiagram
    autonumber
    participant M as Grafana / Alertmanager
    participant W as papel api (webhook)
    participant S as StartTurn
    participant E as JdbcEventStore
    participant P as Postgres (events)

    rect rgb(235, 235, 235)
        note over M,W: planejado, item 20
        M->>W: POST no webhook de alerta
        W->>S: start(TurnStarted)
    end
    S->>S: TurnId.forAlert(tenant, fingerprint, startsAt)
    S->>E: append(turnId, tenant, versão esperada 0, [TurnStarted])
    E->>P: numa transação: versão atual do stream
    alt o stream está vazio
        E->>P: insert da versão 1, id do evento UUIDv7
        P-->>E: commit
        S-->>W: Result(turnId, created = true)
    else reentrega, ou outro escritor ganhou a corrida
        P-->>E: a versão não é 0, ou events_stream_version_uk violada
        E-->>S: ConcurrencyConflictException
        S-->>W: Result(turnId, created = false), mesmo turno
    end
```

O id do turno vem do alerta, então um alerta reentregue encontra o stream ocupado e devolve o mesmo turno:
idempotente sem tabela própria.

## Avanço de um turno

```mermaid
sequenceDiagram
    autonumber
    participant K as poller do worker
    participant A as AdvanceTurn
    participant E as EventStore
    participant D as Domínio (Turn, TurnDecider)
    participant L as LlmPort
    participant T as ToolPort

    rect rgb(235, 235, 235)
        note over K: planejado, item 32
        K->>A: run(turnId)
    end
    loop até Halt, ou até uma chamada ao modelo falhar
        A->>E: load(turnId)
        A->>D: Turn.fold(events)
        A->>D: TurnDecider.decide(state)
        D-->>A: próximo comando
        alt CallLlm
            A->>E: LlmCallStarted
            A->>L: system prompt, histórico, catálogo de tools
            note over L: fakes hoje, Ollama item 32, API da Anthropic item 42
            L-->>A: texto, chamadas de tool, tokens
            A->>E: LlmCallCompleted, ou LlmCallFailed e a rodada acaba
        else ProposeTool
            A->>E: ToolCallProposed, risco vindo do ToolCatalog
        else ExecuteTool (READ_ONLY, SIDE_EFFECT ou aprovada)
            A->>E: ToolCallStarted, chave de idempotência turnId:toolCallId
            A->>T: execute
            note over T: fakes hoje, tools MCP do Vortex item 34
            T-->>A: resultado ou erro
            A->>E: ToolCallCompleted, ToolCallFailed ou ToolOutcomeUnknown
        else AwaitApproval (DANGEROUS)
            A->>E: ApprovalRequested
        else Complete ou Fail
            A->>E: TurnCompleted, ou TurnFailed depois de 8 chamadas ao modelo
        else Halt
            A-->>K: estado: encerrado, ou à espera de um humano
        end
    end
```

- Todo efeito vem depois do seu evento `*Started`, e nenhuma transação abrange um efeito. Um crash deixa um
  stream que a próxima rodada sabe ler: uma tool `READ_ONLY` que começou roda de novo, qualquer outra vira
  `ToolOutcomeUnknown` e espera um humano.
- Uma corrida de append perdida antes de um efeito faz a rodada decidir de novo a partir do estado novo.
  Depois de um efeito, a rodada recarrega e grava o mesmo resultado, então o efeito nunca se repete.
- Um turno parado em `AWAITING_APPROVAL` só volta a andar quando `ApprovalGranted`, `ApprovalDenied` ou
  `ApprovalExpired` é gravado. Hoje só os testes gravam esses eventos; a API de aprovação é o item 37.
