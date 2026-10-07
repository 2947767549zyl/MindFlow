package com.mindflow.module.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.mindflow.client.EmbeddingClient;
import com.mindflow.module.search.dto.EsDocument;
import com.mindflow.module.search.dto.SearchResult;
import com.mindflow.module.auth.entity.User;
import com.mindflow.common.exception.CustomException;
import com.mindflow.module.auth.repository.UserRepository;
import com.mindflow.module.document.repository.FileUploadRepository;
import com.mindflow.module.document.entity.FileUpload;
import com.mindflow.module.organization.service.OrgTagCacheService;
import com.mindflow.module.auth.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合搜索服务，结合文本匹配和向量相似度搜索
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    /**
     * 使用文本匹配和向量相似度进行混合搜索，支持权限过滤
     * 该方法确保用户只能搜索其有权限访问的文档（自己的文档、公开文档、所属组织的文档）
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限搜索，查询: {}, 用户ID: {}", query, userId);
        
        try {
            // 获取用户有效的组织标签（包含层级关系）
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            // 获取用户的数据库ID用于权限过滤
            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query, userId);

            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            }

            logger.debug("向量生成成功，开始执行混合搜索 KNN");

            SearchResponse<EsDocument> response = esClient.search(s -> {
                        s.index("knowledge_base");
                        // KNN 召回
                        int recallK = topK * 30; // KNN 召回窗口
                        s.knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK)
                        );
                        // 必须命中关键词 + 权限过滤
                        s.query(q -> q.bool(b -> b
                                .must(mst -> mst.match(m -> m.field("textContent").query(query)))
                                .filter(f -> f.bool(bf -> bf
                                        // 条件1: 用户可访问自己的文档
                                        .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                                        // 条件2: 公开文档
                                        .should(s2 -> s2.term(t -> t.field("public").value(true)))
                                        // 条件3: 组织标签
                                        .should(s3 -> {
                                            if (userEffectiveTags.isEmpty()) {
                                                return s3.matchNone(mn -> mn);
                                            } else if (userEffectiveTags.size() == 1) {
                                                return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                                            } else {
                                                return s3.bool(inner -> {
                                                    userEffectiveTags.forEach(tag -> inner.should(sh2 -> sh2.term(t -> t.field("orgTag").value(tag))));
                                                    return inner;
                                                });
                                            }
                                        })
                                ))
                        ));

                        // 第二阶段 BM25 rescore
                        s.rescore(r -> r
                                .windowSize(recallK)
                                .query(rq -> rq
                                        .queryWeight(0.2d)               // 保留部分 KNN 分
                                        .rescoreQueryWeight(1.0d)        // BM25 主导
                                        .query(rqq -> rqq.match(m -> m
                                                .field("textContent")
                                                .query(query)
                                                .operator(Operator.And)
                                        ))
                                )
                        );
                        s.size(topK);
                        return s;
                    }, EsDocument.class);

            logger.debug("Elasticsearch查询执行完成，命中数量: {}, 最大分数: {}", 
                response.hits().total().value(), response.hits().maxScore());

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        logger.debug("搜索结果 - 文件: {}, 块: {}, 分数: {}, 内容: {}", 
                            hit.source().getFileMd5(), hit.source().getChunkId(), hit.score(), 
                            hit.source().getTextContent().substring(0, Math.min(50, hit.source().getTextContent().length())));
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score(),
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null,
                                hit.source().getPageNumber(),
                                hit.source().getAnchorText(),
                                "HYBRID",
                                hit.source().getTextContent()
                        );
                    })
                    .toList();

            logger.debug("返回搜索结果数量: {}", results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("带权限的搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearchWithPermission(query, getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    /**
     * 仅使用文本匹配的带权限搜索方法
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId, List<String> userEffectiveTags, int topK) {
        try {
            logger.debug("开始执行纯文本搜索，用户数据库ID: {}, 标签: {}", userDbId, userEffectiveTags);

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q
                            .bool(b -> b
                                    // 匹配内容相关性
                                    .must(m -> m
                                            .match(ma -> ma
                                                    .field("textContent")
                                                    .query(query)
                                            )
                                    )
                                    // 权限过滤
                                    .filter(f -> f
                                            .bool(bf -> bf
                                                    // 条件1: 用户可以访问自己的文档
                                                    .should(s1 -> s1
                                                            .term(t -> t
                                                                    .field("userId")
                                                                    .value(userDbId)
                                                            )
                                                    )
                                                    // 条件2: 用户可以访问公开的文档
                                                    .should(s2 -> s2
                                                            .term(t -> t
                                                                    .field("public")
                                                                    .value(true)
                                                            )
                                                    )
                                                    // 条件3: 用户可以访问其所属组织的文档（包含层级关系）
                                                    .should(s3 -> {
                                                        if (userEffectiveTags.isEmpty()) {
                                                            return s3.matchNone(mn -> mn);
                                                        } else if (userEffectiveTags.size() == 1) {
                                                            // 单个标签使用 term 查询
                                                            return s3.term(t -> t
                                                                    .field("orgTag")
                                                                    .value(userEffectiveTags.get(0))
                                                            );
                                                        } else {
                                                            // 多个标签使用 bool should 组合多个 term 查询
                                                            return s3.bool(innerBool -> {
                                                                userEffectiveTags.forEach(tag ->
                                                                        innerBool.should(sh -> sh.term(t -> t
                                                                                .field("orgTag")
                                                                                .value(tag)
                                                                        ))
                                                                );
                                                                return innerBool;
                                                            });
                                                        }
                                                    })
                                            )
                                    )
                            )
                    )
                    .minScore(0.3d)
                    .size(topK),
                    EsDocument.class
            );

            logger.debug("纯文本查询执行完成，命中数量: {}, 最大分数: {}", 
                response.hits().total().value(), response.hits().maxScore());

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        logger.debug("纯文本搜索结果 - 文件: {}, 块: {}, 分数: {}, 内容: {}", 
                            hit.source().getFileMd5(), hit.source().getChunkId(), hit.score(), 
                            hit.source().getTextContent().substring(0, Math.min(50, hit.source().getTextContent().length())));
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score(),
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null,
                                hit.source().getPageNumber(),
                                hit.source().getAnchorText(),
                                "TEXT_ONLY",
                                hit.source().getTextContent()
                        );
                    })
                    .toList();

            logger.debug("返回纯文本搜索结果数量: {}", results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.debug("开始混合检索，查询: {}, topK: {}", query, topK);
            logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query, "system");
            
            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearch(query, topK);
            }

            SearchResponse<EsDocument> response = esClient.search(s -> {
                        s.index("knowledge_base");
                        int recallK = topK * 30;
                        s.knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK)
                        );

                        // 过滤仅保留包含关键词的文本
                        s.query(q -> q.match(m -> m.field("textContent").query(query)));

                        // rescore BM25
                        s.rescore(r -> r
                                .windowSize(recallK)
                                .query(rq -> rq
                                        .queryWeight(0.2d)
                                        .rescoreQueryWeight(1.0d)
                                        .query(rqq -> rqq.match(m -> m
                                                .field("textContent")
                                                .query(query)
                                                .operator(Operator.And)
                                        ))
                                )
                        );
                        s.size(topK);
                        return s;
                    }, EsDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score(),
                                null,
                                null,
                                false,
                                null,
                                hit.source().getPageNumber(),
                                hit.source().getAnchorText(),
                                "HYBRID",
                                hit.source().getTextContent()
                        );
                    })
                    .toList();
        } catch (Exception e) {
            logger.error("搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    /**
     * 仅使用文本匹配的搜索方法
     */
    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index("knowledge_base")
                .query(q -> q
                        .match(m -> m
                                .field("textContent")
                                .query(query)
                        )
                )
                .size(topK),
                EsDocument.class
        );

        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score(),
                            null,
                            null,
                            false,
                            null,
                            hit.source().getPageNumber(),
                            hit.source().getAnchorText(),
                            "TEXT_ONLY",
                            hit.source().getTextContent()
                    );
                })
                .toList();
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text), requesterId, EmbeddingClient.UsageType.QUERY);
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }
    
    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}", user.getUsername());
            }
            
            // 通过orgTagCacheService获取用户的有效标签集合
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList(); // 返回空列表作为默认值
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
                return userIdLong.toString(); // 如果输入已经是数字ID，直接返回
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}, ID: {}", user.getUsername(), user.getId());
                return user.getId().toString(); // 返回用户的数据库ID
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            // 收集所有唯一的 fileMd5
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new java.util.ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName, (existing, replacement) -> existing));
            // 填充文件名
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }

    // ========== 新增：KNN + BM25 并行召回 + RRF 融合 ==========

    private static final int RRF_CONSTANT_K = 60;

    /**
     * KNN-only搜索（仅向量相似度 + 权限过滤，不含BM25约束）
     */
    private List<SearchResult> knnSearchWithPermission(String query, String userId, int topK) {
        try {
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            String userDbId = getUserDbId(userId);
            List<Float> queryVector = embedToVectorList(query, userId);
            if (queryVector == null) {
                logger.warn("KNN搜索向量生成失败，返回空结果");
                return Collections.emptyList();
            }

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base")
                        .knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(topK)
                                .numCandidates(topK * 3)
                        )
                        .query(q -> q.bool(b -> b
                                .filter(f -> f.bool(bf -> bf
                                        .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                                        .should(s2 -> s2.term(t -> t.field("public").value(true)))
                                        .should(s3 -> {
                                            if (userEffectiveTags.isEmpty()) {
                                                return s3.matchNone(mn -> mn);
                                            } else if (userEffectiveTags.size() == 1) {
                                                return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                                            } else {
                                                return s3.bool(inner -> {
                                                    userEffectiveTags.forEach(tag -> inner.should(sh2 -> sh2.term(t -> t.field("orgTag").value(tag))));
                                                    return inner;
                                                });
                                            }
                                        })
                                ))
                        ))
                        .size(topK);
                return s;
            }, EsDocument.class);

            return toSearchResults(response, "KNN");
        } catch (Exception e) {
            logger.error("KNN搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * BM25-only搜索（仅关键词匹配 + 权限过滤）
     */
    private List<SearchResult> bm25SearchWithPermission(String query, String userId, int topK) {
        try {
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            String userDbId = getUserDbId(userId);

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base")
                        .query(q -> q.bool(b -> b
                                .must(m -> m.match(ma -> ma.field("textContent").query(query)))
                                .filter(f -> f.bool(bf -> bf
                                        .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                                        .should(s2 -> s2.term(t -> t.field("public").value(true)))
                                        .should(s3 -> {
                                            if (userEffectiveTags.isEmpty()) {
                                                return s3.matchNone(mn -> mn);
                                            } else if (userEffectiveTags.size() == 1) {
                                                return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                                            } else {
                                                return s3.bool(inner -> {
                                                    userEffectiveTags.forEach(tag -> inner.should(sh2 -> sh2.term(t -> t.field("orgTag").value(tag))));
                                                    return inner;
                                                });
                                            }
                                        })
                                ))
                        ))
                        .size(topK);
                return s;
            }, EsDocument.class);

            return toSearchResults(response, "TEXT_ONLY");
        } catch (Exception e) {
            logger.error("BM25搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * RRF 融合：对KNN和BM25的结果进行 Reciprocal Rank Fusion
     */
    public List<SearchResult> rrfFusion(List<SearchResult> knnResults,
                                        List<SearchResult> bm25Results,
                                        int topK) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, SearchResult> resultMap = new HashMap<>();

        for (int i = 0; i < knnResults.size(); i++) {
            String key = buildResultKey(knnResults.get(i));
            rrfScores.put(key, 1.0 / (RRF_CONSTANT_K + i));
            resultMap.put(key, knnResults.get(i));
        }

        for (int i = 0; i < bm25Results.size(); i++) {
            String key = buildResultKey(bm25Results.get(i));
            rrfScores.merge(key, 1.0 / (RRF_CONSTANT_K + i), Double::sum);
            resultMap.putIfAbsent(key, bm25Results.get(i));
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    SearchResult original = resultMap.get(entry.getKey());
                    SearchResult copy = new SearchResult(
                            original.getFileMd5(),
                            original.getChunkId(),
                            original.getTextContent(),
                            entry.getValue(),
                            original.getUserId(),
                            original.getOrgTag(),
                            original.getIsPublic() != null && original.getIsPublic(),
                            null,
                            original.getPageNumber(),
                            original.getAnchorText(),
                            "HYBRID_RRF",
                            original.getMatchedChunkText()
                    );
                    return copy;
                })
                .collect(Collectors.toList());
    }

    /**
     * 三阶段搜索：KNN + BM25 并行召回 → RRF 融合
     * （Rerank 阶段由 RerankService 单独完成）
     */
    public List<SearchResult> searchWithRrfRerank(String query, String userId,
                                                  int knnK, int bm25K, int rrfTopK) {
        logger.info("开始三阶段搜索: query={}, userId={}, knnK={}, bm25K={}, rrfTopK={}",
                query, userId, knnK, bm25K, rrfTopK);

        List<SearchResult> knnResults = knnSearchWithPermission(query, userId, knnK);
        List<SearchResult> bm25Results = bm25SearchWithPermission(query, userId, bm25K);

        logger.info("并行召回完成: KNN={}条, BM25={}条", knnResults.size(), bm25Results.size());

        List<SearchResult> rrfResults = rrfFusion(knnResults, bm25Results, rrfTopK);

        logger.info("RRF融合完成: {}条", rrfResults.size());

        attachFileNames(rrfResults);
        return rrfResults;
    }

    private String buildResultKey(SearchResult result) {
        return result.getFileMd5() + "_" + result.getChunkId();
    }

    private List<SearchResult> toSearchResults(SearchResponse<EsDocument> response, String retrievalMode) {
        if (response.hits() == null || response.hits().hits() == null) {
            return Collections.emptyList();
        }
        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score(),
                            hit.source().getUserId(),
                            hit.source().getOrgTag(),
                            hit.source().isPublic(),
                            null,
                            hit.source().getPageNumber(),
                            hit.source().getAnchorText(),
                            retrievalMode,
                            hit.source().getTextContent()
                    );
                })
                .collect(Collectors.toList());
    }
}



