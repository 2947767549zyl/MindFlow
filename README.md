# MindFlow

> 一个基于本地终端原生使用的 Java Agent CLI 产品，对标 Claude Code。  
> 从 ReAct 循环起步，逐步演进至多 Agent 协作、MCP 协议集成、Skill 系统等完整 Agent 产品形态。

<img width="1522" height="779" alt="image" src="https://github.com/user-attachments/assets/5ccc34be-76d4-4884-a918-f240564d2941" />


---

## 概览

MindFlow 是一个**自研的 Java Agent CLI**，基于LangChain4j 等框架。它在终端中运行，通过命令行与用户交互，可自主完成文件操作、代码理解、联网搜索、浏览器自动化等任务。

### 核心能力

| 能力 | 说明 |
|---|---|
| **三种 Agent 模式** | ReAct（默认）、Plan-and-Execute（DAG 规划）、Multi-Agent（Planner/Worker/Reviewer 协作） |
| **可分层的记忆系统** | 短期记忆（TokenBudget 滑动窗口）+ 长期记忆（JSON 持久化，显式保存） |
| **代码库理解（RAG）** | Embedding + SQLite 向量检索 + AST 关系图谱 |
| **MCP 协议集成** | 自研 JSON-RPC 客户端，支持 stdio 子进程与 Streamable HTTP 双通道 |
| **浏览器自动化** | 集成 Chrome DevTools MCP，支持 SPA 页面读取与登录态复用 |
| **安全体系** | 路径围栏 + 命令黑名单 + HITL 人工审批 + 操作审计（JSONL） |
| **联网能力** | 内置智谱 Web Search / SerpAPI / SearXNG + HTML 正文提取 |
| **多模型支持** | GLM-5.1 / DeepSeek V4 运行时切换 |
| **Skill 系统** | 可复用专家手册，SKILL.md + references 按需加载 |
| **并行工具调用** | 同一轮多个工具并行执行，最大 4 并发 |

---

## 快速开始

### 前置要求

- Java 17+
- Maven
- 可用的智谱 API Key（GLM_API_KEY）

### 1. 配置 API Key
bash cp .env.example .env
编辑 .env，填入你的 API Key

API Key 读取顺序：项目 `.env` → 用户 `~/.env` → 环境变量 `GLM_API_KEY`

### 2. 编译运行
bash mvn clean package 
java -jar target/mindflow-1.0-SNAPSHOT.jar
或直接通过 Maven 运行：
bash 
mvn clean compile exec:java -Dexec.mainClass="com.mindflow.cli.Main"
### 3. 试用
👤 你: 你好，请列出当前目录的文件
🧠 思考过程: 用户想了解当前目录结构，我调用 list_dir 工具查看。
🤖 回复: 当前目录包含 src/、target/、pom.xml、README.md 等， 这是一个标准的 Java Maven 项目。

---

## 使用模式

### ReAct 模式（默认）

单轮对话驱动的"思考-行动-观察"循环，适合简单任务或单步操作。LLM 自主决定何时调用工具、何时给出最终回复。

### Plan-and-Execute 模式

通过 `/plan` 或 `/plan <任务>` 进入。LLM 先生成带依赖顺序的执行计划（DAG），用户确认后按序执行。适合多步骤、带依赖的复杂任务。

/plan 创建一个 java 项目，读取 pom.xml，验证项目结构

### Multi-Agent 协作模式

通过 `/team` 或 `/team <任务>` 进入。三个角色协作：

- **Planner**：拆解任务为执行步骤
- **Worker**（2 个）：执行具体工具操作
- **Reviewer**：审查结果质量，未通过则带反馈重试

---

## 命令参考

### 执行模式
| 命令 | 说明 |
|---|---|
| `/plan [任务]` | Plan-and-Execute 模式 |
| `/team [任务]` | Multi-Agent 协作模式 |
| `/cancel` | 请求取消当前任务 |

### 记忆与代码
| 命令 | 说明 |
|---|---|
| `/memory` / `/mem` | 查看记忆状态 |
| `/memory clear` | 清空长期记忆 |
| `/save <事实>` | 保存关键事实到长期记忆 |
| `/index [路径]` | 索引代码库 |
| `/search <查询>` | 语义检索代码 |
| `/graph <类名>` | 查看代码关系图谱 |

### 安全与审计
| 命令 | 说明 |
|---|---|
| `/hitl on/off` | 启用/关闭人工审批 |
| `/hitl` | 查看 HITL 状态 |
| `/policy` | 查看安全策略 |
| `/audit [N]` | 查看最近 N 条审计记录 |

### 模型与 MCP
| 命令 | 说明 |
|---|---|
| `/model glm` / `/model deepseek` | 运行时切换模型 |
| `/mcp` | 查看所有 MCP server 状态 |
| `/mcp restart <name>` | 重启 MCP server |
| `/mcp logs <name>` | 查看 stderr 日志 |
| `/mcp disable/enable <name>` | 启用/禁用 MCP server |
| `/mcp resources <name>` | 查看 server 暴露的资源 |

### 浏览器
| 命令 | 说明 |
|---|---|
| `/browser status` | 浏览器连接状态 |
| `/browser connect [port]` | 切换到复用登录态模式 |
| `/browser disconnect` | 断开登录态浏览器 |
| `/browser tabs` | 查看当前标签页 |

### Skill 系统
| 命令 | 说明 |
|---|---|
| `/skill list` | 查看所有 Skill |
| `/skill show <name>` | 查看 Skill 详情 |
| `/skill on/off <name>` | 启用/禁用 Skill |
| `/skill reload` | 重载所有 Skill |

### 其他
| 命令 | 说明 |
|---|---|
| `/clear` | 清空对话历史 |
| `/context` | 查看上下文策略信息 |
| `/exit` / `/quit` | 退出程序 |

---

## 可用工具

| 工具 | 说明 |
|---|---|
| `read_file` / `write_file` | 文件读写（5MB 上限） |
| `list_dir` | 列出目录 |
| `execute_command` | 执行 Shell 命令（60 秒超时） |
| `create_project` | 创建 Java/Python/Node 项目 |
| `search_code` | 语义检索代码库 |
| `web_search` | 联网搜索（智谱/SerpAPI/SearXNG） |
| `web_fetch` | 抓取 URL 提取正文 Markdown |
| `save_memory` | 保存长期记忆 |
| `load_skill` | 加载 Skill 手册 |
| `browser_connect/disconnect/status` | 浏览器连接管理 |
| `mcp__{server}__{tool}` | MCP server 动态工具 |

---

## 配置说明

### 记忆与索引持久化

- 长期记忆：`~/.mindflow/memory/long_term_memory.json`
- 代码索引：`~/.mindflow/rag/codebase.db`
- 可通过 `-Dpaicli.memory.dir=` / `-Dpaicli.rag.dir=` 自定义目录

### MCP 配置

支持 stdio server 与 Streamable HTTP server：

json { "mcpServers": { "fetch": { "command": "uvx", "args": ["mcp-server-fetch"] }, "remote-demo": { "url": "https://example.com/mcp", "headers": {"Authorization": "Bearer ${TOKEN}"} } } }

配置读取：`~/.mindflow/mcp.json` → `.mindflow/mcp.json`（项目级覆盖用户级）

### 日志配置

- 默认路径：`~/.mindflow/logs/mindflow.log`
- 支持环境变量：`MINDFLOW_LOG_LEVEL` / `MINDFLOW_LOG_DIR` / `MINDFLOW_LOG_MAX_HISTORY` 等

---

## 技术栈

| 类别 | 技术 |
|---|---|
| 语言 | Java 17 |
| 构建 | Maven |
| LLM 接口 | GLM-5.1 / DeepSeek V4（OpenAI 兼容协议） |
| HTTP | OkHttp 4.x |
| JSON | Jackson |
| 终端交互 | JLine 3 |
| 向量存储 | SQLite（RAG 模块） |
| AST 解析 | JavaParser |
| Embedding | Ollama（本地）/ 远程 API |
| MCP 协议 | 自研 JSON-RPC 2.0 客户端 |

> 注：MindFlow 不依赖 Spring Boot、Spring AI、LangChain4j 等 Web 框架，是纯原生 Java CLI 应用。

---

## 项目结构
src/main/java/com/mindflow/ 
├── agent/ # Agent 执行引擎（ReAct / Plan / Multi-Agent） 
├── cli/ # CLI 入口与命令解析 
├── llm/ # LLM 客户端抽象与实现 
├── context/ # 上下文策略与 Token 管理 
├── memory/ # 分层记忆系统（短期 / 长期） 
├── plan/ # DAG 执行计划 
├── rag/ # 代码索引与语义检索 
├── tool/ # 工具注册与并行执行 
├── hitl/ # 人工审批系统 
├── policy/ # 安全策略（路径围栏 / 命令黑名单 / 审计） 
├── web/ # 联网搜索与页面抓取 
├── mcp/ # MCP 协议客户端（JSON-RPC / 双通道传输） 
├── browser/ # Chrome DevTools 会话管理 
├── skill/ # Skill 系统（SKILL.md 加载器） 
└── util/ # 工具类
---

## 设计理念

1. **自研优于框架**：LLM 调用协议、MCP 客户端、Agent 编排等核心模块全部手写，保持对协议和流程的完全控制
2. **安全不妥协**：HITL + 路径围栏 + 命令黑名单 + 审计日志，多层防护而非单一沙箱
3. **经验沉淀**：通过 Skill 系统把"Agent 该怎么思考"从硬编码 prompt 抽出，沉淀为可复用的专家手册
4. **模型无关**：通过统一的 `LlmClient` 接口抽象，支持运行时切换不同模型

---

## License

[MIT](LICENSE)
