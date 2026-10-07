#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
demo_mcp_server.py 自检脚本：拉起子进程走完整 MCP 握手，校验 tools/list、各工具调用、
resource 读取、未知方法/异常工具的错误路径。全绿才返回 0。

运行：python scripts/mcp-servers/smoke_test.py
"""

import json
import os
import queue
import subprocess
import sys
import tempfile
import threading

TIMEOUT_SECONDS = 20
EXPECTED_TOOLS = {
    "echo",
    "get_current_time",
    "calculator",
    "system_info",
    "list_directory",
    "read_text_file",
    "http_get",
    "write_file",
    "edit_file",
    "bash",
    "web_fetch",
}
SERVER_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "demo_mcp_server.py")


class McpStdioClient:
    def __init__(self):
        self.process = subprocess.Popen(
            [sys.executable, SERVER_PATH],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            bufsize=1,
        )
        self.responses = queue.Queue()
        self.request_id = 0
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()
        self.stderr_lines = []

    def _read_stdout(self):
        for line in self.process.stdout:
            self.responses.put(line)

    def _read_stderr(self):
        for line in self.process.stderr:
            self.stderr_lines.append(line.rstrip())

    def notify(self, method, params=None):
        message = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            message["params"] = params
        self.process.stdin.write(json.dumps(message, ensure_ascii=False) + "\n")
        self.process.stdin.flush()

    def call(self, method, params=None):
        self.request_id += 1
        message = {"jsonrpc": "2.0", "id": self.request_id, "method": method}
        if params is not None:
            message["params"] = params
        self.process.stdin.write(json.dumps(message, ensure_ascii=False) + "\n")
        self.process.stdin.flush()
        while True:
            line = self.responses.get(timeout=TIMEOUT_SECONDS)
            payload = json.loads(line)
            if payload.get("id") == self.request_id:
                return payload

    def close(self):
        try:
            self.process.stdin.close()
        except Exception:
            pass
        try:
            self.process.wait(timeout=5)
        except Exception:
            self.process.kill()


def tool_text(response):
    return "".join(item.get("text", "") for item in response.get("result", {}).get("content", []))


def main():
    checks = []

    def check(label, condition, detail=""):
        checks.append((label, bool(condition), detail))
        status = "PASS" if condition else "FAIL"
        suffix = "" if condition else "  <- %s" % detail
        print("[%s] %s%s" % (status, label, suffix))

    client = McpStdioClient()
    try:
        init = client.call(
            "initialize",
            {
                "protocolVersion": "2025-03-26",
                "capabilities": {"tools": {}},
                "clientInfo": {"name": "mindflow-smoke", "version": "1.0.0"},
            },
        )
        result = init.get("result", {})
        check("initialize 返回协议版本 2025-03-26", result.get("protocolVersion") == "2025-03-26", str(result))
        check("initialize 返回 serverInfo.name", bool(result.get("serverInfo", {}).get("name")), str(result))
        check("initialize 声明 tools capability", "tools" in result.get("capabilities", {}), str(result))

        client.notify("notifications/initialized", {})

        tools = client.call("tools/list")
        names = {tool.get("name") for tool in tools.get("result", {}).get("tools", [])}
        check("tools/list 返回全部预期工具", names == EXPECTED_TOOLS, "got=%s" % sorted(names))
        schemas_ok = all(
            tool.get("inputSchema", {}).get("type") == "object" and "properties" in tool.get("inputSchema", {})
            for tool in tools.get("result", {}).get("tools", [])
        )
        check("每个工具都有 object inputSchema + properties", schemas_ok)

        echo = client.call("tools/call", {"name": "echo", "arguments": {"message": "hello"}})
        check("echo 回显正确", "echo: hello" in tool_text(echo), tool_text(echo))

        calc = client.call("tools/call", {"name": "calculator", "arguments": {"expression": "(1+2)*3"}})
        check("calculator (1+2)*3 = 9", tool_text(calc).endswith("= 9"), tool_text(calc))

        clock = client.call("tools/call", {"name": "get_current_time", "arguments": {"timezone": "Asia/Shanghai"}})
        check("get_current_time 返回时间", "当前时间" in tool_text(clock), tool_text(clock))

        info = client.call("tools/call", {"name": "system_info", "arguments": {}})
        check("system_info 返回 python 版本", "python:" in tool_text(info), tool_text(info))

        listing = client.call("tools/call", {"name": "list_directory", "arguments": {"path": ".", "max_entries": 5}})
        check("list_directory 返回目录内容", "目录:" in tool_text(listing), tool_text(listing))

        workspace = tempfile.mkdtemp(prefix="demo-mcp-smoke-")
        target = os.path.join(workspace, "note.txt")

        wrote = client.call(
            "tools/call",
            {"name": "write_file", "arguments": {"path": target, "content": "alpha\nbeta\ngamma\n"}},
        )
        check("write_file 创建文件", "已创建" in tool_text(wrote) and os.path.exists(target), tool_text(wrote))

        edited = client.call(
            "tools/call",
            {"name": "edit_file", "arguments": {"path": target, "old_string": "beta", "new_string": "BETA"}},
        )
        check(
            "edit_file 精准替换单处",
            "替换: 1 处" in tool_text(edited) and open(target, encoding="utf-8").read() == "alpha\nBETA\ngamma\n",
            tool_text(edited),
        )

        not_found = client.call(
            "tools/call",
            {"name": "edit_file", "arguments": {"path": target, "old_string": "no-such-block", "new_string": "x"}},
        )
        check("edit_file 未命中走 isError", not_found.get("result", {}).get("isError") is True, str(not_found))

        duplicate = client.call(
            "tools/call",
            {"name": "write_file", "arguments": {"path": target, "content": "dup\ndup\n"}},
        )
        ambiguous = client.call(
            "tools/call",
            {"name": "edit_file", "arguments": {"path": target, "old_string": "dup", "new_string": "one"}},
        )
        check(
            "edit_file 多处命中要求 replace_all",
            duplicate.get("result", {}).get("isError") is not True
            and ambiguous.get("result", {}).get("isError") is True,
            str(ambiguous),
        )

        ran = client.call("tools/call", {"name": "bash", "arguments": {"command": "echo mindflow-smoke"}})
        check("bash 执行命令并回传 stdout", "mindflow-smoke" in tool_text(ran), tool_text(ran))

        bad_scheme = client.call(
            "tools/call", {"name": "web_fetch", "arguments": {"url": "ftp://example.com"}}
        )
        check("web_fetch 拒绝非 http(s)", bad_scheme.get("result", {}).get("isError") is True, str(bad_scheme))

        resources = client.call("resources/list")
        uris = {item.get("uri") for item in resources.get("result", {}).get("resources", [])}
        check("resources/list 含 demo://readme", "demo://readme" in uris, str(uris))

        readme = client.call("resources/read", {"uri": "demo://readme"})
        contents = readme.get("result", {}).get("contents", [])
        check("resources/read 返回正文", bool(contents) and "# demo-mcp-server" in contents[0].get("text", ""))

        unknown = client.call("tools/call", {"name": "no_such_tool", "arguments": {}})
        check("未知工具返回 -32602", unknown.get("error", {}).get("code") == -32602, str(unknown))

        bad = client.call("tools/call", {"name": "calculator", "arguments": {"expression": "1/0"}})
        check("除零错误走 isError 通道", bad.get("result", {}).get("isError") is True, str(bad))

        missing = client.call("no/such/method")
        check("未知方法返回 -32601", missing.get("error", {}).get("code") == -32601, str(missing))

        ping = client.call("ping")
        check("ping 返回空对象", ping.get("result") == {}, str(ping))
    finally:
        client.close()

    failed = [item for item in checks if not item[1]]
    print("")
    print("stderr tail: %s" % (client.stderr_lines[-3:] if client.stderr_lines else "(empty)"))
    print("total=%d passed=%d failed=%d" % (len(checks), len(checks) - len(failed), len(failed)))
    if failed:
        print("RESULT: FAIL")
        return 1
    print("RESULT: ALL GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
