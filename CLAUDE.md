# CLAUDE.md

This file provides guidance to AI coding agents when working with code in this repository.

> The canonical agent-facing working agreement is [`AGENTS.md`](AGENTS.md). Read it first. This file is the condensed technical reference.

## Project Overview

**MindFlow** (Maven artifact `com.mindflow:MindFlow`) is a knowledge-base driven enterprise Agent platform. It fuses two layers:

- **RAG knowledge base** — document ingestion, hybrid retrieval (KNN + BM25 + RRF), structured Wiki-RAG knowledge network, multi-tenant isolation, token quota and billing.
- **Agent engine** (`com.mindflow.harness.*`) — ReAct decision loop, Plan-and-Execute, Multi-Agent orchestration, HITL human approval, MCP tool integration, Skill system, long-term memory, and full-chain audit / trace replay.

Stack: Java 21, Spring Boot 3.4.2, Vue 3 + TypeScript, MySQL, Redis, Elasticsearch, Kafka, MinIO, WebSocket, Spring Security + JWT.

Naming is unified to **MindFlow**. Older names you may still see in stale artifacts (`LingXi` / 灵犀 / `PaiSmart` / 派聪明 / package `com.yizhaoqi.smartpai`) are the pre-merge lineage — the actual code is all under `com.mindflow`.

## Development Environment

- Java 21, Maven 3.9+
- Node >= 18.20 (recommended 22.12.0), pnpm >= 8.7
- Infrastructure: MySQL 8.0, Elasticsearch 8.10.0, Redis 7.0.11, Kafka 3.2.1, MinIO (Docker optional)

## Common Commands

### Backend

```bash
mvn -q -DskipTests compile   # after Java edits — prefer this to trigger hot reload (see AGENTS.md)
mvn spring-boot:run          # run app (main class: com.mindflow.MindFlowApplication)
mvn test                    # unit tests (harness tests run standalone; @SpringBootTest needs infra)
mvn clean package           # build jar -> target/MindFlow-16.0.0-SNAPSHOT.jar
```

### Frontend

```bash
cd frontend
pnpm install
pnpm dev          # vite --mode test -> http://localhost:9527 (proxies to http://localhost:8081/api/v1)
pnpm build        # production build -> dist/
pnpm typecheck    # tsc type check
pnpm exec eslint <file>   # targeted lint
```

## Architecture

### Backend layout (`src/main/java/com/mindflow/`)

```
MindFlowApplication.java     # entry
client/                      # DeepSeek / Embedding API clients
config/                      # elasticsearch, kafka, minio, redis, security, web, initializer, interceptor, properties
harness/                     # ★ Agent engine (the core merge increment)
  execute/                   #   AgentBudget, ParallelToolRunner, PlanExecuteService, SubAgent(Service), TaskClarification
  plan/                      #   Planner, ExecutionPlan, Task (DAG plan)
  hitl/                      #   ApprovalPolicy, HitlApprovalRegistry, HitlToolRegistry (L2 human approval)
  mcp/                       #   McpClient/Server/Manager, transport (Stdio / StreamableHttp), jsonrpc, protocol, resources
  policy/                    #   CommandGuard, PathGuard, ToolPolicyGateway (L1), AuditLogService (L3)
  memory/                    #   ConversationHistoryCompactor, TokenBudget, HistoryDigest
  skill/                     #   Skill, SkillRegistry, SkillFrontmatterParser, SkillStateStore (3-tier disclosure)
module/                      # business modules (controller/service/repository/entity per module)
  auth · document · search · wiki · chat        # RAG core
  organization · admin · recharge · usage       # enterprise base
  audit · memory · skill · mcp                  # persistence + REST side of harness capabilities
  chat/service/  ReActAgentService, ReasoningMemoryService, ChatOrchestrationService, ConversationService
  chat/agent/    AgentToolRegistry, AgentToolExecutor, LocalFileTools
structure/                   # LlmProviderRouter (AI routing), FileProcessingConsumer (Kafka)
utils/ · entity/ · repository/ · service/
```

### Frontend layout (`frontend/`)

```
packages/   # reusable modules (axios, hooks, utils, ...)
src/
  views/     # chat, chat-history, wiki-graph, skill, mcp-config, agent-tool,
             # model-provider, org-tag, personal-center, usage-monitor, recharge-manage, ...
  components/ · layouts/ · router/ (guards) · service/ (API) · store/ (Pinia)
  hooks/ · locales/ · styles/ · theme/
```

## Key Components

- **`LlmProviderRouter`** — single routing point for all LLM calls (DeepSeek / Ollama / Aliyun).
- **`AgentToolRegistry` / `AgentToolExecutor`** — register and run all LLM-callable tools.
- **`ReActAgentService`** — the ReAct loop: LLM-autonomous exit plus three safety valves (stagnation detection / hard max-rounds / token budget via `AgentBudget`).
- **`ParallelToolRunner`** — parallel tool batching (≤4 concurrent, 90s batch timeout, results replayed in input order to keep tool-message pairing).
- **`HybridSearchService`** — KNN + BM25 parallel recall (50 each) → RRF fusion (k=60) top-20; permission filter pushed down to ES `filter`.
- **`WikiExtractionService` / `WikiPageService` / `WikiSearchService`** — Map-Reduce Wiki compilation, bidirectional links, keyword search.

## Three Agent Modes (chat input box)

| Input | Mode | Behavior |
|---|---|---|
| plain text | ReAct | knowledge-first retrieval → tool calls → streaming answer |
| `/plan <task>` | Plan-and-Execute | DAG plan → WS review popup (300s timeout) → parallel batches → auto-replan if failed & progress <50% |
| `/team <task>` | Multi-Agent | planner → executors×2 → checker (retry ≤2), `team_event` stream |
| `/cancel` | — | `CancellationContext` cascading cancel (LLM stream / tool batches / loop boundaries) |

## Security Layering (L1 / L2 / L3)

- **L1 rule filter** `ToolPolicyGateway` (CommandGuard + PathGuard): destructive-command blacklist, path fence. Always on, targets only `mcp__*` external tools.
- **L2 human approval** HITL (`mindflow.hitl.enabled`, default off): MCP tools require approval via WS `approval_request`; modified args re-checked by L1.
- **L3 audit** `AuditLogService`: JSONL daily file (`data/audit/`) + MySQL `audit_logs` dual-write; secrets (`Bearer/token/key/password/secret`) masked before persistence; write failure is non-blocking.

Built-in tools are all read-only or write-only-to-own-storage (no filesystem/shell). The defense focus is external MCP tools.

## Configuration

- Backend: repo-root `.env` + `src/main/resources/application.yml` / `application-dev.yml`. Port `8081`.
- Frontend: `frontend/.env*`; dev proxy to `http://localhost:8081/api/v1`.
- Agent engine flags live under the `mindflow:` section of `application.yml` (react / tool / plan / hitl / mcp / audit / policy), all with defaults.
- `.env` is gitignored and must never be committed; `application*.yml` reference secrets only via `${ENV:default}` placeholders.

## Data & Persistence Notes

- Redis is for short-lived chat context / session / cache / rate-limit. **Persistent history lives in MySQL.** Do not assume Redis means durable.
- JPA auto-DDL creates tables including `wiki_pages`, `long_term_memories`, `audit_logs`.
- `data/wiki/` and `data/audit/` are **runtime-generated artifacts** (gitignored). `data/skills/` are bundled demo skills.

## Testing Notes

- Harness unit tests (MCP transports, JsonRPC, HITL, guards, skill parsing, plan/multi-agent, audit) run without infra.
- `@SpringBootTest` integration tests require live MySQL/Redis/ES/Kafka/MinIO; they fail without them (expected baseline, not a regression).
- UI/interaction bugs: verify in a real browser at `http://localhost:9527`, inspect network + console, and confirm which layer (frontend/backend/data/env) is actually at fault before assuming.
