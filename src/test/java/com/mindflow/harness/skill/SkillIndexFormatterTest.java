package com.mindflow.harness.skill;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillIndexFormatterTest {

    private static Skill skill(String name, String desc) {
        return new Skill(name, desc, "1.0", "a", "tag", "body");
    }

    @Test
    void emptyListProducesEmptySection() {
        assertEquals("", SkillIndexFormatter.format(List.of()));
        assertEquals("", SkillIndexFormatter.format(null));
    }

    @Test
    void rendersEntriesWithinLimits() {
        String section = SkillIndexFormatter.format(List.of(
                skill("web-access", "网页访问决策手册"),
                skill("rag-tune", "检索调优手册")));

        assertTrue(section.startsWith("## 可用 Skills"));
        assertTrue(section.contains("web-access: 网页访问决策手册"));
        assertTrue(section.contains("rag-tune: 检索调优手册"));
        // 没有落成目录包的（历史 Redis-only 数据）不应渲染出空入口行
        assertFalse(section.contains("入口:"));
    }

    @Test
    void rendersEntryPathAndDegradedReason() {
        Skill packaged = new Skill("web-access", "网页访问决策手册", "1.0", "a", "web", "body",
                "data/skills/web-access/SKILL.md", "DEGRADED",
                "[\"引用文件缺失：references/api.md\",\"含脚本执行诉求\"]");

        String section = SkillIndexFormatter.format(List.of(packaged));

        assertTrue(section.contains("data/skills/web-access/SKILL.md"));
        assertTrue(section.contains("⚠ 依赖未满足: 引用文件缺失：references/api.md"));
    }

    @Test
    void capsTotalSizeAroundEightKb() {
        List<Skill> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(skill("skill-" + i, "描述".repeat(40)));
        }
        String section = SkillIndexFormatter.format(many);
        assertTrue(section.length() <= 8400, "index section should stay near the 8KB cap");
    }

    /**
     * description 允许写满 1024 字，但索引段不能因此把后面的技能整行挤掉：
     * 单条截到 240 字，20 条目录化技能应全部列出。
     */
    @Test
    void truncatesLongDescriptionsInsteadOfDroppingSkills() {
        List<Skill> packaged = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            packaged.add(new Skill("skill-" + i, "官".repeat(1000), "1.0", "a", "t", "body",
                    "data/skills/skill-" + i + "/SKILL.md", "READY", "[]"));
        }

        String section = SkillIndexFormatter.format(packaged);

        for (int i = 0; i < 20; i++) {
            assertTrue(section.contains("- skill-" + i + ":"), "skill-" + i + " 不应被整行丢弃");
        }
        assertTrue(section.contains("详见 load_skill"));
        assertFalse(section.contains("官".repeat(241)), "单条描述应被截断");
    }
}
