---
status: accepted
date: 2026-11-02
---

# ADR-0011: Ollama in dev and test, the Anthropic API in production, both behind Spring AI

## Context and problem statement

A turn calls a model on every planning step and after every tool result. Development and tests need a model that is free and works offline. The production demo needs one that answers fast enough to narrate a live incident.

The first plan was Claude on Amazon Bedrock, so that model access would stay inside the same AWS account as the rest of the stack. AWS declined the access request for this account, stating that model access can depend on regional factors, payment history and account usage, and that it could not approve the request. An earlier check on the same account had already returned `agreementAvailability: NOT_AVAILABLE` and a daily token quota of zero. The production path had to be decided again.

## Decision drivers

- The whole project has a US$ 20 ceiling (ADR-0013), so an overrun must stop requests, not become a bill.
- A reversible choice beats an irreversible bet: nothing in the demo justifies a step that cannot be undone.
- The domain and the use cases must not notice the provider (ADR-0001).
- The decider owns the loop: every tool call must pass through `TurnDecider` so that it is recorded and, when dangerous, waits for a human.
- A turn in the demo should take seconds, not many minutes.

## Considered options

1. The Anthropic API directly, through Spring AI's Anthropic model, on prepaid credits with no auto-reload.
2. Claude Platform on AWS: operated by Anthropic, with SigV4 and IAM, billed through AWS Marketplace.
3. Upgrade the AWS account to the paid plan and ask for Bedrock access again.
4. Ollama in production too, on Fargate.

## Decision outcome

Chosen option: **option 1**, the Anthropic API.

- **Dev and test:** Ollama through Spring AI. The automated gate uses a stub `ChatModel`, so it stays fast and deterministic.
- **Production:** Claude Haiku 5.5 (`claude-haiku-5-5`) by default and Claude Sonnet 5.5 (`claude-sonnet-5-5`) for the planning step.
- **Spending limit:** prepaid credits with no auto-reload. When the balance runs out the API answers with an error and the turn records `LlmCallFailed`; nothing is charged. A dedicated workspace adds a monthly spend limit.
- **Secret:** the API key lives in SSM SecureString and reaches only the worker task. It never enters a shared or global environment.
- **`ChatModel`, not `ChatClient`.** In Spring AI 2.0.1 `ChatClient` registers a tool-calling advisor that runs tools on its own, which would take `ToolCall*` events and approval out of the stream. `ChatModel.call` sends the tool definitions and returns what the model asked for without running it. "Tool calling" here means the model sees the tools; the decider runs them.
- **Options per provider.** `ChatModel` does not merge options. A prompt with options is used as it comes: Ollama rejects generic options by casting them, and Anthropic silently replaces them with empty ones, which would lose the tool callbacks. Each adapter starts from `chatModel.getOptions().mutate()`.

### Consequences

- Good: no irreversible step, and no dependency on the AWS account's standing with the model providers.
- Good: Bedrock or Claude Platform on AWS stay one adapter away, which is the claim ADR-0001 makes.
- Good: on the prices checked in October 2026 the API costs the same per token as Bedrock's global inference profile.
- Bad: the AWS stack no longer shows IAM-scoped model access; a long-lived key has to be stored and rotated.
- Bad: the worker needs internet egress. This already holds, because tasks run in public subnets (ADR-0013).
- Neutral: Anthropic's starter is not a drop-in change in this build; see below.
- Neutral: `LlmPort` exposes one model for now, which is all Ollama needs. Sonnet on the planning step needs the step in the request, and comes with the Anthropic adapter.

### Confirmation

Decided on documentation and on reading the sources of Spring AI 2.0.1 and the Anthropic Java SDK 2.52.0. **No real call has been made yet.** Before production, one run has to prove:

- `dependencyConvergence` passes with `spring-ai-starter-model-anthropic`. Reading the enforcer's rules predicts three `com.github.victools` divergences (5.0.0 against 4.38.0), to be pinned in `dependencyManagement`.
- A tool-use conversation works when replayed from the stream. The stream keeps only each answer's text and tool calls, and adaptive thinking is on by default on Haiku 5.5 and Sonnet 5.5, so the replay leaves the thinking blocks out and may be rejected with a 400. Haiku 5.5 can run with thinking disabled; Sonnet 5.5 cannot avoid thinking blocks between tools in the same way. The last resort is the SDK directly for that part of the adapter, because Spring AI's types for sending those blocks back are package-private and it reorders content. That also means keeping the model's raw content in `LlmCallCompleted`, which is a change to the event.
- `tokensIn` and `tokensOut` are complete: an empty `content` yields a `ChatResponse` with no generations, so usage and the finish reason are lost. The adapter must not read `getResult()` blindly. A refusal looks the same, and `AdvanceTurn` records an answer with neither text nor tools as `LlmCallFailed`, so a refusal cannot complete a turn.

Sampling parameters need no action: Haiku 5.5 and Sonnet 5.5 reject non-default values of `temperature`, `top_p` and `top_k`, and Spring AI sends none of them unless configured.

## Pros and cons of the options

### Option 2: Claude Platform on AWS

- Good: same price per token as the direct API, full API parity, and IAM instead of a long-lived key.
- Bad: subscribing goes through AWS Marketplace, and no source says whether this account's plan allows it; only trying decides.
- Bad: charges would probably reach the card, since AWS credits exclude Marketplace.
- Bad: the Java SDK's support is in beta, and the integration with Spring AI is untested.

### Option 3: paid AWS plan and a new Bedrock request

- Good: the original design stands, with the model behind IAM.
- Bad: the upgrade is irreversible, and the account would probably have no hard spending limit.
- Bad: no documentation or report found shows the upgrade alone lifting a denial. AWS lists account age and usage history among the factors, and an upgrade changes neither.

### Option 4: Ollama in production

- Good: no external account and no per-token cost; about US$ 2.80 more for 30 hours on Fargate.
- Bad: estimated at 12 to 29 minutes per turn on 2 vCPU with no GPU, not measured directly, which rules out a live demo.
- Bad: tool-calling reliability of small local models is unproven in this project.
