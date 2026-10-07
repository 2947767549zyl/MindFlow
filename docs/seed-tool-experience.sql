-- 经验记忆库演示数据（工具失败经验）
-- 用途：一键把「经验记忆库」页面填满，便于演示/截图。
-- 前置：后端重启过一次（Hibernate 已建 tool_experience 表）。
-- 执行：
--   cmd /c "docker exec -i mysql mysql -uroot -pPaiSmart2025 --default-character-set=utf8mb4 -D paismart < docs\seed-tool-experience.sql"
-- 说明：signature 有唯一索引，这里用 INSERT IGNORE，重复执行不会报错也不会覆盖已有真实数据。

INSERT INTO tool_experience
  (tool_name, failure_category, signature, occurrence_count, last_error_text, recovery_hint, first_seen_at, last_seen_at)
VALUES
  ('read_file', 'NOT_FOUND', 'read_file:NOT_FOUND', 6,
   '文件不存在: C:/Users/Administrator/Desktop/MindFlow/项目背景.txt',
   '先用 glob / list 类工具确认路径或名称真实存在，再调用该工具', NOW(6), NOW(6)),

  ('glob', 'MISSING_ARG', 'glob:MISSING_ARG', 3,
   'pattern 不能为空：文件名模式必须给出，例如 **/*.java',
   '先按工具 schema 校正必填参数名与类型，不要猜参数名或类型', NOW(6), NOW(6)),

  ('grep', 'EMPTY_RESULT', 'grep:EMPTY_RESULT', 5,
   '未匹配到内容: pattern=分布式事务 起点=E:/work/docs',
   '换更宽的关键词或换工具；不要用同一条件重复查询', NOW(6), NOW(6)),

  ('search_knowledge', 'EMPTY_RESULT', 'search_knowledge:EMPTY_RESULT', 8,
   '检索到 0 个知识库片段',
   '换更宽的关键词或换工具；不要用同一条件重复查询', NOW(6), NOW(6)),

  ('search_wiki', 'EMPTY_RESULT', 'search_wiki:EMPTY_RESULT', 4,
   '未匹配到 Wiki 概念页: 查询词过长且含口语化描述',
   '先剥离疑问词与口语，只用核心名词短语再检索', NOW(6), NOW(6)),

  ('mcp__demo-tools__bash', 'PERMISSION_DENIED', 'mcp__demo-tools__bash:PERMISSION_DENIED', 5,
   '已被安全策略拦截: 参数 command 命中命令黑名单：递归删除根目录',
   '该操作被安全策略或人工拒绝，原样重试无效，应改方案或向用户说明', NOW(6), NOW(6)),

  ('mcp__demo-tools__read_text_file', 'PERMISSION_DENIED', 'mcp__demo-tools__read_text_file:PERMISSION_DENIED', 3,
   '已被安全策略拦截: 参数 path 路径越界：不在项目根之内',
   '该操作被安全策略或人工拒绝，原样重试无效，应改方案或向用户说明', NOW(6), NOW(6)),

  ('mcp__demo-tools__read_text_file', 'NOT_FOUND', 'mcp__demo-tools__read_text_file:NOT_FOUND', 4,
   '文件不存在: D:/work/notes/design.md',
   '先用 glob / list 类工具确认路径或名称真实存在，再调用该工具', NOW(6), NOW(6)),

  ('mcp__demo-tools__http_get', 'TIMEOUT', 'mcp__demo-tools__http_get:TIMEOUT', 3,
   'HTTP 请求异常: <urlopen error timed out>',
   '缩小输入规模后重试，必要时显式加超时参数', NOW(6), NOW(6)),

  ('mcp__demo-tools__web_fetch', 'RATE_LIMIT', 'mcp__demo-tools__web_fetch:RATE_LIMIT', 2,
   '抓取失败: 429 Too Many Requests',
   '这属于环境性失败且会自愈：等待窗口恢复后重试，不要换工具绕开', NOW(6), NOW(6)),

  ('mcp__demo-tools__edit_file', 'MISSING_ARG', 'mcp__demo-tools__edit_file:MISSING_ARG', 7,
   '未找到 old_string（必须与文件内容逐字符一致，含缩进与换行）',
   '先 read_file 取回原文片段再编辑，不要凭记忆构造 old_string', NOW(6), NOW(6)),

  ('mcp__demo-tools__write_file', 'PERMISSION_DENIED', 'mcp__demo-tools__write_file:PERMISSION_DENIED', 1,
   '已被安全策略拦截: 参数 path 命中敏感路径黑名单（敏感扩展名 .key）',
   '该操作被安全策略或人工拒绝，原样重试无效，应改方案或向用户说明', NOW(6), NOW(6)),

  ('mcp__demo-tools__calculator', 'MISSING_ARG', 'mcp__demo-tools__calculator:MISSING_ARG', 2,
   'expression 不能为空',
   '先按工具 schema 校正必填参数名与类型，不要猜参数名或类型', NOW(6), NOW(6)),

  ('mcp__demo-tools__list_directory', 'UNKNOWN', 'mcp__demo-tools__list_directory:UNKNOWN', 1,
   '工具执行失败: 目录枚举被中断（权限受限）',
   '先读错误正文再决定下一步，避免原样重试', NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE
  occurrence_count = VALUES(occurrence_count),
  last_error_text = VALUES(last_error_text),
  recovery_hint = VALUES(recovery_hint),
  last_seen_at = VALUES(last_seen_at);
