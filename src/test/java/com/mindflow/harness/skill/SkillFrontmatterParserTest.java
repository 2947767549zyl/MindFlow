package com.mindflow.harness.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillFrontmatterParserTest {

    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();

    @Test
    void parsesBasicFrontmatterAndBody() {
        String raw = """
                ---
                name: web-access
                description: 网页访问决策手册，指导选择 web_fetch 或浏览器 MCP
                version: 1.0.0
                author: mindflow
                tags: [web, browser]
                ---
                # Web Access
                决策四步法...
                """;

        SkillFrontmatterParser.ParseResult result = parser.parse(raw);
        assertNotNull(result);
        assertEquals("web-access", result.skill().getName());
        assertTrue(result.skill().getDescription().contains("网页访问"));
        assertEquals("1.0.0", result.skill().getVersion());
        assertEquals("web, browser", result.skill().getTags());
        assertTrue(result.skill().getBody().contains("决策四步法"));
    }

    @Test
    void supportsMultilineBlock() {
        String raw = """
                ---
                name: demo
                description: 多行描述
                author: |
                  第一行
                  第二行
                ---
                body
                """;
        SkillFrontmatterParser.ParseResult result = parser.parse(raw);
        assertNotNull(result);
        assertTrue(result.skill().getAuthor().contains("第一行"));
        assertTrue(result.skill().getAuthor().contains("第二行"));
    }

    @Test
    void rejectsMissingOrInvalidRequiredFields() {
        assertNull(parser.parse(null));
        assertNull(parser.parse("no frontmatter"));
        assertNull(parser.parse("---\nname: demo\n---\nbody"));
        assertNull(parser.parse("---\ndescription: 只有描述\n---\nbody"));
        assertNull(parser.parse("---\nname: BadName\ndescription: 名称非 kebab-case\n---\nbody"));
    }

    /**
     * 官方 docx/pptx 技能的 description 普遍写了六七百字（把“何时使用”写进描述），
     * 旧的 500 字口径会把合法包直接拒掉；上限应对齐 Agent Skills 规范的 1024 字。
     */
    @Test
    void acceptsLongSpecCompliantDescription() {
        String desc = "Use when the user wants to work with documents. ".repeat(15);
        assertTrue(desc.length() > 500 && desc.length() <= 1024);
        assertNull(parser.failureReason("---\nname: docx\ndescription: " + desc + "\n---\nbody"));
        assertNotNull(parser.parse("---\nname: docx\ndescription: " + desc + "\n---\nbody"));

        String tooLong = "x".repeat(1100);
        String reason = parser.failureReason("---\nname: docx\ndescription: " + tooLong + "\n---\nbody");
        assertTrue(reason.contains("1024"));
        assertTrue(reason.contains("1100"));
        assertNull(parser.parse("---\nname: docx\ndescription: " + tooLong + "\n---\nbody"));
    }
}
