package com.mindflow.cli;

final class CliCommandParser {

    enum CommandType {
        // NONE: 用户输入为空、null，或不是以 "/" 开头的普通对话内容（即非命令）
        NONE,

        // UNKNOWN_COMMAND: 用户输入了以 "/" 开头但系统无法识别的命令（如 /xyz）
        UNKNOWN_COMMAND,

        // CANCEL: 用户输入 "/cancel" 或 "cancel"（用于取消当前任务，当前实现仅提示无任务）
        CANCEL,

        // EXIT: 用户输入 "/exit"、"/quit"、"exit" 或 "quit"（退出程序）
        EXIT,

        // CLEAR: 用户输入 "/clear" 或 "clear"（清空当前对话历史，保留长期记忆）
        CLEAR,

        // SWITCH_MODEL:
        //   - 仅输入 "/model" → 查看当前模型信息；
        //   - 输入 "/model <provider>"（如 /model deepseek）→ 切换 LLM 提供商
        SWITCH_MODEL,

        // SWITCH_PLAN:
        //   - 仅输入 "/plan" → 进入“计划模式”（设置标志位，下次输入按 Plan 模式处理）；
        //   - 输入 "/plan <任务描述>" → 立即将该任务交给 Agent 执行（视为普通输入）
        SWITCH_PLAN,

        // SWITCH_TEAM:
        //   - 仅输入 "/team" → 进入“团队协作模式”；
        //   - 输入 "/team <任务描述>" → 立即执行该团队任务
        SWITCH_TEAM,

        // SWITCH_HITL:
        //   - "/hitl" → 查看当前人工审批（HITL）状态；
        //   - "/hitl on" → 启用高危操作需人工审批；
        //   - "/hitl off" → 关闭审批，并清空“全部放行”记录
        SWITCH_HITL,

        // MEMORY_STATUS: 用户输入 "/memory" 或 "/mem" → 显示当前长期记忆状态（如向量库大小）
        MEMORY_STATUS,

        // MEMORY_CLEAR: 用户输入 "/memory clear" 或 "/mem clear" → 清空长期记忆（RAG 向量库）
        MEMORY_CLEAR,

        // MEMORY_SAVE:
        //   - 仅输入 "/save" → 将当前对话保存为记忆快照；
        //   - 输入 "/save <名称>" → 保存为指定名称的记忆快照
        MEMORY_SAVE,

        // INDEX_CODE:
        //   - 仅输入 "/index" → 对当前目录进行代码索引；
        //   - 输入 "/index <路径>" → 对指定路径进行代码索引
        INDEX_CODE,

        // SEARCH_CODE:
        //   - 仅输入 "/search" → 不带查询词，通常会提示错误（但解析仍成功）；
        //   - 输入 "/search <关键词>" → 在已索引代码中执行混合检索
        SEARCH_CODE,

        // GRAPH_QUERY:
        //   - 仅输入 "/graph" → 查询空，可能无效；
        //   - 输入 "/graph <类名>" → 查询该类在代码库中的关系图谱（继承、调用等）
        GRAPH_QUERY,

        // CONTEXT_STATUS: 用户输入 "/context" 或 "/ctx" → 显示当前上下文长度、token 使用情况等
        CONTEXT_STATUS,

        // POLICY_STATUS: 用户输入 "/policy" → 显示安全策略：危险工具列表、命令黑名单、文件写入限制等
        POLICY_STATUS,

        // AUDIT_TAIL:
        //   - 仅输入 "/audit" → 显示最近若干条审计日志；
        //   - 输入 "/audit <数字>"（如 /audit 10）→ 显示最近 N 条日志
        AUDIT_TAIL,

        // MCP_LIST: 用户输入 "/mcp" → 列出所有已配置的 MCP 服务及其状态
        MCP_LIST,

        // MCP_RESTART: 用户输入 "/mcp restart <服务名>" → 重启指定 MCP 服务进程
        MCP_RESTART,

        // MCP_LOGS: 用户输入 "/mcp logs <服务名>" → 输出该 MCP 服务的标准错误日志（stderr）
        MCP_LOGS,

        // MCP_DISABLE: 用户输入 "/mcp disable <服务名>" → 禁用指定 MCP 服务（不再注册到 Agent）
        MCP_DISABLE,

        // MCP_ENABLE: 用户输入 "/mcp enable <服务名>" → 重新启用已禁用的 MCP 服务
        MCP_ENABLE,

        // MCP_RESOURCES: 用户输入 "/mcp resources <服务名>" → 显示该 MCP 服务声明的 resources（能力资源）
        MCP_RESOURCES,

        // MCP_PROMPTS: 用户输入 "/mcp prompts <服务名>" → 显示该 MCP 服务的 system prompt 片段
        MCP_PROMPTS,

        // BROWSER:
        //   - 仅输入 "/browser" → 等价于 "/browser status"，查看浏览器会话状态；
        //   - 输入 "/browser <子命令>"（如 connect / disconnect / tabs）→ 执行对应浏览器控制操作
        BROWSER,

        // SKILL_LIST: 用户输入 "/skill" 或 "/skill list" → 列出所有已加载的技能（含启用/禁用状态）
        SKILL_LIST,

        // SKILL_SHOW: 用户输入 "/skill show <技能名>" → 显示该技能的完整 SKILL.md 内容
        SKILL_SHOW,

        // SKILL_ON: 用户输入 "/skill on <技能名>" → 启用指定技能（从禁用列表中移除）
        SKILL_ON,

        // SKILL_OFF: 用户输入 "/skill off <技能名>" → 禁用指定技能（加入禁用列表并持久化）
        SKILL_OFF,

        // SKILL_RELOAD: 用户输入 "/skill reload" → 重新扫描三层技能目录，热重载所有技能
        SKILL_RELOAD
    }

    record ParsedCommand(CommandType type, String payload) {
        static ParsedCommand none() {
            return new ParsedCommand(CommandType.NONE, null);
        }
    }

    private CliCommandParser() {
    }

    static ParsedCommand parse(String input) {
        if (input == null) {
            return ParsedCommand.none();
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return ParsedCommand.none();
        }

        if (trimmed.equalsIgnoreCase("/exit")
                || trimmed.equalsIgnoreCase("/quit")
                || trimmed.equalsIgnoreCase("exit")
                || trimmed.equalsIgnoreCase("quit")) {
            return new ParsedCommand(CommandType.EXIT, null);
        }

        if (trimmed.equalsIgnoreCase("/cancel") || trimmed.equalsIgnoreCase("cancel")) {
            return new ParsedCommand(CommandType.CANCEL, null);
        }

        if (trimmed.equalsIgnoreCase("/clear") || trimmed.equalsIgnoreCase("clear")) {
            return new ParsedCommand(CommandType.CLEAR, null);
        }

        if (trimmed.equalsIgnoreCase("/model")) {
            return new ParsedCommand(CommandType.SWITCH_MODEL, null);
        }

        if (trimmed.regionMatches(true, 0, "/model ", 0, 7)) {
            return new ParsedCommand(CommandType.SWITCH_MODEL, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/plan")) {
            return new ParsedCommand(CommandType.SWITCH_PLAN, null);
        }

        if (trimmed.regionMatches(true, 0, "/plan ", 0, 6)) {
            return new ParsedCommand(CommandType.SWITCH_PLAN, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/team")) {
            return new ParsedCommand(CommandType.SWITCH_TEAM, null);
        }

        if (trimmed.regionMatches(true, 0, "/team ", 0, 6)) {
            return new ParsedCommand(CommandType.SWITCH_TEAM, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/hitl on")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, "on");
        }

        if (trimmed.equalsIgnoreCase("/hitl off")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, "off");
        }

        if (trimmed.equalsIgnoreCase("/hitl")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, null);
        }

        if (trimmed.equalsIgnoreCase("/memory") || trimmed.equalsIgnoreCase("/mem")) {
            return new ParsedCommand(CommandType.MEMORY_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/memory clear") || trimmed.equalsIgnoreCase("/mem clear")) {
            return new ParsedCommand(CommandType.MEMORY_CLEAR, null);
        }

        if (trimmed.equalsIgnoreCase("/save")) {
            return new ParsedCommand(CommandType.MEMORY_SAVE, null);
        }

        if (trimmed.regionMatches(true, 0, "/save ", 0, 6)) {
            return new ParsedCommand(CommandType.MEMORY_SAVE, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/index")) {
            return new ParsedCommand(CommandType.INDEX_CODE, null);
        }

        if (trimmed.regionMatches(true, 0, "/index ", 0, 7)) {
            return new ParsedCommand(CommandType.INDEX_CODE, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/search")) {
            return new ParsedCommand(CommandType.SEARCH_CODE, null);
        }

        if (trimmed.regionMatches(true, 0, "/search ", 0, 8)) {
            return new ParsedCommand(CommandType.SEARCH_CODE, trimmed.substring(8).trim());
        }

        if (trimmed.equalsIgnoreCase("/graph")) {
            return new ParsedCommand(CommandType.GRAPH_QUERY, null);
        }

        if (trimmed.regionMatches(true, 0, "/graph ", 0, 7)) {
            return new ParsedCommand(CommandType.GRAPH_QUERY, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/context") || trimmed.equalsIgnoreCase("/ctx")) {
            return new ParsedCommand(CommandType.CONTEXT_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/policy")) {
            return new ParsedCommand(CommandType.POLICY_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/audit")) {
            return new ParsedCommand(CommandType.AUDIT_TAIL, null);
        }

        if (trimmed.regionMatches(true, 0, "/audit ", 0, 7)) {
            return new ParsedCommand(CommandType.AUDIT_TAIL, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/browser")) {
            return new ParsedCommand(CommandType.BROWSER, "status");
        }

        if (trimmed.regionMatches(true, 0, "/browser ", 0, 9)) {
            return new ParsedCommand(CommandType.BROWSER, trimmed.substring(9).trim());
        }

        if (trimmed.equalsIgnoreCase("/skill") || trimmed.equalsIgnoreCase("/skill list")) {
            return new ParsedCommand(CommandType.SKILL_LIST, null);
        }

        if (trimmed.equalsIgnoreCase("/skill reload")) {
            return new ParsedCommand(CommandType.SKILL_RELOAD, null);
        }

        if (trimmed.regionMatches(true, 0, "/skill show ", 0, 12)) {
            return new ParsedCommand(CommandType.SKILL_SHOW, trimmed.substring(12).trim());
        }

        if (trimmed.regionMatches(true, 0, "/skill on ", 0, 10)) {
            return new ParsedCommand(CommandType.SKILL_ON, trimmed.substring(10).trim());
        }

        if (trimmed.regionMatches(true, 0, "/skill off ", 0, 11)) {
            return new ParsedCommand(CommandType.SKILL_OFF, trimmed.substring(11).trim());
        }

        if (trimmed.equalsIgnoreCase("/mcp")) {
            return new ParsedCommand(CommandType.MCP_LIST, null);
        }

        if (trimmed.regionMatches(true, 0, "/mcp resources ", 0, 15)) {
            return new ParsedCommand(CommandType.MCP_RESOURCES, trimmed.substring(15).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp prompts ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_PROMPTS, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp restart ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_RESTART, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp logs ", 0, 10)) {
            return new ParsedCommand(CommandType.MCP_LOGS, trimmed.substring(10).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp disable ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_DISABLE, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp enable ", 0, 12)) {
            return new ParsedCommand(CommandType.MCP_ENABLE, trimmed.substring(12).trim());
        }

        if (trimmed.startsWith("/")) {
            return new ParsedCommand(CommandType.UNKNOWN_COMMAND, trimmed);
        }

        return ParsedCommand.none();
    }
}
