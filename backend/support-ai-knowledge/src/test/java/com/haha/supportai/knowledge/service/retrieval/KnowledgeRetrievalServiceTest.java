package com.haha.supportai.knowledge.service.retrieval;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.ai.config.RerankProperties;
import com.haha.supportai.ai.entity.dto.RerankDocumentScore;
import com.haha.supportai.ai.entity.dto.RerankModelResult;
import com.haha.supportai.ai.exception.RerankInputLimitException;
import com.haha.supportai.ai.exception.RerankUnavailableException;
import com.haha.supportai.ai.model.ValidatedEmbeddingModel;
import com.haha.supportai.ai.rerank.RerankModelAdapter;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.knowledge.entity.po.AuthorizedRetrievalHit;
import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import com.haha.supportai.data.knowledge.repository.AuthorizedRetrievalRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.config.RetrievalProperties;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.support.DocumentProcessingSnapshotFactory;
import com.haha.supportai.knowledge.entity.dto.KnowledgeBaseAccessContext;
import com.haha.supportai.knowledge.entity.dto.KnowledgeRetrievalTestRequest;
import com.haha.supportai.knowledge.entity.enums.RerankStatusEnum;
import com.haha.supportai.knowledge.entity.enums.RetrievalStrategyEnum;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.exception.KnowledgeRetrievalInvalidRequestException;
import com.haha.supportai.knowledge.exception.KnowledgeRetrievalUnavailableException;
import com.haha.supportai.knowledge.support.RetrievalKeywordExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final String PROCESSING_CONFIG_HASH = "config-hash";

    @Mock
    EmbeddingModel embeddingModel;

    @Mock
    AuthorizedRetrievalRepository authorizedRetrievalRepository;

    @Mock
    AccessPolicy accessPolicy;

    @Mock
    DocumentProcessingSnapshotFactory documentProcessingSnapshotFactory;

    @Mock
    RerankModelAdapter rerankModelAdapter;

    @Test
    void writableTestChecksWritePermissionBeforeCreatingOneQueryEmbedding() {
        float[] embedding = embedding();
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenReturn(writeAccess());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("退款状态")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 3,
                "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH)).thenReturn(List.of(hit()));

        var response = service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request("  退款状态  ", 3));

        assertThat(response.getQuery()).isEqualTo("退款状态");
        assertThat(response.getStrategy()).isEqualTo(RetrievalStrategyEnum.VECTOR);
        assertThat(response.getVectorCandidateCount()).isEqualTo(1);
        assertThat(response.getKeywordCandidateCount()).isZero();
        assertThat(response.getRerankStatus()).isEqualTo(RerankStatusEnum.DISABLED);
        assertThat(response.getRerankCandidateCount()).isZero();
        assertThat(response.getMatches()).hasSize(1);
        assertThat(response.getMatches().get(0).getDocumentName()).isEqualTo("退款排查手册");
        assertThat(response.getDurationMs()).isGreaterThanOrEqualTo(0);
        verify(accessPolicy).requireWrite(KNOWLEDGE_BASE_ID);
        verify(embeddingModel).embed("退款状态");
        verify(authorizedRetrievalRepository).searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 3,
                "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH);
    }

    @Test
    void rejectedWritePermissionDoesNotCallEmbeddingModelOrRepository() {
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenThrow(new KnowledgeBaseAccessDeniedException());

        assertThatThrownBy(() -> service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request("退款状态", 3)))
                .isInstanceOf(KnowledgeBaseAccessDeniedException.class);

        verify(embeddingModel, never()).embed(any(String.class));
        verify(authorizedRetrievalRepository, never()).searchWritable(any(), any(), any(float[].class), anyInt(),
                anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void emptyAuthorizedResultReturnsSuccessfulEmptyMatches() {
        float[] embedding = embedding();
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenReturn(writeAccess());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("退款状态")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 3,
                "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH)).thenReturn(List.of());

        var response = service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request("退款状态", 3));

        assertThat(response.getMatches()).isEmpty();
    }

    @Test
    void fusionUsesExpandedCandidatesAndDeduplicatesByRrf() {
        float[] embedding = embedding();
        AuthorizedRetrievalHit vectorOnly = hit();
        vectorOnly.setChunkId(UUID.fromString("550e8400-e29b-41d4-a716-446655440013"));
        vectorOnly.setDocumentName("向量候选");
        AuthorizedRetrievalHit shared = hit();
        shared.setDocumentName("精确错误码候选");
        AuthorizedRetrievalHit keywordOnly = hit();
        keywordOnly.setChunkId(UUID.fromString("550e8400-e29b-41d4-a716-446655440014"));
        keywordOnly.setDocumentName("关键词候选");
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenReturn(writeAccess());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("PAY-CB-1001 是什么错误？")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 6,
                "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH)).thenReturn(List.of(vectorOnly, shared));
        when(authorizedRetrievalRepository.searchWritableByKeywords(
                any(), any(), anyList(), anyInt(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(List.of(shared, keywordOnly));

        KnowledgeRetrievalTestRequest request = request("PAY-CB-1001 是什么错误？", 2);
        request.setStrategy(RetrievalStrategyEnum.FUSION);
        var response = service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request);

        assertThat(response.getStrategy()).isEqualTo(RetrievalStrategyEnum.FUSION);
        assertThat(response.getVectorCandidateCount()).isEqualTo(2);
        assertThat(response.getKeywordCandidateCount()).isEqualTo(2);
        assertThat(response.getMatches()).extracting(match -> match.getDocumentName())
                .containsExactly("精确错误码候选", "向量候选");
        assertThat(response.getMatches().get(0).getScore()).isGreaterThan(response.getMatches().get(1).getScore());
        verify(authorizedRetrievalRepository).searchWritableByKeywords(any(), any(), anyList(),
                anyInt(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void fusionWithoutExplicitKeywordKeepsSemanticRankingAndSkipsKeywordQuery() {
        float[] embedding = embedding();
        AuthorizedRetrievalHit first = hit();
        AuthorizedRetrievalHit second = hit();
        second.setChunkId(UUID.fromString("550e8400-e29b-41d4-a716-446655440013"));
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenReturn(writeAccess());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("退款状态为什么没有更新")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 3,
                "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH)).thenReturn(List.of(first, second));

        KnowledgeRetrievalTestRequest request = request("退款状态为什么没有更新", 1);
        request.setStrategy(RetrievalStrategyEnum.FUSION);
        var response = service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request);

        assertThat(response.getMatches()).extracting(match -> match.getChunkId())
                .containsExactly(first.getChunkId());
        assertThat(response.getKeywordCandidateCount()).isZero();
        verify(authorizedRetrievalRepository, never()).searchWritableByKeywords(
                any(), any(), anyList(), anyInt(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void enabledRerankExpandsCandidatesAndOnlyReordersAuthorizedHits() {
        float[] embedding = embedding();
        AuthorizedRetrievalHit first = hit();
        AuthorizedRetrievalHit second = hit();
        second.setChunkId(UUID.fromString("550e8400-e29b-41d4-a716-446655440013"));
        second.setDocumentName("第二候选");
        second.setContent("第二条资料");
        AuthorizedRetrievalHit third = hit();
        third.setChunkId(UUID.fromString("550e8400-e29b-41d4-a716-446655440014"));
        third.setDocumentName("第三候选");
        third.setContent("第三条资料");
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenReturn(writeAccess());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("如何处理复杂退款？")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 6,
                "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH)).thenReturn(List.of(first, second, third));
        when(rerankModelAdapter.rerank("如何处理复杂退款？",
                List.of(first.getContent(), second.getContent(), third.getContent()), 2))
                .thenReturn(new RerankModelResult(List.of(
                        new RerankDocumentScore(2, 0.95D),
                        new RerankDocumentScore(0, 0.80D)), 37));

        var response = service(enabledRerank()).testWritableKnowledgeBase(
                KNOWLEDGE_BASE_ID, request("如何处理复杂退款？", 2));

        assertThat(response.getMatches()).extracting(match -> match.getChunkId())
                .containsExactly(third.getChunkId(), first.getChunkId());
        assertThat(response.getMatches()).extracting(match -> match.getScore())
                .containsExactly(0.95D, 0.80D);
        assertThat(response.getRerankStatus()).isEqualTo(RerankStatusEnum.APPLIED);
        assertThat(response.getRerankCandidateCount()).isEqualTo(3);
        assertThat(response.getRerankTotalTokens()).isEqualTo(37);
    }

    @Test
    void emptyCandidatesSkipRerankCall() {
        float[] embedding = embedding();
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenReturn(writeAccess());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("没有候选的问题")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 6,
                "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH)).thenReturn(List.of());

        var response = service(enabledRerank()).testWritableKnowledgeBase(
                KNOWLEDGE_BASE_ID, request("没有候选的问题", 2));

        assertThat(response.getMatches()).isEmpty();
        assertThat(response.getRerankStatus()).isEqualTo(RerankStatusEnum.SKIPPED_NO_CANDIDATES);
        verify(rerankModelAdapter, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void unavailableRerankFallsBackToBaseOrder() {
        RerankProperties properties = enabledRerank();
        List<AuthorizedRetrievalHit> candidates = prepareRerankCandidates("模型不可用时怎么办？");
        when(rerankModelAdapter.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new RerankUnavailableException(new IllegalStateException("unavailable")));

        var response = service(properties).testWritableKnowledgeBase(
                KNOWLEDGE_BASE_ID, request("模型不可用时怎么办？", 2));

        assertThat(response.getMatches()).extracting(match -> match.getChunkId())
                .containsExactly(candidates.get(0).getChunkId(), candidates.get(1).getChunkId());
        assertThat(response.getRerankStatus()).isEqualTo(RerankStatusEnum.FALLBACK_UNAVAILABLE);
        assertThat(response.getRerankCandidateCount()).isEqualTo(3);
    }

    @Test
    void unavailableRerankRestoresOriginalFusionOrderInsteadOfExpandedCandidateOrder() {
        float[] embedding = embedding();
        AuthorizedRetrievalHit vectorFirst = hit(
                "550e8400-e29b-41d4-a716-446655440101", "向量第一", "向量第一资料");
        AuthorizedRetrievalHit keywordFirst = hit(
                "550e8400-e29b-41d4-a716-446655440201", "关键词第一", "关键词第一资料");
        AuthorizedRetrievalHit shared = hit(
                "550e8400-e29b-41d4-a716-446655440301", "扩大候选", "扩大候选资料");
        List<AuthorizedRetrievalHit> vectorHits = List.of(
                vectorFirst,
                hit("550e8400-e29b-41d4-a716-446655440102", "向量第二", "向量第二资料"),
                hit("550e8400-e29b-41d4-a716-446655440103", "向量第三", "向量第三资料"),
                shared);
        List<AuthorizedRetrievalHit> keywordHits = List.of(
                keywordFirst,
                hit("550e8400-e29b-41d4-a716-446655440202", "关键词第二", "关键词第二资料"),
                hit("550e8400-e29b-41d4-a716-446655440203", "关键词第三", "关键词第三资料"),
                shared);
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenReturn(writeAccess());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("PAY-CB-1001 是什么错误？")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 9,
                "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH)).thenReturn(vectorHits);
        when(authorizedRetrievalRepository.searchWritableByKeywords(
                any(), any(), anyList(), anyInt(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(keywordHits);
        when(rerankModelAdapter.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new RerankUnavailableException(new IllegalStateException("unavailable")));
        KnowledgeRetrievalTestRequest request = request("PAY-CB-1001 是什么错误？", 1);
        request.setStrategy(RetrievalStrategyEnum.FUSION);

        var response = service(enabledRerank()).testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request);

        assertThat(response.getMatches()).extracting(match -> match.getChunkId())
                .containsExactly(vectorFirst.getChunkId());
        assertThat(response.getRerankStatus()).isEqualTo(RerankStatusEnum.FALLBACK_UNAVAILABLE);
        verify(rerankModelAdapter).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void overLimitRerankFallsBackWithoutLosingCandidates() {
        List<AuthorizedRetrievalHit> candidates = prepareRerankCandidates("超限时怎么办？");
        when(rerankModelAdapter.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new RerankInputLimitException());

        var response = service(enabledRerank()).testWritableKnowledgeBase(
                KNOWLEDGE_BASE_ID, request("超限时怎么办？", 2));

        assertThat(response.getMatches()).extracting(match -> match.getChunkId())
                .containsExactly(candidates.get(0).getChunkId(), candidates.get(1).getChunkId());
        assertThat(response.getRerankStatus()).isEqualTo(RerankStatusEnum.SKIPPED_INPUT_LIMIT);
        assertThat(response.getRerankCandidateCount()).isEqualTo(3);
    }

    @Test
    void malformedRerankOutputFallsBackToBaseOrder() {
        List<AuthorizedRetrievalHit> candidates = prepareRerankCandidates("异常输出时怎么办？");
        when(rerankModelAdapter.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(new RerankModelResult(List.of(
                        new RerankDocumentScore(0, 0.9D),
                        new RerankDocumentScore(0, 0.8D)), 10));

        var response = service(enabledRerank()).testWritableKnowledgeBase(
                KNOWLEDGE_BASE_ID, request("异常输出时怎么办？", 2));

        assertThat(response.getMatches()).extracting(match -> match.getChunkId())
                .containsExactly(candidates.get(0).getChunkId(), candidates.get(1).getChunkId());
        assertThat(response.getRerankStatus()).isEqualTo(RerankStatusEnum.FALLBACK_INVALID_RESPONSE);
        assertThat(response.getRerankCandidateCount()).isEqualTo(3);
    }

    @Test
    void readableSessionRetrievalUsesOnlyServerSideSessionIdentifierAndCurrentUser() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        float[] embedding = embedding();
        when(accessPolicy.currentUser()).thenReturn(new AuthenticatedUser(USER_ID, "support", "支持用户", UserRoleEnum.USER));
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("退款状态")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchReadableWithinSession(USER_ID, sessionId,
                embedding, 2, "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH)).thenReturn(List.of(hit()));

        var matches = service().retrieveReadableWithinSession(sessionId, "退款状态", 2);

        assertThat(matches).hasSize(1);
        verify(authorizedRetrievalRepository).searchReadableWithinSession(USER_ID, sessionId,
                embedding, 2, "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH);
    }

    @Test
    void backgroundRetrievalUsesTrustedUserWithoutReadingRequestContext() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        float[] embedding = embedding();
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("退款状态")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchReadableWithinSession(USER_ID, sessionId,
                embedding, 2, "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH)).thenReturn(List.of(hit()));

        var matches = service().retrieveReadableWithinSession(USER_ID, sessionId, "退款状态", 2);

        assertThat(matches).hasSize(1);
        verify(accessPolicy, never()).currentUser();
        verify(authorizedRetrievalRepository).searchReadableWithinSession(USER_ID, sessionId,
                embedding, 2, "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH);
    }

    @Test
    void nullSessionIsRejectedBeforeEmbeddingModel() {
        assertThatThrownBy(() -> service().retrieveReadableWithinSession(null, "退款状态", 2))
                .isInstanceOf(KnowledgeRetrievalInvalidRequestException.class);

        verify(embeddingModel, never()).embed(any(String.class));
        verify(authorizedRetrievalRepository, never()).searchReadableWithinSession(any(), any(), any(float[].class), anyInt(),
                anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void revalidatesCandidatesWithoutEmbeddingAndKeepsFirstCandidateOrder() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        AuthorizedRetrievalHit first = hit();
        AuthorizedRetrievalHit second = hit();
        UUID secondChunkId = UUID.fromString("550e8400-e29b-41d4-a716-446655440013");
        second.setChunkId(secondChunkId);
        second.setDocumentName("支付排查手册");
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(authorizedRetrievalRepository.revalidateReadableWithinSession(USER_ID, sessionId,
                List.of(first.getChunkId(), secondChunkId), "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH))
                .thenReturn(List.of(second, first));

        var matches = service().revalidateReadableWithinSession(sessionId,
                List.of(first.getChunkId(), secondChunkId, first.getChunkId()));

        assertThat(matches).extracting(match -> match.getChunkId())
                .containsExactly(first.getChunkId(), secondChunkId);
        verify(embeddingModel, never()).embed(any(String.class));
        verify(authorizedRetrievalRepository).revalidateReadableWithinSession(USER_ID, sessionId,
                List.of(first.getChunkId(), secondChunkId), "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH);
    }

    @Test
    void backgroundRevalidationUsesTrustedUserWithoutReadingRequestContext() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        AuthorizedRetrievalHit hit = hit();
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(authorizedRetrievalRepository.revalidateReadableWithinSession(USER_ID, sessionId,
                List.of(hit.getChunkId()), "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH)).thenReturn(List.of(hit));

        var matches = service().revalidateReadableWithinSession(USER_ID, sessionId, List.of(hit.getChunkId()));

        assertThat(matches).hasSize(1);
        verify(accessPolicy, never()).currentUser();
    }

    @Test
    void emptyRevalidationDoesNotLoadUserOrModelConfiguration() {
        assertThat(service().revalidateReadableWithinSession(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440002"), List.of())).isEmpty();

        verify(accessPolicy, never()).currentUser();
        verify(documentProcessingSnapshotFactory, never()).create();
        verify(embeddingModel, never()).embed(any(String.class));
    }

    @Test
    void invalidRevalidationCandidatesAreRejectedBeforeRepositoryAccess() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

        assertThatThrownBy(() -> service().revalidateReadableWithinSession(null, List.of()))
                .isInstanceOf(KnowledgeRetrievalInvalidRequestException.class);
        assertThatThrownBy(() -> service().revalidateReadableWithinSession(sessionId, null))
                .isInstanceOf(KnowledgeRetrievalInvalidRequestException.class);
        assertThatThrownBy(() -> service().revalidateReadableWithinSession(sessionId,
                Arrays.asList(hit().getChunkId(), null)))
                .isInstanceOf(KnowledgeRetrievalInvalidRequestException.class);

        verify(authorizedRetrievalRepository, never()).revalidateReadableWithinSession(
                any(), any(), any(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void invalidRequestAndModelFailuresUseExplicitExceptions() {
        assertThatThrownBy(() -> service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request(" ", 3)))
                .isInstanceOf(KnowledgeRetrievalInvalidRequestException.class);

        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenReturn(writeAccess());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("退款状态")).thenThrow(new IllegalStateException("model unavailable"));

        assertThatThrownBy(() -> service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request("退款状态", 3)))
                .isInstanceOf(KnowledgeRetrievalUnavailableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidTopKIsRejectedBeforeAuthorizationOrEmbedding() {
        assertThatThrownBy(() -> service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request("退款状态", 0)))
                .isInstanceOf(KnowledgeRetrievalInvalidRequestException.class);
        assertThatThrownBy(() -> service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request("退款状态", 51)))
                .isInstanceOf(KnowledgeRetrievalInvalidRequestException.class);

        verify(accessPolicy, never()).requireWrite(any());
        verify(embeddingModel, never()).embed(any(String.class));
    }

    @Test
    void acceptsKnownStrategyButRejectsClientSuppliedScopeOrFilterFieldsDuringDeserialization() throws Exception {
        KnowledgeRetrievalTestRequest request = new ObjectMapper().readValue(
                "{\"query\":\"退款状态\",\"topK\":3,\"strategy\":\"FUSION\"}",
                KnowledgeRetrievalTestRequest.class);

        assertThat(request.getStrategy()).isEqualTo(RetrievalStrategyEnum.FUSION);
        assertThatThrownBy(() -> new ObjectMapper().readValue(
                "{\"query\":\"退款状态\",\"topK\":3,\"kbIds\":[\"550e8400-e29b-41d4-a716-446655440001\"]}",
                KnowledgeRetrievalTestRequest.class))
                .hasCauseInstanceOf(KnowledgeRetrievalInvalidRequestException.class);
    }

    private KnowledgeRetrievalService service() {
        return service(new RerankProperties());
    }

    private KnowledgeRetrievalService service(RerankProperties rerankProperties) {
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        return new KnowledgeRetrievalService(embeddingModel, authorizedRetrievalRepository, accessPolicy,
                documentProcessingSnapshotFactory, new ObjectMapper(), retrievalProperties,
                new RetrievalKeywordExtractor(), rerankProperties, rerankModelAdapter);
    }

    private RerankProperties enabledRerank() {
        RerankProperties properties = new RerankProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://workspace.example/compatible-api/v1/reranks");
        return properties;
    }

    private List<AuthorizedRetrievalHit> prepareRerankCandidates(String query) {
        float[] embedding = embedding();
        AuthorizedRetrievalHit first = hit();
        AuthorizedRetrievalHit second = hit();
        second.setChunkId(UUID.fromString("550e8400-e29b-41d4-a716-446655440013"));
        second.setContent("第二条资料");
        AuthorizedRetrievalHit third = hit();
        third.setChunkId(UUID.fromString("550e8400-e29b-41d4-a716-446655440014"));
        third.setContent("第三条资料");
        List<AuthorizedRetrievalHit> candidates = List.of(first, second, third);
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenReturn(writeAccess());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed(query)).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 6,
                "ollama", "bge-m3", 1024, PROCESSING_CONFIG_HASH)).thenReturn(candidates);
        return candidates;
    }

    private KnowledgeRetrievalTestRequest request(String query, int topK) {
        KnowledgeRetrievalTestRequest request = new KnowledgeRetrievalTestRequest();
        request.setQuery(query);
        request.setTopK(topK);
        return request;
    }

    private KnowledgeBaseAccessContext writeAccess() {
        KbMember member = new KbMember();
        member.setKbId(KNOWLEDGE_BASE_ID);
        member.setUserId(USER_ID);
        member.setPermission(KbPermissionEnum.WRITE);
        return new KnowledgeBaseAccessContext(null, member);
    }

    private AuthenticatedUser currentUser() {
        return new AuthenticatedUser(USER_ID, "support", "支持用户", UserRoleEnum.USER);
    }

    private ProcessingConfigurationSnapshot snapshot() {
        return new ProcessingConfigurationSnapshot("bge-m3", 1024, PROCESSING_CONFIG_HASH,
                "{\"embedding\":{\"provider\":\"ollama\",\"model\":\"bge-m3\",\"dimensions\":1024}}");
    }

    private AuthorizedRetrievalHit hit() {
        return new AuthorizedRetrievalHit(KNOWLEDGE_BASE_ID,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440010"), "退款排查手册",
                UUID.fromString("550e8400-e29b-41d4-a716-446655440011"), 2,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440012"), 0, "退款/状态", null, 0, 20,
                "退款状态仍在处理中", 0.92D);
    }

    private AuthorizedRetrievalHit hit(String chunkId, String documentName, String content) {
        AuthorizedRetrievalHit hit = hit();
        hit.setChunkId(UUID.fromString(chunkId));
        hit.setDocumentName(documentName);
        hit.setContent(content);
        return hit;
    }

    private float[] embedding() {
        float[] embedding = new float[ValidatedEmbeddingModel.DIMENSIONS];
        embedding[0] = 0.8F;
        embedding[1] = 0.2F;
        return embedding;
    }
}
