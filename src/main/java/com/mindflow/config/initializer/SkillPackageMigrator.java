package com.mindflow.config.initializer;

import com.mindflow.module.skill.service.SkillPackageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Skill 启动初始化：旧数据迁移 + 提示词缓存重建。
 *
 * 1. 把历史上“只写 Redis”的 Skill 落成 data/skills/&lt;name&gt;/ 目录包并写入 MySQL（幂等，已存则跳过）；
 * 2. 用 MySQL 清单回写 Redis 索引——Redis 被清空或换实例后，技能列表不应从提示词里消失。
 *
 * 失败只告警不阻断启动（Skill 是增强能力，不该拖垮主流程）。
 */
@Component
@Order(4)
public class SkillPackageMigrator implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(SkillPackageMigrator.class);

    private final SkillPackageService skillPackageService;

    public SkillPackageMigrator(SkillPackageService skillPackageService) {
        this.skillPackageService = skillPackageService;
    }

    @Override
    public void run(String... args) {
        try {
            int migrated = skillPackageService.migrateLegacyFromRedis();
            if (migrated > 0) {
                logger.info("Skill 旧数据迁移：{} 个 Redis-only Skill 已转为目录包", migrated);
            }
        } catch (Exception e) {
            logger.warn("Skill 旧数据迁移失败，跳过（不影响启动）：{}", e.getMessage());
        }
        try {
            int cached = skillPackageService.syncCacheFromDatabase();
            if (cached > 0) {
                logger.info("Skill 提示词缓存已从 MySQL 重建：{} 个 Skill", cached);
            }
        } catch (Exception e) {
            logger.warn("Skill 缓存重建失败，跳过（不影响启动）：{}", e.getMessage());
        }
    }
}
