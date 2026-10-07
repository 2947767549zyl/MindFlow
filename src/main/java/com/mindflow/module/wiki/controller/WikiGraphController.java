package com.mindflow.module.wiki.controller;

import com.mindflow.module.wiki.service.WikiSearchService;
import com.mindflow.utils.JwtUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Wiki 知识图谱接口：节点=概念页，边=正向 [[关联概念]]，供前端力导向图渲染。
 */
@RestController
@RequestMapping("/api/v1/wiki")
public class WikiGraphController {

    private final WikiSearchService wikiSearchService;
    private final JwtUtils jwtUtils;

    public WikiGraphController(WikiSearchService wikiSearchService, JwtUtils jwtUtils) {
        this.wikiSearchService = wikiSearchService;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping("/graph")
    public ResponseEntity<?> graph(@RequestHeader("Authorization") String token) {
        jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));

        Map<String, String> pages = wikiSearchService.getAllPageContents();
        Map<String, List<String>> forwardLinks = wikiSearchService.allForwardLinks();

        Map<String, Integer> degree = new HashMap<>();
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> seenEdges = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : forwardLinks.entrySet()) {
            String source = entry.getKey();
            for (String target : entry.getValue()) {
                if (target == null || !pages.containsKey(target) || source.equals(target)) {
                    continue;
                }
                if (!seenEdges.add(source + "\u0000" + target)) {
                    continue;
                }
                links.add(Map.of("source", source, "target", target));
                degree.merge(source, 1, Integer::sum);
                degree.merge(target, 1, Integer::sum);
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String title : pages.keySet()) {
            int nodeDegree = degree.getOrDefault(title, 0);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", title);
            node.put("title", title);
            node.put("degree", nodeDegree);
            node.put("isolated", nodeDegree == 0);
            nodes.add(node);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodes", nodes);
        data.put("links", links);
        data.put("nodeCount", nodes.size());
        data.put("linkCount", links.size());
        return ok("success", data);
    }

    @GetMapping("/page")
    public ResponseEntity<?> page(@RequestHeader("Authorization") String token,
                                  @RequestParam("title") String title) {
        jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));

        Optional<WikiSearchService.WikiMultiHopResult> result = wikiSearchService.navigateWithHops(title, 1);
        if (result.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "code", 404,
                    "message", "未找到该 Wiki 页面: " + title,
                    "data", Map.of()));
        }

        WikiSearchService.WikiMultiHopResult page = result.get();
        List<Map<String, Object>> related = new ArrayList<>();
        for (WikiSearchService.WikiLinkPage linkPage : page.related()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", linkPage.title());
            item.put("markdown", linkPage.markdown());
            related.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", page.title());
        data.put("markdown", page.markdown());
        data.put("backlinks", page.backlinks());
        data.put("related", related);
        return ok("success", data);
    }

    private ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", message);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}
