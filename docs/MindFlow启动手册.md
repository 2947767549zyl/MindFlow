# MindFlow 启动手册

> MindFlow = 灵犀 RAG 知识库平台（Spring Boot 3 + Vue3）× PaiCLI Agent 引擎（ReAct / Plan-and-Execute / Multi-Agent）的合并项目。
> 本手册覆盖前后端从零配置到启动运行的全部步骤，以及合并后新增的 Agent 能力开关。

## 一、前置依赖

### 后端
| 组件 | 版本/说明 |
|---|---|
| JDK | 21（已验证：`C:\Users\Administrator\.jdks\oracle_open_jdk-21`） |
| Maven | 3.9+（本机用 IDEA 自带：`D:\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd`） |
| MySQL | 主库：用户/文档/会话/wiki_pages/long_term_memories/audit_logs |
| Redis | 会话状态、生成快照、反馈、限流 |
| Elasticsearch | 知识库向量+BM25 混合检索 |
| Kafka | 文档异步解析 |
| MinIO | 文档对象存储 |

### 前端
| 组件 | 版本/说明 |
|---|---|
| Node | >=18.20（**推荐 22.12.0**，本机 `nvm install 22.12.0 && nvm use 22.12.0`；ESLint 9 需要 import attributes 语法，18.17 跑不起来） |
| pnpm | >=8.7（Node 22 下 `npm i -g pnpm`，本机 12.8.1） |

## 二、后端启动

```powershell
$env:JAVA_HOME="C:\Users\Administrator\.jdks\oracle_open_jdk-21"

# 构建（已验证 BUILD SUCCESS）
mvn -DskipTests package

# 运行（三选一）
java -jar target\MindFlow-16.0.0-SNAPSHOT.jar
mvn spring-boot:run
# 或 IDEA 直接运行 com.mindflow.MindFlowApplication
```

- 配置源：仓库根 `.env`（MySQL/Redis/ES/Kafka/MinIO 凭据、JWT 密钥）+ `src/main/resources/application.yml`
- 端口：`8081`
- 新增三张表（JPA 自动建）：`wiki_pages`、`long_term_memories`、`audit_logs`

### 合并新增配置（application.yml `mindflow:` 段，全部有默认值）
```yaml
mindflow:
  react:            # ReAct 主循环：max-rounds 10 / max-tool-calls 8 / stagnation-window 3
  tool:             # 并行工具：parallelism 4 / batch-timeout-seconds 90
  plan:             # /plan 审阅超时 review-timeout-seconds 300（超时自动取消）
  hitl:             # L2 人工审批：enabled false / approval-timeout-seconds 120
  mcp:              # MCP server：enabled true / config-path / project-dir
  audit:            # 审计 JSONL 目录：data/audit
```

## 三、前端启动

```powershell
cd frontend
pnpm install
pnpm dev        # vite --mode test → http://localhost:9527（代理直连 localhost:8081）
pnpm build      # 生产构建 → dist/（已验证 BUILD SUCCESSFUL）
```

- `.env`（基础变量，已补齐：标题/图标前缀/路由模式 hash/业务码/WS 超时等）
- `.env.test`（开发：`VITE_SERVICE_BASE_URL=http://localhost:8081/api/v1`）
- `.env.prod`（构建：`/api/v1` 相对路径）
- 本次修复的仓库缺失声明：`@iconify/utils`、`@unocss/preset-wind3`、`@sa/axios` 的 `nanoid`

## 四、Agent 三模式用法（聊天输入框）

| 输入 | 行为 |
|---|---|
| 普通文本 | ReAct：知识库优先检索 → 工具调用 → 流式回答 |
| `/plan <任务>` | 生成 DAG 计划 → **WS 弹窗审阅**（执行/取消，300s 超时自动取消）→ 批次并行执行（≤4）→ `plan_step`/`plan_status` 进度 → 失败且进度<50% 自动重规划 |
| `/team <任务>` | 规划者 → 执行者×2（批次并行）→ 检查者（最多重试 2 次），`team_event` 实时展示每步状态 |
| `/cancel` 或停止按钮 | CancellationContext 级联取消（LLM 流/工具批次/循环边界全部感知） |

## 五、工具与安全分层

- **内置工具**：`search_knowledge`（Rerank 精排）、`generate_summary`、`submit_feedback`、`knowledge_stats`、`search_wiki`、`navigate_wiki`（反向链接）、`save_memory`（长期记忆）
- **执行链**：引擎 → `ParallelToolRunner`（并行≤4/批次90s/结果按序回灌）→ `HitlToolRegistry`（L2 审批）→ `AgentToolRegistry`（L1 注册表）
- **HITL**：`mindflow.hitl.enabled=true` 后，`mcp__*` 工具与高危工具触发 `approval_request` 弹窗（批准/拒绝/本次会话全部放行/跳过）；关闭时行为与普通执行完全一致
- **MCP**：配置 `~/.mindflow/mcp.json`（兼容 Claude Desktop 格式，stdio + Streamable HTTP）；无配置文件 = no-op；MCP 工具一律强制审批
- **审计双写**：危险工具 allow/deny → `data/audit/audit-YYYY-MM-DD.jsonl` + MySQL `audit_logs`，敏感参数（token/key/password/secret/authorization/Bearer）自动脱敏
- **长期记忆**：用户说"记一下/记住"触发 `save_memory` → MySQL + `memory_saved` WS 推送；每轮 ReAct 注入相关记忆上下文；REST 管理：`GET/POST/DELETE /api/v1/memory`

## 六、WS 协议 v2（纯增量，前端对未知 type 静默忽略）

后→前：`plan_review` / `plan_step` / `plan_status` / `team_event` / `approval_request` / `memory_saved`
前→后：`{"type":"plan_review_response", planId, action, feedback?}`、`{"type":"approval_response", approvalId, decision, modifiedArgs?}`
所有消息携带 `generationId + conversationId`（多 tab 按代次路由）。

## 七、验证状态与已知限制

| 项 | 状态 |
|---|---|
| 后端编译/打包 | ✅ EXIT=0，`MindFlow-16.0.0-SNAPSHOT.jar` |
| 前端安装/构建 | ✅ pnpm install + vite build BUILD SUCCESSFUL |
| 定向单元测试 | ✅ 56/56 绿（harness 引擎/MCP 协议/HITL/审计/记忆/工具桥接） |
| 全量 mvn test | ⚠️ 8 个 `@SpringBootTest` 因本环境无 MySQL/Redis/ES/Kafka/MinIO 失败（既有基线，非本次改动）；实机有基础设施后可全绿 |
| 前端 typecheck | ⚠️ 仓库既有基线噪音（auto-import dts 类错误），与本次改动无关 |
| `local-logo` 图标 | ⚠️ 本地 svg 素材缺失警告，非致命 |
| Skill Redis（设计 §12） | ⏳ 唯一未移植设计项，不影响启动与三大模式演示 |

## 八、实机冒烟清单（有基础设施的机器上）

1. 启动后端 → 日志出现 `MindFlow` banner、ES 索引初始化、Kafka 消费者就绪
2. 登录（9527）→ 上传文档 → 等待 Kafka 解析 + Wiki 生成（wiki_pages 双写生效）
3. 普通提问 → 流式回答 + 引用标注 + 工具调用事件
4. `/plan 总结知识库中的核心概念` → 计划弹窗 → 执行 → 步骤进度 → 汇总
5. `/team 对比 X 与 Y 两个概念` → 三角色协作事件流
6. （可选）开启 `mindflow.hitl.enabled` + 配置一个 MCP server → 触发审批弹窗 → 审计文件落盘
