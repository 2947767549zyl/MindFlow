package com.mindflow.module.chat.controller;

import com.mindflow.common.exception.CustomException;
import com.mindflow.harness.policy.AuditLogService;
import com.mindflow.harness.skill.Skill;
import com.mindflow.harness.skill.SkillRegistry;
import com.mindflow.harness.skill.SkillStateStore;
import com.mindflow.module.auth.entity.User;
import com.mindflow.module.auth.repository.UserRepository;
import com.mindflow.module.skill.entity.SkillFileEntry;
import com.mindflow.module.skill.entity.SkillPackage;
import com.mindflow.module.skill.service.SkillPackageService;
import com.mindflow.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 管理接口：Web 形态下替代 CLI 的 ~/.mindflow/skills 文件目录。
 *
 * 存储三层（与 wiki 一致）：
 * - 磁盘 data/skills/&lt;name&gt;/：整个 Skill 目录，Agent 三级披露的读取入口；
 * - MySQL skill_packages / skill_files：权威清单与元数据；
 * - Redis：只作提示词索引缓存（索引段 + 启停状态），不再是唯一真相。
 *
 * 权限：查看/下载任意登录用户可用；导入/删除/启停仅管理员（Skill 内容会直接影响 Agent 行为），
 * 且所有变更写审计。
 */
@RestController
@RequestMapping("/api/v1/skill")
public class SkillController {

    private final SkillRegistry skillRegistry;
    private final SkillStateStore skillStateStore;
    private final SkillPackageService skillPackageService;
    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public SkillController(SkillRegistry skillRegistry,
                           SkillStateStore skillStateStore,
                           SkillPackageService skillPackageService,
                           JwtUtils jwtUtils,
                           UserRepository userRepository,
                           AuditLogService auditLogService) {
        this.skillRegistry = skillRegistry;
        this.skillStateStore = skillStateStore;
        this.skillPackageService = skillPackageService;
        this.jwtUtils = jwtUtils;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String token) {
        requireUser(token);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Skill skill : skillRegistry.allSkills()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", skill.getName());
            item.put("description", skill.getDescription());
            item.put("version", skill.getVersion());
            item.put("tags", skill.getTags());
            item.put("enabled", !skillStateStore.isDisabled(skill.getName()));
            // 目录包信息：让前端能区分"整目录已落盘"还是"仅旧的单文件数据"
            Optional<SkillPackage> pkg = safeFindPackage(skill.getName());
            if (pkg.isPresent()) {
                SkillPackage entity = pkg.get();
                item.put("hasPackage", true);
                item.put("status", entity.getStatus());
                item.put("missingDeps", skillPackageService.missingDepsList(entity));
                item.put("fileCount", entity.getFileCount());
                item.put("totalBytes", entity.getTotalBytes());
                item.put("dirPath", entity.getDirPath());
                item.put("entryPath", entity.getEntryPath());
                item.put("updatedAt", entity.getUpdatedAt());
            } else {
                item.put("hasPackage", false);
                item.put("status", null);
                item.put("missingDeps", List.of());
            }
            data.add(item);
        }
        return ok("success", data);
    }

    @GetMapping("/{name}/content")
    public ResponseEntity<?> content(@RequestHeader("Authorization") String token,
                                     @PathVariable String name) {
        requireUser(token);
        Optional<SkillPackage> pkg = safeFindPackage(name);
        if (pkg.isPresent()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", name);
            data.put("content", skillPackageService.readEntryRaw(name));
            data.put("entryPath", pkg.get().getEntryPath());
            return ok("success", data);
        }
        Optional<Skill> skill = skillRegistry.get(name);
        if (skill.isEmpty()) {
            return ResponseEntity.ok(Map.of("code", 404, "message", "Skill 不存在: " + name, "data", Map.of()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", skill.get().getName());
        data.put("content", skillPackageService.toRawMarkdown(skill.get()));
        data.put("entryPath", null);
        return ok("success", data);
    }

    /**
     * 单文件导入（旧的 Web 入口）：内容同样走 SkillPackageService 落盘 + 入库，
     * 保证"导入即持久化"，不再只写 Redis。
     */
    @PostMapping
    public ResponseEntity<?> save(@RequestHeader("Authorization") String token,
                                  @RequestBody Map<String, String> body) {
        String operator = requireAdmin(token);
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            audit("import:failed", String.valueOf(body.get("name")), operator, false, "content 为空");
            return fail("SKILL.md 内容不能为空");
        }
        try {
            SkillPackageService.ImportResult result = skillPackageService.importSingle(content, operator);
            audit("import", result.skillPackage().getName(), operator, true, null);
            return ok("已保存", Map.of(
                    "name", result.skillPackage().getName(),
                    "status", result.skillPackage().getStatus(),
                    "warnings", result.warnings()));
        } catch (CustomException e) {
            audit("import:failed", String.valueOf(body.get("name")), operator, false, e.getMessage());
            // 项目无全局异常处理器：导入类校验失败属于操作员可自救的业务错误，
            // 按仓库约定回 code!=200 + 具体原因，而不是抛成 500 让前端只剩一句“网络错误”
            return fail(e.getMessage());
        }
    }

    /**
     * 目录导入：前端用 webkitdirectory 选择整个 Skill 目录，逐个文件提交。
     * paths[i] 是 files[i] 相对目录根的路径（webkitRelativePath，含子目录）。
     */
    @PostMapping("/package")
    public ResponseEntity<?> importPackage(@RequestHeader("Authorization") String token,
                                           @RequestParam("files") MultipartFile[] files,
                                           @RequestParam(value = "paths", required = false) List<String> paths,
                                           @RequestParam(value = "rootDirName", required = false) String rootDirName) {
        String operator = requireAdmin(token);
        if (files == null || files.length == 0) {
            return fail("未收到任何文件");
        }
        List<SkillPackageService.PackageFile> packageFiles = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            String relPath = paths != null && i < paths.size() ? paths.get(i) : null;
            packageFiles.add(new SkillPackageService.PackageFile(
                    relPath == null || relPath.isBlank() ? file.getOriginalFilename() : relPath,
                    file.getOriginalFilename(),
                    readBytes(file)));
        }
        try {
            SkillPackageService.ImportResult result =
                    skillPackageService.importPackage(rootDirName, packageFiles, operator);
            audit("import:package", result.skillPackage().getName(), operator, true,
                    result.warnings().isEmpty() ? null : String.join("; ", result.warnings()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", result.skillPackage().getName());
            data.put("status", result.skillPackage().getStatus());
            data.put("fileCount", result.skillPackage().getFileCount());
            data.put("totalBytes", result.skillPackage().getTotalBytes());
            data.put("dirPath", result.skillPackage().getDirPath());
            data.put("missingDeps", skillPackageService.missingDepsList(result.skillPackage()));
            data.put("warnings", result.warnings());
            return ok("已导入 " + result.skillPackage().getName(), data);
        } catch (CustomException e) {
            audit("import:package:failed", rootDirName, operator, false, e.getMessage());
            return fail(e.getMessage());
        }
    }

    /** 文件清单 + 完整性状态：前端"完整性"列与目录预览用 */
    @GetMapping("/{name}/files")
    public ResponseEntity<?> files(@RequestHeader("Authorization") String token,
                                   @PathVariable String name) {
        requireUser(token);
        SkillPackage pkg = safeFindPackage(name)
                .orElseThrow(() -> new CustomException("Skill 尚未以目录形式导入: " + name, HttpStatus.NOT_FOUND));
        List<Map<String, Object>> items = new ArrayList<>();
        for (SkillFileEntry file : skillPackageService.listFiles(name)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("relPath", file.getRelPath());
            item.put("mediaType", file.getMediaType());
            item.put("sizeBytes", file.getSizeBytes());
            item.put("textReadable", file.getTextReadable());
            item.put("isEntry", file.getIsEntry());
            item.put("sha256", file.getSha256());
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("status", pkg.getStatus());
        data.put("dirPath", pkg.getDirPath());
        data.put("entryPath", pkg.getEntryPath());
        data.put("missingDeps", skillPackageService.missingDepsList(pkg));
        data.put("files", items);
        return ok("success", data);
    }

    /** 包内单个文本文件预览（分页）；Agent 侧读文件走内置 read_file，不经这里 */
    @GetMapping("/{name}/file")
    public ResponseEntity<?> file(@RequestHeader("Authorization") String token,
                                  @PathVariable String name,
                                  @RequestParam("path") String path,
                                  @RequestParam(value = "offset", defaultValue = "1") int offset,
                                  @RequestParam(value = "limit", defaultValue = "600") int limit) {
        requireUser(token);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("path", path);
        data.put("content", skillPackageService.readTextFile(name, path, offset, limit));
        return ok("success", data);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@RequestHeader("Authorization") String token,
                                    @PathVariable String name) {
        String operator = requireAdmin(token);
        // deletePackage 内部已兼容旧数据：Redis-only 且无包记录时按旧口径删缓存
        boolean deleted = skillPackageService.deletePackage(name);
        audit("delete", name, operator, deleted, deleted ? null : "Skill 不存在");
        return ok(deleted ? "已删除" : "Skill 不存在", Map.of("deleted", deleted));
    }

    @PostMapping("/{name}/enable")
    public ResponseEntity<?> enable(@RequestHeader("Authorization") String token,
                                    @PathVariable String name) {
        String operator = requireAdmin(token);
        skillRegistry.setEnabled(name, true);
        audit("enable", name, operator, true, null);
        return ok("已启用", Map.of("name", name));
    }

    @PostMapping("/{name}/disable")
    public ResponseEntity<?> disable(@RequestHeader("Authorization") String token,
                                     @PathVariable String name) {
        String operator = requireAdmin(token);
        skillRegistry.setEnabled(name, false);
        audit("disable", name, operator, true, null);
        return ok("已禁用", Map.of("name", name));
    }

    /** 包表查询失败（未迁移/DB 异常）时不影响列表主流程，按旧口径展示 */
    private Optional<SkillPackage> safeFindPackage(String name) {
        try {
            return skillPackageService.find(name);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new CustomException("读取上传文件失败：" + file.getOriginalFilename() + " → " + e.getMessage(),
                    HttpStatus.BAD_REQUEST);
        }
    }

    private String requireUser(String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (username == null || username.isBlank()) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }
        return username;
    }

    private String requireAdmin(String token) {
        String username = requireUser(token);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.UNAUTHORIZED));
        if (user.getRole() != User.Role.ADMIN) {
            throw new CustomException("仅管理员可导入/删除/启停 Skill", HttpStatus.FORBIDDEN);
        }
        return username;
    }

    private void audit(String action, String name, String operator, boolean allowed, String reason) {
        try {
            auditLogService.record("skill:" + action, "{\"name\":\"" + (name == null ? "" : name) + "\"}",
                    allowed ? AuditLogService.OUTCOME_ALLOW : AuditLogService.OUTCOME_DENY,
                    reason, AuditLogService.APPROVER_NONE, 0, operator, null);
        } catch (Exception ignored) {
        }
    }

    private ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", message);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /** 业务校验失败：HTTP 200 + code 400 + 可读原因，前端 axios 会把它当后端失败直接弹出 message */
    private ResponseEntity<?> fail(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 400);
        response.put("message", message);
        response.put("data", Map.of());
        return ResponseEntity.ok(response);
    }
}
