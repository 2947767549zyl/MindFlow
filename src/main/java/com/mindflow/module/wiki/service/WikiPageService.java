package com.mindflow.module.wiki.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class WikiPageService {

    private static final Logger logger = LoggerFactory.getLogger(WikiPageService.class);
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[\\[([^\\]]+)]]");

    @Value("${wiki.storage.path:data/wiki}")
    private String wikiStoragePath;

    /**
     * 保存或更新一个 Wiki 页面到文件系统
     */
    public void saveOrUpdate(String title, String markdownContent) {
        writeToFile(title, markdownContent);
    }

    /**
     * 处理双向链接：扫描所有 MD 文件中的 [[xxx]]，在目标文件末尾追加反向引用段落
     * 先移除旧的「被引用」段落再追加新的，避免重复累积
     */
    public void resolveBidirectionalLinks() {
        Map<String, Path> allPages = findAllPages();
        if (allPages.isEmpty()) return;

        Map<String, String> contentMap = readAllContents(allPages);
        if (contentMap.isEmpty()) return;

        // 收集链接关系：target → [source1, source2]
        Map<String, Set<String>> backlinks = new HashMap<>();
        for (Map.Entry<String, String> page : contentMap.entrySet()) {
            Set<String> links = extractLinks(page.getValue());
            for (String link : links) {
                if (contentMap.containsKey(link) && !link.equals(page.getKey())) {
                    backlinks.computeIfAbsent(link, k -> new LinkedHashSet<>())
                            .add(page.getKey());
                }
            }
        }

        // 为有反向引用的页面追加「被引用」段落
        for (Map.Entry<String, Set<String>> entry : backlinks.entrySet()) {
            String targetTitle = entry.getKey();
            Set<String> sources = entry.getValue();

            String currentContent = contentMap.get(targetTitle);
            if (currentContent == null) continue;

            String cleanContent = removeBacklinkSection(currentContent);
            String backlinkSection = buildBacklinkSection(sources);
            String updatedContent = cleanContent + "\n\n" + backlinkSection;

            writeToFile(targetTitle, updatedContent);
        }

        if (!backlinks.isEmpty()) {
            logger.info("双向链接处理完成，涉及 {} 个页面", backlinks.size());
        }
    }

    /**
     * 提取 Markdown 内容中的所有 [[链接]]
     */
    public Set<String> extractLinks(String content) {
        if (content == null || content.isEmpty()) return Collections.emptySet();
        Set<String> links = new LinkedHashSet<>();
        Matcher matcher = LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            links.add(matcher.group(1).trim());
        }
        return links;
    }

    /**
     * 写入文件系统：data/wiki/{title}.md
     */
    private void writeToFile(String title, String content) {
        try {
            Path dir = Paths.get(wikiStoragePath);
            Files.createDirectories(dir);
            String safeFileName = title.replaceAll("[\\\\/:*?\"<>|]", "_") + ".md";
            Files.writeString(dir.resolve(safeFileName), content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.error("写入 Wiki 文件失败: title={}", title, e);
        }
    }

    /**
     * 扫描文件系统，读取所有 .md 文件
     */
     Map<String, Path> findAllPages() {
        Map<String, Path> pages = new LinkedHashMap<>();
        try {
            Path dir = Paths.get(wikiStoragePath);
            if (!Files.exists(dir)) {
                logger.warn("Wiki 存储目录不存在，将无法检索页面: path={}, 当前工作目录={}",
                        dir.toAbsolutePath(), System.getProperty("user.dir"));
                return pages;
            }
            try (var files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".md")).forEach(f -> {
                    String name = f.getFileName().toString();
                    String title = name.substring(0, name.length() - 3);
                    pages.put(title, f);
                });
            }
        } catch (IOException e) {
            logger.error("扫描 Wiki 目录失败", e);
        }
        return pages;
    }

    /**
     * 读取所有 Wiki 文件的内容到内存
     */
     Map<String, String> readAllContents(Map<String, Path> pages) {
        Map<String, String> contentMap = new HashMap<>();
        for (Map.Entry<String, Path> entry : pages.entrySet()) {
            try {
                contentMap.put(entry.getKey(), Files.readString(entry.getValue()));
            } catch (IOException e) {
                logger.warn("读取 Wiki 文件失败: {}", entry.getKey(), e);
            }
        }
        return contentMap;
    }

    /**
     * 移除内容末尾的「被引用」段落（如果存在），避免重复追加
     */
    private String removeBacklinkSection(String content) {
        int idx = content.lastIndexOf("\n## 被引用\n");
        return idx >= 0 ? content.substring(0, idx) : content;
    }

    /**
     * 构建反向引用段落
     */
    private String buildBacklinkSection(Set<String> sourceTitles) {
        StringBuilder sb = new StringBuilder("## 被引用\n\n");
        sb.append("本文被以下页面引用：\n");
        for (String source : sourceTitles) {
            sb.append("- [[").append(source).append("]]\n");
        }
        return sb.toString();
    }

    /**
     * 获取所有 Wiki 页面的标题和内容
     */
    public Map<String, String> getAllPageContents() {
        Map<String, Path> allPages = findAllPages();
        return readAllContents(allPages);
    }
}
