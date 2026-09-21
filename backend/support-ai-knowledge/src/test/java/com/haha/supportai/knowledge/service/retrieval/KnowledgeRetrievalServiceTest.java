package com.haha.supportai.knowledge.service.retrieval;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.ai.model.ValidatedEmbeddingModel;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.knowledge.entity.po.AuthorizedRetrievalHit;
import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import com.haha.supportai.data.knowledge.repository.AuthorizedRetrievalRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.support.DocumentProcessingSnapshotFactory;
import com.haha.supportai.knowledge.entity.dto.KnowledgeBaseAccessContext;
import com.haha.supportai.knowledge.entity.dto.KnowledgeRetrievalTestRequest;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.exception.KnowledgeRetrievalInvalidRequestException;
import com.haha.supportai.knowledge.exception.KnowledgeRetrievalUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Mock
    EmbeddingModel embeddingModel;

    @Mock
    AuthorizedRetrievalRepository authorizedRetrievalRepository;

    @Mock
    AccessPolicy accessPolicy;

    @Mock
    DocumentProcessingSnapshotFactory documentProcessingSnapshotFactory;

    @Test
    void writableTestChecksWritePermissionBeforeCreatingOneQueryEmbedding() {
        float[] embedding = embedding();
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenReturn(writeAccess());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("退款状态")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 3,
                "ollama", "bge-m3", 1024)).thenReturn(List.of(hit()));

        var response = service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request("  退款状态  ", 3));

        assertThat(response.getQuery()).isEqualTo("退款状态");
        assertThat(response.getMatches()).hasSize(1);
        assertThat(response.getMatches().get(0).getDocumentName()).isEqualTo("退款排查手册");
        assertThat(response.getDurationMs()).isGreaterThanOrEqualTo(0);
        verify(accessPolicy).requireWrite(KNOWLEDGE_BASE_ID);
        verify(embeddingModel).embed("退款状态");
        verify(authorizedRetrievalRepository).searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 3,
                "ollama", "bge-m3", 1024);
    }

    @Test
    void rejectedWritePermissionDoesNotCallEmbeddingModelOrRepository() {
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenThrow(new KnowledgeBaseAccessDeniedException());

        assertThatThrownBy(() -> service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request("退款状态", 3)))
                .isInstanceOf(KnowledgeBaseAccessDeniedException.class);

        verify(embeddingModel, never()).embed(any(String.class));
        verify(authorizedRetrievalRepository, never()).searchWritable(any(), any(), any(float[].class), anyInt(),
                anyString(), anyString(), anyInt());
    }

    @Test
    void emptyAuthorizedResultReturnsSuccessfulEmptyMatches() {
        float[] embedding = embedding();
        when(accessPolicy.requireWrite(KNOWLEDGE_BASE_ID)).thenReturn(writeAccess());
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("退款状态")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchWritable(USER_ID, KNOWLEDGE_BASE_ID, embedding, 3,
                "ollama", "bge-m3", 1024)).thenReturn(List.of());

        var response = service().testWritableKnowledgeBase(KNOWLEDGE_BASE_ID, request("退款状态", 3));

        assertThat(response.getMatches()).isEmpty();
    }

    @Test
    void readableSessionRetrievalUsesOnlyServerSideSessionIdentifierAndCurrentUser() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        float[] embedding = embedding();
        when(accessPolicy.currentUser()).thenReturn(new AuthenticatedUser(USER_ID, "support", "支持用户", UserRoleEnum.USER));
        when(documentProcessingSnapshotFactory.create()).thenReturn(snapshot());
        when(embeddingModel.embed("退款状态")).thenReturn(embedding);
        when(authorizedRetrievalRepository.searchReadableWithinSession(USER_ID, sessionId,
                embedding, 2, "ollama", "bge-m3", 1024)).thenReturn(List.of(hit()));

        var matches = service().retrieveReadableWithinSession(sessionId, "退款状态", 2);

        assertThat(matches).hasSize(1);
        verify(authorizedRetrievalRepository).searchReadableWithinSession(USER_ID, sessionId,
                embedding, 2, "ollama", "bge-m3", 1024);
    }

    @Test
    void nullSessionIsRejectedBeforeEmbeddingModel() {
        assertThatThrownBy(() -> service().retrieveReadableWithinSession(null, "退款状态", 2))
                .isInstanceOf(KnowledgeRetrievalInvalidRequestException.class);

        verify(embeddingModel, never()).embed(any(String.class));
        verify(authorizedRetrievalRepository, never()).searchReadableWithinSession(any(), any(), any(float[].class), anyInt(),
                anyString(), anyString(), anyInt());
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
    void rejectsClientSuppliedScopeOrFilterFieldsDuringRequestDeserialization() {
        assertThatThrownBy(() -> new ObjectMapper().readValue(
                "{\"query\":\"退款状态\",\"topK\":3,\"kbIds\":[\"550e8400-e29b-41d4-a716-446655440001\"]}",
                KnowledgeRetrievalTestRequest.class))
                .hasCauseInstanceOf(KnowledgeRetrievalInvalidRequestException.class);
    }

    private KnowledgeRetrievalService service() {
        return new KnowledgeRetrievalService(embeddingModel, authorizedRetrievalRepository, accessPolicy,
                documentProcessingSnapshotFactory, new ObjectMapper());
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

    private ProcessingConfigurationSnapshot snapshot() {
        return new ProcessingConfigurationSnapshot("bge-m3", 1024, "config-hash",
                "{\"embedding\":{\"provider\":\"ollama\",\"model\":\"bge-m3\",\"dimensions\":1024}}");
    }

    private AuthorizedRetrievalHit hit() {
        return new AuthorizedRetrievalHit(KNOWLEDGE_BASE_ID,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440010"), "退款排查手册",
                UUID.fromString("550e8400-e29b-41d4-a716-446655440011"), 2,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440012"), 0, "退款/状态", null, 0, 20,
                "退款状态仍在处理中", 0.92D);
    }

    private float[] embedding() {
        float[] embedding = new float[ValidatedEmbeddingModel.DIMENSIONS];
        embedding[0] = 0.8F;
        embedding[1] = 0.2F;
        return embedding;
    }
}
