#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MindFlow MCP 外置工具服务（stdio，零第三方依赖）。

协议：MCP 2025-03-26，行分隔 JSON-RPC 2.0（严格对齐 MindFlow harness/mcp 的实现）：
  initialize -> notifications/initialized -> tools/list -> tools/call
  另实现 resources/list、resources/read、ping。

在 MindFlow「MCP 工具」页注册（仅管理员）：
  name      : demo-tools            （只允许字母数字下划线连字符，≤64）
  transport : stdio
  command   : python                （白名单：python / python3 / node / npx / pnpm / bunx / uvx）
  args      : <本文件的绝对路径>      （args 内不得含 && || ; | ` $( > ，且 python 禁止 -c）

注意：本文件是"外置不受控工具"的演示样例——read_text_file / list_directory / http_get
属于敏感能力。MindFlow 对 MCP 工具一律强制 HITL 审批 + 审计，所以它们不会被静默调用；
但生产环境请按需删改这些工具，不要直接暴露真实主机能力。

自检：python scripts/mcp-servers/smoke_test.py
"""

import ast
import datetime
import html
import json
import os
import platform
import re
import socket
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

PROTOCOL_VERSION = "2025-03-26"
SERVER_NAME = "demo-mcp-server"
SERVER_VERSION = "1.0.0"

MAX_OUTPUT_CHARS = 12000
MAX_WRITE_BYTES = 500000


def log(message):
    sys.stderr.write("[demo-mcp-server] " + str(message) + "\n")
    sys.stderr.flush()


def tool_get_current_time(args):
    tz_name = (args.get("timezone") or "").strip()
    if tz_name:
        try:
            from zoneinfo import ZoneInfo

            now = datetime.datetime.now(ZoneInfo(tz_name))
        except Exception as exc:
            return "时区不可用: %s（%s）。可用示例：Asia/Shanghai、UTC、America/New_York" % (tz_name, exc)
    else:
        now = datetime.datetime.now().astimezone()
    return "当前时间: %s\n时区: %s\nUnix 时间戳: %d" % (
        now.strftime("%Y-%m-%d %H:%M:%S"),
        now.tzname() or "local",
        int(now.timestamp()),
    )


_ALLOWED_BINOPS = {
    ast.Add: lambda a, b: a + b,
    ast.Sub: lambda a, b: a - b,
    ast.Mult: lambda a, b: a * b,
    ast.Div: lambda a, b: a / b,
    ast.FloorDiv: lambda a, b: a // b,
    ast.Mod: lambda a, b: a % b,
    ast.Pow: lambda a, b: a ** b,
}
_ALLOWED_UNARY = {ast.UAdd: lambda a: +a, ast.USub: lambda a: -a}
_MAX_POW_EXPONENT = 1000


def _eval_node(node):
    if isinstance(node, ast.Expression):
        return _eval_node(node.body)
    if isinstance(node, ast.Constant):
        if isinstance(node.value, bool) or not isinstance(node.value, (int, float)):
            raise ValueError("只支持数字常量")
        return node.value
    if isinstance(node, ast.BinOp):
        op = _ALLOWED_BINOPS.get(type(node.op))
        if op is None:
            raise ValueError("不支持的运算符: %s" % type(node.op).__name__)
        left = _eval_node(node.left)
        right = _eval_node(node.right)
        if isinstance(node.op, ast.Pow) and abs(right) > _MAX_POW_EXPONENT:
            raise ValueError("指数过大（上限 %d）" % _MAX_POW_EXPONENT)
        return op(left, right)
    if isinstance(node, ast.UnaryOp):
        op = _ALLOWED_UNARY.get(type(node.op))
        if op is None:
            raise ValueError("不支持的一元运算符")
        return op(_eval_node(node.operand))
    raise ValueError("表达式包含不允许的语法: %s" % type(node).__name__)


def tool_calculator(args):
    expression = (args.get("expression") or "").strip()
    if not expression:
        raise ValueError("expression 不能为空")
    if len(expression) > 200:
        raise ValueError("表达式过长（上限 200 字符）")
    tree = ast.parse(expression, mode="eval")
    value = _eval_node(tree)
    return "%s = %s" % (expression, value)


def tool_system_info(args):
    return "\n".join(
        [
            "hostname: %s" % socket.gethostname(),
            "platform: %s" % platform.platform(),
            "system/release: %s %s" % (platform.system(), platform.release()),
            "machine: %s" % platform.machine(),
            "cpu_count: %s" % (os.cpu_count() or "unknown"),
            "python: %s" % sys.version.split()[0],
            "cwd: %s" % os.getcwd(),
            "pid: %d" % os.getpid(),
        ]
    )


def tool_list_directory(args):
    path = (args.get("path") or ".").strip() or "."
    max_entries = int(args.get("max_entries") or 50)
    max_entries = max(1, min(max_entries, 500))
    if not os.path.exists(path):
        raise ValueError("路径不存在: %s" % path)
    if not os.path.isdir(path):
        raise ValueError("不是目录: %s" % path)
    names = sorted(os.listdir(path))
    lines = ["目录: %s" % os.path.abspath(path), "共 %d 项，显示前 %d 项:" % (len(names), max_entries)]
    for name in names[:max_entries]:
        full = os.path.join(path, name)
        if os.path.isdir(full):
            lines.append("  [dir ] %s" % name)
        else:
            try:
                size = os.path.getsize(full)
            except OSError:
                size = -1
            lines.append("  [file] %s  (%s bytes)" % (name, size))
    if len(names) > max_entries:
        lines.append("  ... 其余 %d 项已省略" % (len(names) - max_entries))
    return "\n".join(lines)


def tool_read_text_file(args):
    path = (args.get("path") or "").strip()
    if not path:
        raise ValueError("path 不能为空")
    max_bytes = int(args.get("max_bytes") or 8000)
    max_bytes = max(1, min(max_bytes, 200000))
    if not os.path.exists(path):
        raise ValueError("文件不存在: %s" % path)
    if os.path.isdir(path):
        raise ValueError("这是目录，不是文件: %s" % path)
    with open(path, "rb") as handle:
        raw = handle.read(max_bytes + 1)
    truncated = len(raw) > max_bytes
    raw = raw[:max_bytes]
    if b"\x00" in raw:
        return "文件疑似二进制内容，已拒绝解码展示（路径: %s，读取 %d 字节）" % (path, len(raw))
    text = raw.decode("utf-8", errors="replace")
    header = "文件: %s\n读取 %d 字节%s\n---\n" % (
        os.path.abspath(path),
        len(raw),
        "（已截断）" if truncated else "",
    )
    return header + text


def tool_http_get(args):
    url = (args.get("url") or "").strip()
    if not url:
        raise ValueError("url 不能为空")
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise ValueError("只允许 http/https，收到: %s" % parsed.scheme)
    timeout_seconds = int(args.get("timeout_seconds") or 10)
    timeout_seconds = max(1, min(timeout_seconds, 60))
    max_chars = int(args.get("max_chars") or 4000)
    max_chars = max(100, min(max_chars, 20000))
    request = urllib.request.Request(url, headers={"User-Agent": "MindFlow-Demo-MCP/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            status = response.status
            content_type = response.headers.get("Content-Type", "unknown")
            body = response.read(max_chars + 1)
    except urllib.error.HTTPError as exc:
        return "HTTP 请求失败: %s %s" % (exc.code, exc.reason)
    except Exception as exc:
        return "HTTP 请求异常: %s" % exc
    text = body.decode("utf-8", errors="replace")
    truncated = len(text) > max_chars
    return "GET %s\nstatus: %s\ncontent-type: %s\n---\n%s%s" % (
        url,
        status,
        content_type,
        text[:max_chars],
        "\n...（已截断）" if truncated else "",
    )


def tool_echo(args):
    return "echo: %s" % (args.get("message") or "")


def tool_write_file(args):
    path = (args.get("path") or "").strip()
    if not path:
        raise ValueError("path 不能为空")
    if args.get("content") is None:
        raise ValueError("content 不能为空（写空文件请显式传空字符串）")
    content = str(args.get("content"))
    size = len(content.encode("utf-8"))
    if size > MAX_WRITE_BYTES:
        raise ValueError("内容过大：%d 字节，上限 %d" % (size, MAX_WRITE_BYTES))
    existed = os.path.exists(path)
    parent = os.path.dirname(os.path.abspath(path))
    if not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(content)
    return "%s: %s\n大小: %d 字节 / %d 行" % (
        "已覆盖" if existed else "已创建",
        os.path.abspath(path),
        size,
        content.count("\n") + (1 if content else 0),
    )


def tool_edit_file(args):
    path = (args.get("path") or "").strip()
    if not path:
        raise ValueError("path 不能为空")
    old_string = args.get("old_string")
    if not old_string:
        raise ValueError("old_string 不能为空：块级编辑必须给出要被替换的原文片段")
    new_string = args.get("new_string")
    new_string = "" if new_string is None else str(new_string)
    replace_all = bool(args.get("replace_all") or False)
    if not os.path.exists(path):
        raise ValueError("文件不存在: %s" % path)
    if os.path.isdir(path):
        raise ValueError("这是目录，不是文件: %s" % path)

    with open(path, "r", encoding="utf-8", errors="replace", newline="") as handle:
        original = handle.read()

    occurrences = original.count(old_string)
    if occurrences == 0:
        raise ValueError(
            "未找到 old_string（必须与文件内容逐字符一致，含缩进与换行）。文件: %s" % path
        )
    if occurrences > 1 and not replace_all:
        raise ValueError(
            "old_string 在文件中出现 %d 次，不唯一；请给出更长的上下文使其唯一，或显式传 replace_all=true" % occurrences
        )

    updated = original.replace(old_string, new_string) if replace_all else original.replace(old_string, new_string, 1)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(updated)

    replaced = occurrences if replace_all else 1
    return "已编辑: %s\n替换: %d 处\n行数变化: %+d\n字节: %d → %d" % (
        os.path.abspath(path),
        replaced,
        updated.count("\n") - original.count("\n"),
        len(original.encode("utf-8")),
        len(updated.encode("utf-8")),
    )


def tool_bash(args):
    command = (args.get("command") or "").strip()
    if not command:
        raise ValueError("command 不能为空")
    timeout_seconds = int(args.get("timeout_seconds") or 30)
    timeout_seconds = max(1, min(timeout_seconds, 300))
    cwd = (args.get("cwd") or "").strip() or None
    if cwd and not os.path.isdir(cwd):
        raise ValueError("cwd 不存在: %s" % cwd)

    try:
        completed = subprocess.run(
            command,
            shell=True,
            cwd=cwd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired:
        return "命令超时（%ds）已终止: %s" % (timeout_seconds, command)

    stdout = (completed.stdout or "").strip()
    stderr = (completed.stderr or "").strip()
    if len(stdout) > MAX_OUTPUT_CHARS:
        stdout = stdout[:MAX_OUTPUT_CHARS] + "\n...（stdout 已截断）"
    if len(stderr) > 4000:
        stderr = stderr[:4000] + "\n...（stderr 已截断）"
    return "命令: %s\n退出码: %d\n--- stdout ---\n%s\n--- stderr ---\n%s" % (
        command,
        completed.returncode,
        stdout or "(空)",
        stderr or "(空)",
    )


def tool_web_fetch(args):
    url = (args.get("url") or "").strip()
    if not url:
        raise ValueError("url 不能为空")
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise ValueError("只允许 http/https，收到: %s" % parsed.scheme)
    timeout_seconds = int(args.get("timeout_seconds") or 15)
    timeout_seconds = max(1, min(timeout_seconds, 60))
    max_chars = int(args.get("max_chars") or 6000)
    max_chars = max(200, min(max_chars, 30000))
    strip_html = bool(args.get("strip_html", True))

    request = urllib.request.Request(url, headers={"User-Agent": "MindFlow-Demo-MCP/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            status = response.status
            content_type = response.headers.get("Content-Type", "unknown")
            body = response.read(max_chars * 4 + 1)
    except urllib.error.HTTPError as exc:
        return "抓取失败: %s %s" % (exc.code, exc.reason)
    except Exception as exc:
        return "抓取异常: %s" % exc

    text = body.decode("utf-8", errors="replace")
    if strip_html and "html" in content_type.lower():
        text = re.sub(r"(?is)<(script|style)[^>]*>.*?</\1>", " ", text)
        text = re.sub(r"(?s)<[^>]+>", " ", text)
        text = re.sub(r"[ \t\r\f\v]+", " ", text)
        text = re.sub(r"\n\s*\n\s*\n+", "\n\n", text)
        text = html.unescape(text).strip()
    truncated = len(text) > max_chars
    return "URL: %s\nstatus: %s\ncontent-type: %s\n---\n%s%s" % (
        url,
        status,
        content_type,
        text[:max_chars],
        "\n...（已截断）" if truncated else "",
    )


# name -> (描述, inputSchema, 处理函数)
TOOLS = {
    "echo": (
        "连通性自检工具：原样回显输入内容。用于确认 MCP 链路、HITL 审批弹窗与审计入库是否正常。",
        {
            "type": "object",
            "properties": {
                "message": {"type": "string", "description": "要回显的文本，例如 hello"},
            },
            "required": ["message"],
        },
        tool_echo,
    ),
    "get_current_time": (
        "查询当前日期时间，可指定 IANA 时区（如 Asia/Shanghai）。只读、无副作用。",
        {
            "type": "object",
            "properties": {
                "timezone": {"type": "string", "description": "IANA 时区名，例如 Asia/Shanghai；留空使用本机时区"},
            },
            "required": [],
        },
        tool_get_current_time,
    ),
    "calculator": (
        "计算数学表达式，支持 + - * / // % ** 与括号，例如 (1+2)*3、2**10、7%3。不使用 eval，按 AST 白名单求值。只读、无副作用。",
        {
            "type": "object",
            "properties": {
                "expression": {"type": "string", "description": "数学表达式，例如 (1+2)*3/4"},
            },
            "required": ["expression"],
        },
        tool_calculator,
    ),
    "system_info": (
        "返回运行本 MCP server 的主机概要信息（主机名、操作系统、Python 版本、CPU 数、工作目录、进程号）。只读。",
        {
            "type": "object",
            "properties": {},
            "required": [],
        },
        tool_system_info,
    ),
    "list_directory": (
        "⚠️ 敏感：列出服务器主机上指定目录的文件与子目录（默认当前工作目录）。会暴露主机文件结构，调用前应确认用户意图。",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "目录路径，默认 .（MCP server 的工作目录）"},
                "max_entries": {"type": "integer", "description": "最多返回条数，默认 50，上限 500"},
            },
            "required": [],
        },
        tool_list_directory,
    ),
    "read_text_file": (
        "⚠️ 敏感：读取服务器主机上的文本文件内容（默认最多 8000 字节，二进制会被拒绝）。会暴露主机文件内容，调用前应确认用户意图。",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "文件路径"},
                "max_bytes": {"type": "integer", "description": "最多读取字节数，默认 8000，上限 200000"},
            },
            "required": ["path"],
        },
        tool_read_text_file,
    ),
    "http_get": (
        "⚠️ 敏感：向外部 URL 发起 HTTP GET 请求并返回响应正文（默认最多 4000 字符，仅允许 http/https）。会产生外部网络访问。",
        {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "目标 URL，必须 http:// 或 https://"},
                "timeout_seconds": {"type": "integer", "description": "超时秒数，默认 10，上限 60"},
                "max_chars": {"type": "integer", "description": "最多返回字符数，默认 4000，上限 20000"},
            },
            "required": ["url"],
        },
        tool_http_get,
    ),
    "write_file": (
        "⚠️ 破坏性：写入新文件或覆盖现有文件（自动创建父目录，单次上限 500KB）。会修改主机文件系统，"
        "覆盖前请确认目标路径与内容。需要新增文件时优先用它。",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "目标文件路径；相对路径基于 MCP server 工作目录"},
                "content": {"type": "string", "description": "完整文件内容（覆盖写入）"},
            },
            "required": ["path", "content"],
        },
        tool_write_file,
    ),
    "edit_file": (
        "⚠️ 破坏性：块级精准编辑。给出 old_string（必须与文件内原文逐字符一致，含缩进换行）与 new_string，"
        "只替换该片段，避免重写整个文件——显著省 Token 且降低误改风险。old_string 不唯一时须传 replace_all=true。",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "目标文件路径"},
                "old_string": {"type": "string", "description": "要被替换的原文片段（必须唯一且完全一致）"},
                "new_string": {"type": "string", "description": "替换后的新内容；传空字符串表示删除该片段"},
                "replace_all": {"type": "boolean", "description": "old_string 出现多次时是否全部替换，默认 false"},
            },
            "required": ["path", "old_string", "new_string"],
        },
        tool_edit_file,
    ),
    "bash": (
        "⚠️ 最高风险：在主机上执行任意命令行（git / npm / python / curl 等），拥有与 MCP server 进程等同的权限。"
        "会改变主机状态、可能产生网络与数据副作用，执行前必须由用户确认。",
        {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "完整命令行，例如 git status --short"},
                "timeout_seconds": {"type": "integer", "description": "超时秒数，默认 30，上限 300"},
                "cwd": {"type": "string", "description": "工作目录，默认 MCP server 工作目录"},
            },
            "required": ["command"],
        },
        tool_bash,
    ),
    "web_fetch": (
        "⚠️ 敏感：抓取网页内容用于查阅文档 / API 参考 / Issue（默认把 HTML 转成纯文本并截断到 6000 字符）。"
        "会产生外部网络访问；仅支持 http/https 静态内容，不执行页面 JS。",
        {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "目标 URL，必须 http:// 或 https://"},
                "timeout_seconds": {"type": "integer", "description": "超时秒数，默认 15，上限 60"},
                "max_chars": {"type": "integer", "description": "最多返回字符数，默认 6000，上限 30000"},
                "strip_html": {"type": "boolean", "description": "是否把 HTML 转为纯文本，默认 true"},
            },
            "required": ["url"],
        },
        tool_web_fetch,
    ),
}

RESOURCES = {
    "demo://readme": (
        "demo-mcp-server 使用说明",
        "text/markdown",
        "\n".join(
            [
                "# demo-mcp-server",
                "",
                "MindFlow 的外置 MCP 工具演示服务，零第三方依赖，stdio 传输。",
                "",
                "## 工具清单",
                "",
                "- `echo`：连通性自检",
                "- `get_current_time`：时间查询",
                "- `calculator`：AST 白名单表达式求值",
                "- `system_info`：主机概要",
                "- `list_directory` / `read_text_file`：⚠️ 敏感，读取主机文件",
                "- `edit_file`：块级精准编辑（search_replace，省 Token）",
                "- `write_file`：⚠️ 破坏性，写入/覆盖文件",
                "- `bash`：⚠️ 最高风险，执行任意命令（需用户确认）",
                "- `web_fetch` / `http_get`：⚠️ 网络外访，抓取网页 / API",
                "",
                "MindFlow 对 MCP 工具一律强制审批 + 审计，不会静默执行。",
            ]
        ),
    ),
    "demo://server-info": (
        "demo-mcp-server 运行信息",
        "text/plain",
        "server=%s version=%s protocol=%s python=%s cwd=%s"
        % (SERVER_NAME, SERVER_VERSION, PROTOCOL_VERSION, sys.version.split()[0], os.getcwd()),
    ),
}


def send(payload):
    sys.stdout.write(json.dumps(payload, ensure_ascii=False))
    sys.stdout.write("\n")
    sys.stdout.flush()


def send_result(request_id, result):
    send({"jsonrpc": "2.0", "id": request_id, "result": result})


def send_error(request_id, code, message):
    send({"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}})


def tool_definitions():
    definitions = []
    for name, (description, schema, _handler) in TOOLS.items():
        definitions.append({"name": name, "description": description, "inputSchema": schema})
    return definitions


def handle_initialize(request_id):
    send_result(
        request_id,
        {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {
                "tools": {"listChanged": False},
                "resources": {"subscribe": False, "listChanged": False},
            },
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
        },
    )


def handle_tools_list(request_id):
    send_result(request_id, {"tools": tool_definitions()})


def handle_tools_call(request_id, params):
    name = (params or {}).get("name") or ""
    arguments = (params or {}).get("arguments") or {}
    if not isinstance(arguments, dict):
        send_error(request_id, -32602, "tools/call 的 arguments 必须是对象")
        return
    entry = TOOLS.get(name)
    if entry is None:
        send_error(request_id, -32602, "未知工具: %s" % name)
        return
    _description, _schema, handler = entry
    try:
        text = handler(arguments)
        payload = {"content": [{"type": "text", "text": str(text)[:MAX_OUTPUT_CHARS]}], "isError": False}
    except Exception as exc:
        log("tool %s failed: %s" % (name, exc))
        payload = {"content": [{"type": "text", "text": "工具执行失败: %s" % exc}], "isError": True}
    send_result(request_id, payload)


def handle_resources_list(request_id):
    items = []
    for uri, (name, mime_type, _text) in RESOURCES.items():
        items.append({"uri": uri, "name": name, "description": name, "mimeType": mime_type})
    send_result(request_id, {"resources": items})


def handle_resources_read(request_id, params):
    uri = (params or {}).get("uri") or ""
    entry = RESOURCES.get(uri)
    if entry is None:
        send_error(request_id, -32602, "未知 resource: %s" % uri)
        return
    _name, mime_type, text = entry
    send_result(request_id, {"contents": [{"uri": uri, "mimeType": mime_type, "text": text}]})


def dispatch(message):
    request_id = message.get("id")
    method = message.get("method") or ""
    params = message.get("params")

    if request_id is None:
        if method == "notifications/initialized":
            log("client initialized")
        return

    if method == "initialize":
        handle_initialize(request_id)
    elif method == "tools/list":
        handle_tools_list(request_id)
    elif method == "tools/call":
        handle_tools_call(request_id, params)
    elif method == "resources/list":
        handle_resources_list(request_id)
    elif method == "resources/read":
        handle_resources_read(request_id, params)
    elif method == "ping":
        send_result(request_id, {})
    else:
        send_error(request_id, -32601, "Method not found: %s" % method)


def main():
    try:
        sys.stdin.reconfigure(encoding="utf-8", errors="replace")
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception as exc:
        log("stream reconfigure skipped: %s" % exc)
    log("started (protocol=%s, tools=%d, resources=%d)" % (PROTOCOL_VERSION, len(TOOLS), len(RESOURCES)))
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError as exc:
            log("invalid json ignored: %s" % exc)
            continue
        try:
            dispatch(message)
        except Exception as exc:
            log("dispatch failed: %s" % exc)
            request_id = message.get("id")
            if request_id is not None:
                send_error(request_id, -32603, "内部错误: %s" % exc)
    log("stdin closed, exiting")


if __name__ == "__main__":
    main()
