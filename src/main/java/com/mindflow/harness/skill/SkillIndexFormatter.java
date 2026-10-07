package com.mindflow.harness.skill;

import java.util.List;

/**
 * Skill 索引渲染：把启用 skill 渲染为注入 system prompt 末尾的 `## 可用 Skills` 段。
 * 上限：20 条、总大小 ≤ 8KB、单条描述截到 240 字。
 *
 * 描述允许写 1024 字（规范口径），但 20 条全文常驻会挤爆提示词反而让后面的技能被整行丢弃，
 * 所以在索引段只给“能判断该不该用”的前 240 字，完整描述在 load_skill 读入口文档时自然拿到。
 *
 * 目录化 Skill 会额外带上入口文件路径——这是三级披露的锚点：
 * L1 只给 name+description+入口路径；L2 由 load_skill 读 SKILL.md；
 * L3 由模型自己用 read_file 按需读 references/ 等附属文件（只读需要的那一个）。
 * 依赖未满足的包直接标注降级原因，避免模型对着跑不通的手册反复尝试。
 */
public final class SkillIndexFormatter {

    private static final int MAX_INDEX_CHARS = 8192;
    private static final int MAX_DESC_CHARS = 240;
    private static final int MAX_DEPS_CHARS = 180;

    private SkillIndexFormatter() {
    }

    public static String format(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 可用 Skills\n");
        sb.append("以下能力手册可在匹配时通过 load_skill 工具加载：\n");
        for (Skill skill : skills) {
            StringBuilder line = new StringBuilder("- ")
                    .append(skill.getName()).append(": ").append(oneLine(skill.getDescription())).append('\n');
            if (skill.hasPackage()) {
                line.append("  入口: ").append(skill.getEntryPath())
                        .append("（附属文件按需用 read_file 读取，一次只读当前需要的那个；脚本仅可读，无执行通道）\n");
            }
            if (skill.degraded()) {
                String deps = compact(skill.getMissingDeps());
                if (!deps.isEmpty()) {
                    line.append("  ⚠ 依赖未满足: ").append(deps).append('\n');
                }
            }
            if (sb.length() + line.length() > MAX_INDEX_CHARS) {
                break;
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * description 允许是 YAML 块标量，多行会破坏索引段的列表结构，压成一行更安全也更省字符；
     * 超长时只保留前 MAX_DESC_CHARS 字，完整描述交给 load_skill 输出。
     */
    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String line = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
        return line.length() > MAX_DESC_CHARS ? line.substring(0, MAX_DESC_CHARS) + "…（详见 load_skill）" : line;
    }

    /** missingDeps 存的是 JSON 数组字符串，索引段里渲染成逗号分隔并限长 */
    private static String compact(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        String text = json.replaceAll("[\"\\[\\]]", "").replace("\\", "").trim();
        return text.length() > MAX_DEPS_CHARS ? text.substring(0, MAX_DEPS_CHARS) + "…" : text;
    }
}
