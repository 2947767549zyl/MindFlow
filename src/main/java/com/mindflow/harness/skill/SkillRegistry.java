package com.mindflow.harness.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Skill 注册表：对外统一入口，启用上限 20 个（与 MindFlow 口径一致）。
 */
@Service
public class SkillRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);
    private static final int MAX_ENABLED_SKILLS = 20;

    private final SkillStateStore stateStore;
    private final SkillFrontmatterParser frontmatterParser;

    public SkillRegistry(SkillStateStore stateStore, SkillFrontmatterParser frontmatterParser) {
        this.stateStore = stateStore;
        this.frontmatterParser = frontmatterParser;
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(stateStore.get(name));
    }

    /**
     * 保存一份 SKILL.md：解析 frontmatter 后写入 Redis Hash&List。
     *
     * @return 解析失败（缺 name/description、格式非法）返回空
     */
    public Optional<Skill> save(String rawMarkdown) {
        SkillFrontmatterParser.ParseResult result = frontmatterParser.parse(rawMarkdown);
        if (result == null) {
            logger.warn("SKILL.md 解析失败：缺少合法 frontmatter（name/description）");
            return Optional.empty();
        }
        stateStore.save(result.skill());
        return Optional.of(result.skill());
    }

    public boolean delete(String name) {
        if (!stateStore.exists(name)) {
            return false;
        }
        stateStore.delete(name);
        return true;
    }

    public void setEnabled(String name, boolean enabled) {
        stateStore.setDisabled(name, !enabled);
    }

    public List<Skill> enabledSkills() {
        return stateStore.listAll().stream()
                .filter(skill -> !stateStore.isDisabled(skill.getName()))
                .limit(MAX_ENABLED_SKILLS)
                .toList();
    }

    public List<Skill> allSkills() {
        return stateStore.listAll();
    }
}
