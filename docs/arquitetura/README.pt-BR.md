# Arquitetura

*[Read in English](README.md)*

A arquitetura do Turnward como código, mantida em dia pelos próprios pull requests que a mudam.

| Documento | O que mostra |
|---|---|
| [overview.svg](overview.svg) | Desenho do sistema inteiro numa página, usado nos READMEs. Fonte: [overview.excalidraw](overview.excalidraw) |
| [context.pt-BR.md](context.pt-BR.md) | C4 nível 1: o Turnward, quem o usa e os sistemas externos em volta |
| [containers.pt-BR.md](containers.pt-BR.md) | C4 nível 2: os dois papéis da aplicação, o event store e os módulos por dentro |
| [flows.pt-BR.md](flows.pt-BR.md) | Dois fluxos em execução: o início de um turno a partir de um alerta e o avanço dele pelo modelo e pelas tools |

Os diagramas são blocos Mermaid `flowchart` e `sequenceDiagram` com legenda C4. A sintaxe `C4Context`
do próprio Mermaid ainda é experimental e renderiza mal no GitHub, por isso não é usada.

## Construído e planejado

Cada página mostra o que existe no `main` e o que o programa de portfólio tem com data, nada além disso.

- As partes construídas são preenchidas, com setas contínuas.
- As planejadas são brancas, de borda tracejada, com setas tracejadas, cada uma com o item do programa que a traz.
- Módulos sem item datado (Kafka, Redis, o servidor MCP) ficam fora até um item trazê-los.

O pull request que constrói uma parte planejada a deixa contínua no mesmo diff.

## Como se mantém em dia

O check `architecture` ([architecture.yml](../../.github/workflows/architecture.yml)) reprova o pull
request que muda um caminho-chave sem tocar em `docs/arquitetura/` nem em `docs/adr/`. Caminho-chave é o
que move uma caixa ou uma seta:

- o `pom.xml` de cada módulo `turnward-*`, onde moram as dependências entre módulos
- as portas do `turnward-application` (`application/port/`)
- os `application*.yml` do `turnward-app` e as migrations do Flyway
- o `ArchitectureTest`, as regras do hexágono
- `deploy/`, `infra/**/*.tf` e `agents/`

O `pom.xml` da raiz (versões e plugins) e o código de dentro dos adapters não são caminho-chave: preencher
um adapter muda o que uma caixa faz, não onde ela está.

Quando um caminho-chave muda e a arquitetura não (nível de log, comentário numa migration), o rótulo
`arquitetura-sem-mudanca` no pull request faz o check rodar de novo e passar. Fora disso, atualize o
diagrama que a mudança toca, nos dois idiomas, e o SVG quando uma caixa ou seta da visão geral mudou.

## Como editar a visão geral

1. Abra `overview.excalidraw` em [excalidraw.com](https://excalidraw.com) (File, Open) ou no plugin
   Excalidraw do VS Code ou do Obsidian.
2. Edite e exporte como SVG, com "Embed scene" desligado e o fundo ligado, por cima de `overview.svg`.
3. Salve a cena por cima de `overview.excalidraw`. Os dois arquivos vão no mesmo pull request.
