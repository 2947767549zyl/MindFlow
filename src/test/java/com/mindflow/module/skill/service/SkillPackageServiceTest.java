package com.mindflow.module.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.common.exception.CustomException;
import com.mindflow.harness.skill.Skill;
import com.mindflow.harness.skill.SkillFrontmatterParser;
import com.mindflow.harness.skill.SkillStateStore;
import com.mindflow.module.chat.agent.AgentToolRegistry;
import com.mindflow.module.skill.entity.SkillPackage;
import com.mindflow.module.skill.repository.SkillFileEntryRepository;
import com.mindflow.module.skill.repository.SkillPackageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SkillPackageService 的目录落盘、路径安全与依赖校验行为。
 * 不启 Spring 容器：仓库/Redis 缓存全部 mock，只验证服务自身的逻辑。
 */
class SkillPackageServiceTest {

    private static final String ENTRY = """
            ---
            name: demo-skill
            description: 演示用的目录化技能
            ---
            先读 references/api.md 再按步骤操作。
            """;

    private SkillPackageRepository packageRepository;
    private SkillFileEntryRepository fileRepository;
    private SkillStateStore skillStateStore;
    private SkillPackageService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        packageRepository = mock(SkillPackageRepository.class);
        fileRepository = mock(SkillFileEntryRepository.class);
        skillStateStore = mock(SkillStateStore.class);
        ObjectProvider<AgentToolRegistry> toolRegistryProvider = mock(ObjectProvider.class);
        // 拿不到工具清单时跳过工具依赖校验，只保留文件/脚本两类判定
        when(toolRegistryProvider.getIfAvailable()).thenReturn(null);
        when(packageRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(packageRepository.save(any(SkillPackage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service = new SkillPackageService(packageRepository, fileRepository, new SkillFrontmatterParser(),
                skillStateStore, toolRegistryProvider, new ObjectMapper());
        ReflectionTestUtils.setField(service, "skillStoragePath", tempDir.toString());
    }

    @Test
    void importPackageWritesWholeDirectoryTree() throws IOException {
        SkillPackageService.ImportResult result = service.importPackage("demo-skill", List.of(
                file("SKILL.md", ENTRY),
                file("references/api.md", "# API\n接口清单")
        ), "admin");

        SkillPackage saved = result.skillPackage();
        assertEquals("demo-skill", saved.getName());
        assertEquals(SkillPackage.STATUS_READY, saved.getStatus());
        assertEquals(2, saved.getFileCount());
        assertTrue(Files.exists(tempDir.resolve("demo-skill/SKILL.md")));
        assertTrue(Files.exists(tempDir.resolve("demo-skill/references/api.md")));

        // 缓存里带上入口路径，索引段才有可点击的锚点
        ArgumentCaptor<Skill> cached = ArgumentCaptor.forClass(Skill.class);
        verify(skillStateStore).save(cached.capture());
        assertTrue(cached.getValue().hasPackage());
        assertTrue(cached.getValue().getEntryPath().endsWith("demo-skill/SKILL.md"));
    }

    @Test
    void importPackageRejectsPathTraversal() {
        CustomException error = assertThrows(CustomException.class, () ->
                service.importPackage("demo-skill", List.of(
                        file("SKILL.md", ENTRY),
                        file("../escape.txt", "escape")
                ), "admin"));
        assertTrue(error.getMessage().contains(".."));
        verify(packageRepository, never()).save(any());
    }

    @Test
    void importPackageRejectsAbsoluteAndIllegalEntry() {
        assertThrows(CustomException.class, () ->
                service.importPackage("demo-skill", List.of(
                        file("SKILL.md", ENTRY),
                        file("C:/Windows/system.ini", "boom")
                ), "admin"));

        CustomException noEntry = assertThrows(CustomException.class, () ->
                service.importPackage("demo-skill", List.of(file("references/api.md", "# API")), "admin"));
        assertTrue(noEntry.getMessage().contains("SKILL.md"));
    }

    @Test
    void importPackageMarksDegradedWhenReferenceMissing() {
        SkillPackageService.ImportResult result = service.importPackage("demo-skill", List.of(
                file("SKILL.md", """
                        ---
                        name: demo-skill
                        description: 缺少附属文件的技能
                        ---
                        请阅读 references/gone.md 与 scripts/run.py 完成操作。
                        """),
                // 脚本随包上传：只存只读，导入期就该告知“无执行通道”
                file("scripts/run.py", "print('ok')\n")
        ), "admin");

        assertEquals(SkillPackage.STATUS_DEGRADED, result.skillPackage().getStatus());
        String missingDeps = result.skillPackage().getMissingDeps();
        assertTrue(missingDeps.contains("references/gone.md"));
        assertTrue(missingDeps.contains("脚本"));
    }

    @Test
    void importPackagePrunesStaleFiles() {
        when(packageRepository.findByName("demo-skill")).thenReturn(Optional.of(existingPackage()));
        service.importPackage("demo-skill", List.of(
                file("SKILL.md", ENTRY),
                file("references/api.md", "# API")
        ), "admin");

        // 上一次导入留下的旧附属文件应当被清掉，避免 load_skill 清单里出现幽灵文件
        assertFalse(Files.exists(tempDir.resolve("demo-skill/references/stale.md")));
    }

    @Test
    void readEntryStripsFrontmatterAndPaginatesByLineCount() throws IOException {
        Path entry = tempDir.resolve("paged/SKILL.md");
        Files.createDirectories(entry.getParent());
        StringBuilder markdown = new StringBuilder("---\nname: paged\ndescription: 分页\n---\n");
        for (int i = 1; i <= 10; i++) {
            markdown.append("L").append(i).append('\n');
        }
        Files.writeString(entry, markdown.toString(), StandardCharsets.UTF_8);
        when(packageRepository.findByName("paged")).thenReturn(Optional.of(packageOf("paged", entry.toString(), 3)));

        SkillPackageService.EntryText first = service.readEntry("paged", 1, 3);
        assertEquals("L1\nL2\nL3", first.content());
        assertEquals(10, first.totalLines());
        assertEquals(1, first.from());
        assertEquals(3, first.to());
        assertTrue(first.truncated());

        SkillPackageService.EntryText second = service.readEntry("paged", 4, 3);
        assertEquals("L4\nL5\nL6", second.content());
        assertEquals(4, second.from());

        SkillPackageService.EntryText tail = service.readEntry("paged", 11, 3);
        assertEquals("", tail.content());
        assertFalse(tail.truncated());
    }

    @Test
    void buildBufferBodyKeepsResumableHintWhenTruncated() {
        SkillPackage pkg = packageOf("demo-skill", tempDir.resolve("demo-skill/SKILL.md").toString(), 4);
        String longText = "字".repeat(17000);
        SkillPackageService.EntryText entry =
                new SkillPackageService.EntryText(pkg.getEntryPath(), longText, 800, 1, 600, true);

        String body = service.buildBufferBody(pkg, entry);

        assertTrue(body.length() < longText.length());
        // 截断必须给出可恢复路径，而不是像旧实现那样静默丢弃
        assertTrue(body.contains("read_file"));
        assertTrue(body.contains("offset="));
        assertTrue(body.contains(pkg.getEntryPath()));
    }

    @Test
    void readTextFileRejectsBinaryAndMissingPath() throws IOException {
        Path dir = tempDir.resolve("demo-skill");
        Files.createDirectories(dir.resolve("references"));
        Files.writeString(dir.resolve("references/api.md"), "# API", StandardCharsets.UTF_8);
        Files.write(dir.resolve("assets.bin"), new byte[] {0, 1, 2, 3});
        when(packageRepository.findByName("demo-skill")).thenReturn(Optional.of(packageOf("demo-skill",
                dir.resolve("SKILL.md").toString(), 3)));

        assertEquals("# API", service.readTextFile("demo-skill", "references/api.md", 1, 100));
        assertThrows(CustomException.class, () -> service.readTextFile("demo-skill", "references/gone.md", 1, 100));
        assertThrows(CustomException.class, () -> service.readTextFile("demo-skill", "assets.bin", 1, 100));
        assertThrows(CustomException.class, () -> service.readTextFile("demo-skill", "../outside.txt", 1, 100));
    }

    @Test
    void deletePackageRemovesDirectoryAndCache() {
        Path dir = tempDir.resolve("demo-skill");
        try {
            Files.createDirectories(dir.resolve("references"));
            Files.writeString(dir.resolve("SKILL.md"), ENTRY, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        when(packageRepository.findByName("demo-skill"))
                .thenReturn(Optional.of(packageOf("demo-skill", dir.resolve("SKILL.md").toString(), 2)));

        assertTrue(service.deletePackage("demo-skill"));
        assertFalse(Files.exists(dir));
        verify(fileRepository).deleteBySkillName("demo-skill");
        verify(packageRepository).deleteByName("demo-skill");
        verify(skillStateStore).delete("demo-skill");
    }

    @Test
    void migrateLegacyFromRedisSkipsAlreadyPackagedSkills() {
        Skill legacy = new Skill("old-skill", "旧的 Redis-only 技能", "", "", "", "正文");
        when(skillStateStore.listAll()).thenReturn(List.of(legacy));
        when(packageRepository.existsByName("old-skill")).thenReturn(true);

        assertEquals(0, service.migrateLegacyFromRedis());
        verify(packageRepository, never()).save(any());
    }

    private SkillPackageService.PackageFile file(String relPath, String content) {
        return new SkillPackageService.PackageFile(relPath, relPath, content.getBytes(StandardCharsets.UTF_8));
    }

    private static SkillPackage packageOf(String name, String entryPath, int fileCount) {
        SkillPackage pkg = new SkillPackage();
        pkg.setName(name);
        pkg.setDescription("描述");
        pkg.setEntryPath(entryPath);
        pkg.setDirPath(Path.of(entryPath).getParent().toString().replace('\\', '/'));
        pkg.setFileCount(fileCount);
        pkg.setStatus(SkillPackage.STATUS_READY);
        pkg.setMissingDeps("[]");
        return pkg;
    }

    private SkillPackage existingPackage() {
        Path dir = tempDir.resolve("demo-skill");
        try {
            Files.createDirectories(dir.resolve("references"));
            Files.writeString(dir.resolve("references/stale.md"), "过期文件", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SkillPackage pkg = packageOf("demo-skill", dir.resolve("SKILL.md").toString(), 1);
        pkg.setBody("旧正文");
        return pkg;
    }
}
