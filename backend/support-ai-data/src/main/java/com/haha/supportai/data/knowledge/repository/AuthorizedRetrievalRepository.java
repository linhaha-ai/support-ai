package com.haha.supportai.data.knowledge.repository;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.AuthorizedRetrievalHit;
import com.haha.supportai.data.knowledge.mapper.AuthorizedRetrievalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuthorizedRetrievalRepository {

    private final AuthorizedRetrievalMapper authorizedRetrievalMapper;

    public List<AuthorizedRetrievalHit> searchWritable(UUID userId, UUID knowledgeBaseId, float[] queryEmbedding,
                                                        int topK, String embeddingProvider, String embeddingModel,
                                                        int dimensions, String processingConfigHash) {
        return authorizedRetrievalMapper.selectWritable(userId, knowledgeBaseId, queryEmbedding, topK,
                embeddingProvider, embeddingModel, dimensions, processingConfigHash);
    }

    public List<AuthorizedRetrievalHit> searchWritableByKeywords(UUID userId, UUID knowledgeBaseId,
                                                                  List<String> keywordPatterns, int topK,
                                                                  String embeddingProvider, String embeddingModel,
                                                                  int dimensions, String processingConfigHash) {
        if (keywordPatterns.isEmpty()) {
            return List.of();
        }
        return authorizedRetrievalMapper.selectWritableByKeywords(userId, knowledgeBaseId, keywordPatterns, topK,
                embeddingProvider, embeddingModel, dimensions, processingConfigHash);
    }

    public List<AuthorizedRetrievalHit> searchReadableWithinSession(UUID userId, UUID sessionId,
                                                                      float[] queryEmbedding, int topK,
                                                                      String embeddingProvider, String embeddingModel,
                                                                      int dimensions, String processingConfigHash) {
        return authorizedRetrievalMapper.selectReadableWithinSession(userId, sessionId, queryEmbedding, topK,
                embeddingProvider, embeddingModel, dimensions, processingConfigHash);
    }

    public List<AuthorizedRetrievalHit> searchReadableWithinSessionByKeywords(UUID userId, UUID sessionId,
                                                                                List<String> keywordPatterns, int topK,
                                                                                String embeddingProvider,
                                                                                String embeddingModel,
                                                                                int dimensions,
                                                                                String processingConfigHash) {
        if (keywordPatterns.isEmpty()) {
            return List.of();
        }
        return authorizedRetrievalMapper.selectReadableWithinSessionByKeywords(userId, sessionId, keywordPatterns,
                topK, embeddingProvider, embeddingModel, dimensions, processingConfigHash);
    }

    public List<AuthorizedRetrievalHit> revalidateReadableWithinSession(UUID userId, UUID sessionId,
                                                                          List<UUID> chunkIds,
                                                                          String embeddingProvider,
                                                                          String embeddingModel,
                                                                          int dimensions,
                                                                          String processingConfigHash) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        return authorizedRetrievalMapper.revalidateReadableWithinSession(userId, sessionId, chunkIds,
                embeddingProvider, embeddingModel, dimensions, processingConfigHash);
    }
}
