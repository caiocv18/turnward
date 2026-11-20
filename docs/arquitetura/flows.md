# Runtime flows

*[Leia em português](flows.pt-BR.md)*

Steps inside a grey box are planned; the note names the item of the portfolio program that brings them.
Everything else is code in `main`.

## Starting a turn

```mermaid
sequenceDiagram
    autonumber
    participant M as Grafana / Alertmanager
    participant W as api role (webhook)
    participant S as StartTurn
    participant E as JdbcEventStore
    participant P as Postgres (events)

    rect rgb(235, 235, 235)
        note over M,W: planned, item 20
        M->>W: POST alert webhook
        W->>S: start(TurnStarted)
    end
    S->>S: TurnId.forAlert(tenant, fingerprint, startsAt)
    S->>E: append(turnId, tenant, expected version 0, [TurnStarted])
    E->>P: in one transaction: current version of the stream
    alt the stream is empty
        E->>P: insert version 1, event id UUIDv7
        P-->>E: committed
        S-->>W: Result(turnId, created = true)
    else redelivery, or another writer won the race
        P-->>E: version is not 0, or events_stream_version_uk violated
        E-->>S: ConcurrencyConflictException
        S-->>W: Result(turnId, created = false), same turn
    end
```

The turn id is derived from the alert, so a redelivered alert finds its stream taken and returns the same
turn: idempotent without a table of its own.

## Advancing a turn

```mermaid
sequenceDiagram
    autonumber
    participant K as worker poller
    participant A as AdvanceTurn
    participant E as EventStore
    participant D as Domain (Turn, TurnDecider)
    participant L as LlmPort
    participant T as ToolPort

    rect rgb(235, 235, 235)
        note over K: planned, item 32
        K->>A: run(turnId)
    end
    loop until Halt, or a model call fails
        A->>E: load(turnId)
        A->>D: Turn.fold(events)
        A->>D: TurnDecider.decide(state)
        D-->>A: next command
        alt CallLlm
            A->>E: LlmCallStarted
            A->>L: system prompt, history, tool catalog
            note over L: fakes today, Ollama item 32, Anthropic API item 42
            L-->>A: text, tool calls, tokens
            A->>E: LlmCallCompleted, or LlmCallFailed and the run ends
        else ProposeTool
            A->>E: ToolCallProposed, risk from ToolCatalog
        else ExecuteTool (READ_ONLY, SIDE_EFFECT or approved)
            A->>E: ToolCallStarted, idempotency key turnId:toolCallId
            A->>T: execute
            note over T: fakes today, Vortex MCP tools item 34
            T-->>A: result or error
            A->>E: ToolCallCompleted, ToolCallFailed or ToolOutcomeUnknown
        else AwaitApproval (DANGEROUS)
            A->>E: ApprovalRequested
        else Complete or Fail
            A->>E: TurnCompleted, or TurnFailed after 8 model calls
        else Halt
            A-->>K: state: ended, or waiting for a human
        end
    end
```

- Every effect is preceded by its `*Started` event, and no transaction spans an effect. A crash leaves a
  stream the next run can read: a `READ_ONLY` tool that started is run again, any other becomes
  `ToolOutcomeUnknown` and waits for a human.
- A lost append race before an effect makes the run decide again from the new state. After an effect, the
  run reloads and appends the same outcome, so the effect is never repeated.
- A turn halted in `AWAITING_APPROVAL` moves again only when `ApprovalGranted`, `ApprovalDenied` or
  `ApprovalExpired` is appended. Today only the tests append them; the approval API is item 37.
