package com.mindflow.module.chat.agent;

import com.mindflow.harness.policy.ToolPolicyGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置只读文件工具：read_file / grep / glob，用于让 Agent 理解项目结构与定位代码。
 *
 * 与 MCP 外置工具的区别是它们免审批（只读、无副作用），但**仍然受 L1 路径围栏约束**：
 * 统一走 {@link ToolPolicyGateway#resolvePath} 复用同一套根目录判定，避免出现"内置工具绕过围栏"的窟窿。
 */
@Component
public class LocalFileTools {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileTools.class);

    private static final Set<String> TOOL_NAMES = Set.of("read_file", "grep", "glob");

    /** 遍历时跳过的目录：版本库、依赖、构建产物与运行期数据，避免噪音与超大遍历 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", "node_modules", "target", "dist", "build", "logs", "__pycache__", ".venv");

    private static final long MAX_SCAN_BYTES = 1_000_000L;
    private static final int MAX_GLOB_RESULTS = 200;
    private static final int MAX_GREP_RESULTS = 100;
    private static final int MAX_READ_LINES = 2000;

    private final ToolPolicyGateway policyGateway;

    public LocalFileTools(ToolPolicyGateway policyGateway) {
        this.policyGateway = policyGateway;
    }

    public boolean handles(String name) {
        return name != null && TOOL_NAMES.contains(name);
    }

    public List<AgentToolRegistry.AgentTool> definitions() {
        return List.of(readFileTool(), grepTool(), globTool());
    }

    public AgentToolRegistry.ToolExecutionResult execute(String name, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        return switch (name) {
            case "read_file" -> readFile(args);
            case "grep" -> grep(args);
            case "glob" -> glob(args);
            default -> fail(name, "未知的本地文件工具: " + name);
        };
    }

    private AgentToolRegistry.AgentTool readFileTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", stringProp("文件路径；相对路径基于项目根"));
        properties.put("offset", intProp("起始行号，从 1 开始，默认 1"));
        properties.put("limit", intProp("最多返回行数，默认 200，上限 2000"));
        return new AgentToolRegistry.AgentTool(
                "read_file",
                "读取项目内文本文件的内容（带行号），用于查看源码、配置与文档。只读、免审批；路径必须位于允许的项目根内。",
                objectSchema(properties, List.of("path")));
    }

    private AgentToolRegistry.AgentTool grepTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", stringProp("正则表达式，例如 class\\s+\\w+Service"));
        properties.put("path", stringProp("搜索起始目录，默认项目根"));
        properties.put("file_glob", stringProp("只搜索匹配的文件名模式，例如 *.java"));
        properties.put("case_insensitive", boolProp("是否忽略大小写，默认 false"));
        properties.put("max_results", intProp("最多返回匹配行数，默认 50，上限 100"));
        return new AgentToolRegistry.AgentTool(
                "grep",
                "按正则搜索项目内文件内容，返回 `文件:行号: 内容`。用于定位符号定义、配置项、调用点等。只读、免审批。",
                objectSchema(properties, List.of("pattern")));
    }

    private AgentToolRegistry.AgentTool globTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", stringProp("文件名 glob 模式，例如 **/*.java、src/**/application*.yml"));
        properties.put("path", stringProp("搜索起始目录，默认项目根"));
        properties.put("max_results", intProp("最多返回条数，默认 100，上限 200"));
        return new AgentToolRegistry.AgentTool(
                "glob",
                "按文件名模式匹配项目内的文件与目录，用于快速摸清结构（如找出所有配置文件、所有 Controller）。只读、免审批。",
                objectSchema(properties, List.of("pattern")));
    }

    private AgentToolRegistry.ToolExecutionResult readFile(Map<String, Object> args) {
        String rawPath = stringArg(args, "path");
        if (rawPath.isBlank()) {
            return fail("read_file", "path 不能为空");
        }
        Path target;
        try {
            target = policyGateway.resolvePath(rawPath);
        } catch (RuntimeException e) {
            return fail("read_file", e.getMessage());
        }
        if (!Files.exists(target)) {
            return fail("read_file", "文件不存在: " + target);
        }
        if (Files.isDirectory(target)) {
            return fail("read_file", "这是目录，请用 glob 列文件或 grep 搜索内容: " + target);
        }
        if (isBinary(target)) {
            return fail("read_file", "文件疑似二进制，已拒绝展示: " + target);
        }

        int offset = intArg(args, "offset", 1, 1, 1_000_000);
        int limit = intArg(args, "limit", 200, 1, MAX_READ_LINES);
        try {
            List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return new AgentToolRegistry.ToolExecutionResult("read_file", true,
                        "文件为空: " + target, Map.of("path", target.toString(), "lines", 0));
            }
            int from = Math.min(offset - 1, lines.size());
            int to = Math.min(from + limit, lines.size());
            StringBuilder content = new StringBuilder();
            content.append("文件: ").append(target).append('\n')
                    .append("行数: ").append(lines.size()).append(" / 展示 ").append(from + 1).append('-').append(to).append('\n')
                    .append("---\n");
            for (int i = from; i < to; i++) {
                content.append(String.format("%6d| %s%n", i + 1, lines.get(i)));
            }
            if (to < lines.size()) {
                content.append("... 其余 ").append(lines.size() - to).append(" 行已省略（可用 offset/limit 继续读取）");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", target.toString());
            data.put("lines", lines.size());
            data.put("from", from + 1);
            data.put("to", to);
            return new AgentToolRegistry.ToolExecutionResult("read_file", true, content.toString().trim(), data);
        } catch (Exception e) {
            logger.warn("read_file 失败: {}", e.getMessage());
            return fail("read_file", "读取失败: " + e.getMessage());
        }
    }

    private AgentToolRegistry.ToolExecutionResult grep(Map<String, Object> args) {
        String expression = stringArg(args, "pattern");
        if (expression.isBlank()) {
            return fail("grep", "pattern 不能为空");
        }
        Pattern pattern;
        try {
            int flags = boolArg(args, "case_insensitive") ? Pattern.CASE_INSENSITIVE : 0;
            pattern = Pattern.compile(expression, flags);
        } catch (Exception e) {
            return fail("grep", "正则表达式无效: " + e.getMessage());
        }
        Path base;
        try {
            base = policyGateway.resolvePath(stringArg(args, "path").isBlank() ? "." : stringArg(args, "path"));
        } catch (RuntimeException e) {
            return fail("grep", e.getMessage());
        }
        if (!Files.isDirectory(base)) {
            return fail("grep", "搜索起点不是目录: " + base);
        }

        PathMatcher fileMatcher = compileGlobMatcher(stringArg(args, "file_glob"));
        int maxResults = intArg(args, "max_results", 50, 1, MAX_GREP_RESULTS);
        List<String> matches = new ArrayList<>();
        boolean[] truncated = {false};

        walkFiles(base, file -> {
            if (truncated[0] || matches.size() >= maxResults) {
                return;
            }
            if (fileMatcher != null && !fileMatcher.matches(file.getFileName())) {
                return;
            }
            if (isBinary(file) || sizeOf(file) > MAX_SCAN_BYTES) {
                return;
            }
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (matches.size() >= maxResults) {
                        truncated[0] = true;
                        return;
                    }
                    Matcher matcher = pattern.matcher(lines.get(i));
                    if (matcher.find()) {
                        matches.add(base.relativize(file) + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            } catch (Exception ignored) {
                // 单个文件读失败不影响整体搜索（编码/权限等）
            }
        });

        if (matches.isEmpty()) {
            return new AgentToolRegistry.ToolExecutionResult("grep", true,
                    "未匹配到内容: pattern=" + expression + "，起点=" + base, Map.of("matches", 0));
        }
        StringBuilder content = new StringBuilder("pattern: ").append(expression)
                .append("\n起点: ").append(base)
                .append("\n匹配: ").append(matches.size()).append(truncated[0] ? "（已达到上限，结果被截断）" : "")
                .append("\n---\n");
        content.append(String.join("\n", matches));
        return new AgentToolRegistry.ToolExecutionResult("grep", true, content.toString(),
                Map.of("matches", matches.size()));
    }

    private AgentToolRegistry.ToolExecutionResult glob(Map<String, Object> args) {
        String expression = stringArg(args, "pattern");
        if (expression.isBlank()) {
            return fail("glob", "pattern 不能为空");
        }
        if (expression.contains("..")) {
            return fail("glob", "pattern 不允许包含 .. : " + expression);
        }
        Path base;
        try {
            base = policyGateway.resolvePath(stringArg(args, "path").isBlank() ? "." : stringArg(args, "path"));
        } catch (RuntimeException e) {
            return fail("glob", e.getMessage());
        }
        if (!Files.isDirectory(base)) {
            return fail("glob", "搜索起点不是目录: " + base);
        }
        PathMatcher matcher = compileGlobMatcher(expression);
        int maxResults = intArg(args, "max_results", 100, 1, MAX_GLOB_RESULTS);

        List<String> hits = new ArrayList<>();
        walkFiles(base, file -> {
            if (hits.size() >= maxResults) {
                return;
            }
            if (matcher.matches(toUnixPath(base.relativize(file)))) {
                hits.add(base.relativize(file).toString());
            }
        });

        if (hits.isEmpty()) {
            return new AgentToolRegistry.ToolExecutionResult("glob", true,
                    "未匹配到文件: pattern=" + expression + "，起点=" + base, Map.of("files", 0));
        }
        List<String> sorted = new ArrayList<>(hits);
        sorted.sort(String::compareTo);
        StringBuilder content = new StringBuilder("pattern: ").append(expression)
                .append("\n起点: ").append(base)
                .append("\n匹配: ").append(sorted.size()).append(hits.size() >= maxResults ? "（可能被上限截断）" : "")
                .append("\n---\n");
        content.append(String.join("\n", sorted));
        return new AgentToolRegistry.ToolExecutionResult("glob", true, content.toString(),
                Map.of("files", sorted.size()));
    }

    private void walkFiles(Path base, java.util.function.Consumer<Path> visitor) {
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(base) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        visitor.accept(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.warn("遍历目录失败: base={}, error={}", base, e.getMessage());
        }
    }

    private static Path toUnixPath(Path relative) {
        return Path.of(relative.toString().replace('\\', '/'));
    }

    private static PathMatcher compileGlobMatcher(String glob) {
        if (glob == null || glob.isBlank()) {
            return null;
        }
        String normalized = glob.trim().replace('\\', '/');
        if (normalized.contains("..")) {
            return null;
        }
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + normalized);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBinary(Path file) {
        try {
            byte[] head = new byte[1024];
            try (var stream = Files.newInputStream(file)) {
                int read = stream.read(head);
                for (int i = 0; i < Math.max(read, 0); i++) {
                    if (head[i] == 0) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static AgentToolRegistry.ToolExecutionResult fail(String name, String reason) {
        return new AgentToolRegistry.ToolExecutionResult(name, false, reason, Map.of());
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean boolArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intArg(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object value = args.get(key);
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(parsed, max));
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> stringProp(String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string");
        prop.put("description", description);
        return prop;
    }

    private static Map<String, Object> intProp(String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "integer");
        prop.put("description", description);
        return prop;
    }

    private static Map<String, Object> boolProp(String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "boolean");
        prop.put("description", description);
        return prop;
    }
}
