package com.mindflow.harness.skill;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手写 YAML 子集解析器（移植自 MindFlow skill.SkillFrontmatterParser 的口径）：
 * 支持 frontmatter 头 `--- ... ---`、单行 string、多行 `|` block、行内数组。
 * 嵌套对象不支持（警告并跳过）。必填 name / description，缺失返回 null 由调用方拒绝。
 */
@Component
public class SkillFrontmatterParser {

    /**
     * description 上限对齐 Agent Skills 规范的 1024 字。
     * 官方技能（docx / pptx 等）会把「何时该用我」写满几百字，旧的 500 字口径会直接拒掉合法包；
     * 提示词预算由 SkillIndexFormatter 的单行截断控制，不该靠拒收来省字符。
     */
    private static final int MAX_DESCRIPTION_CODEPOINTS = 1024;

    public record ParseResult(Skill skill, String bodyWithoutFrontmatter, Map<String, String> fields) {}

    /**
     * 解析失败的具体原因（成功返回 null）。旧口径只回一句「frontmatter 非法」，
     * 导入者往往不知道到底是缺 name、还是 name 不是 kebab-case，这里把原因拆开。
     */
    public String failureReason(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "文件内容为空";
        }
        String content = rawContent.replace("\r\n", "\n");
        if (!content.startsWith("---")) {
            return "缺少 frontmatter（文件必须以 --- 开头）";
        }
        int endIdx = content.indexOf("\n---", 3);
        if (endIdx < 0) {
            return "frontmatter 未闭合（缺少结尾的 ---）";
        }
        Map<String, String> fields = parseFields(content.substring(3, endIdx));
        String name = fields.get("name");
        String description = fields.get("description");
        if (name == null || name.isBlank()) {
            return "frontmatter 缺少 name";
        }
        if (!name.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            return "name 必须是 kebab-case（小写字母/数字/单连字符）：" + name;
        }
        if (description == null || description.isBlank()) {
            return "frontmatter 缺少 description";
        }
        if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION_CODEPOINTS) {
            return "description 超过 " + MAX_DESCRIPTION_CODEPOINTS + " 字上限（当前 "
                    + description.codePointCount(0, description.length()) + " 字），请精简后重新导入";
        }
        return null;
    }

    public ParseResult parse(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }
        String content = rawContent.replace("\r\n", "\n");
        if (!content.startsWith("---")) {
            return null;
        }
        int endIdx = content.indexOf("\n---", 3);
        if (endIdx < 0) {
            return null;
        }
        String frontmatter = content.substring(3, endIdx);
        String body = content.substring(endIdx + 4);
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }

        Map<String, String> fields = parseFields(frontmatter);
        String name = fields.get("name");
        String description = fields.get("description");
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            return null;
        }
        if (!name.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            return null;
        }
        if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION_CODEPOINTS) {
            return null;
        }

        Skill skill = new Skill(name, description,
                fields.getOrDefault("version", ""),
                fields.getOrDefault("author", ""),
                fields.getOrDefault("tags", ""),
                body);
        // fields 原样透出：allowed-tools / license 等未建模的键不应当在导入时被静默丢掉
        return new ParseResult(skill, body, fields);
    }

    private Map<String, String> parseFields(String frontmatter) {
        Map<String, String> fields = new LinkedHashMap<>();
        String currentKey = null;
        StringBuilder blockValue = null;

        for (String line : frontmatter.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("  ") || line.startsWith("\t")) {
                if (currentKey != null && blockValue != null) {
                    blockValue.append(line.stripLeading()).append('\n');
                }
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (currentKey != null && blockValue != null && !blockValue.isEmpty()) {
                fields.put(currentKey, blockValue.toString().stripTrailing());
            }
            currentKey = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("|".equals(value)) {
                blockValue = new StringBuilder();
            } else {
                blockValue = null;
                fields.put(currentKey, normalizeInline(value));
            }
        }
        if (currentKey != null && blockValue != null && !blockValue.isEmpty()) {
            fields.put(currentKey, blockValue.toString().stripTrailing());
        }
        return fields;
    }

    private String normalizeInline(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) {
                return "";
            }
            return inner;
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
