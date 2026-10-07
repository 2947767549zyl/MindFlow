package com.mindflow.harness.skill;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 提示词缓存：Redis Hash + List 组合存储（面试稿口径）。
 *
 * - Hash  `mindflow:skill:{name}`：name / description / version / author / tags / body
 *   + 目录化字段 entryPath / status / missingDeps
 * - List  `mindflow:skill:index`：技能名插入顺序（保证快速有序读取）
 * - Set   `mindflow:skill:disabled`：禁用名单
 *
 * 注意：这里只是缓存，不是持久层。目录包的权威清单在 MySQL（skill_packages / skill_files），
 * 文件实体在磁盘 data/skills/；Redis 清空后由 SkillPackageMigrator 从 MySQL 重建。
 */
@Component
public class SkillStateStore {

    private static final String HASH_PREFIX = "mindflow:skill:";
    private static final String INDEX_KEY = "mindflow:skill:index";
    private static final String DISABLED_KEY = "mindflow:skill:disabled";

    private final StringRedisTemplate redisTemplate;

    public SkillStateStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void save(Skill skill) {
        Map<String, String> hash = new HashMap<>();
        hash.put("name", skill.getName());
        hash.put("description", skill.getDescription() == null ? "" : skill.getDescription());
        hash.put("version", skill.getVersion() == null ? "" : skill.getVersion());
        hash.put("author", skill.getAuthor() == null ? "" : skill.getAuthor());
        hash.put("tags", skill.getTags() == null ? "" : skill.getTags());
        hash.put("body", skill.getBody() == null ? "" : skill.getBody());
        hash.put("entryPath", skill.getEntryPath() == null ? "" : skill.getEntryPath());
        hash.put("status", skill.getStatus() == null ? "" : skill.getStatus());
        hash.put("missingDeps", skill.getMissingDeps() == null ? "" : skill.getMissingDeps());
        redisTemplate.opsForHash().putAll(HASH_PREFIX + skill.getName(), hash);

        List<String> index = redisTemplate.opsForList().range(INDEX_KEY, 0, -1);
        if (index == null || !index.contains(skill.getName())) {
            redisTemplate.opsForList().rightPush(INDEX_KEY, skill.getName());
        }
    }

    public Skill get(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(HASH_PREFIX + name);
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        return new Skill(
                String.valueOf(hash.getOrDefault("name", name)),
                String.valueOf(hash.getOrDefault("description", "")),
                String.valueOf(hash.getOrDefault("version", "")),
                String.valueOf(hash.getOrDefault("author", "")),
                String.valueOf(hash.getOrDefault("tags", "")),
                String.valueOf(hash.getOrDefault("body", "")),
                blankToNull(hash.get("entryPath")),
                blankToNull(hash.get("status")),
                blankToNull(hash.get("missingDeps")));
    }

    /**
     * 按 index List 的插入顺序全量读取（快速有序读取）。
     */
    public List<Skill> listAll() {
        List<String> names = redisTemplate.opsForList().range(INDEX_KEY, 0, -1);
        List<Skill> skills = new ArrayList<>();
        if (names == null) {
            return skills;
        }
        for (String name : names) {
            Skill skill = get(name);
            if (skill != null) {
                skills.add(skill);
            }
        }
        return skills;
    }

    public void delete(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        redisTemplate.delete(HASH_PREFIX + name);
        redisTemplate.opsForList().remove(INDEX_KEY, 0, name);
        redisTemplate.opsForSet().remove(DISABLED_KEY, name);
    }

    public boolean exists(String name) {
        return name != null && !name.isBlank() && Boolean.TRUE.equals(redisTemplate.hasKey(HASH_PREFIX + name));
    }

    public void setDisabled(String name, boolean disabled) {
        if (disabled) {
            redisTemplate.opsForSet().add(DISABLED_KEY, name);
        } else {
            redisTemplate.opsForSet().remove(DISABLED_KEY, name);
        }
    }

    public boolean isDisabled(String name) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(DISABLED_KEY, name));
    }

    public Set<String> disabledNames() {
        Set<String> members = redisTemplate.opsForSet().members(DISABLED_KEY);
        return members == null ? Set.of() : members;
    }

    /** 缓存里的目录化字段：空串按"未落成包"处理，避免索引段渲染出无意义的空路径 */
    private static String blankToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
