package com.haha.supportai.knowledge.document.embedding.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVectorRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeDocumentRepository;
import com.haha.supportai.knowledge.document.embedding.exception.DocumentIndexIntegrityException;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIndexPublicationServiceTest {

    private static final UUID JOB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440831");
    private static final UUID VERSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440832");
    private static final UUID DOCUMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440833");
    private static final UUID LEASE_TOKEN = UUID.fromString("550e8400-e29b-41d4-a716-446655440834");

    @Mock
    IngestionJobRepository ingestionJobRepository;

    @Mock
    DocumentVersionRepository documentVersionRepository;

    @Mock
    KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Mock
    DocumentChunkRepository documentChunkRepository;

    @Mock
    DocumentVectorRepository documentVectorRepository;

    @Mock
    DocumentEmbeddingConfigurationValidator configurationValidator;

    private DocumentIndexPublicationService publicationService;

    @BeforeEach
    void setUp() {
        publicationService = new DocumentIndexPublicationService(ingestionJobRepository, documentVersionRepository,
                knowledgeDocumentRepository, documentChunkRepository, documentVectorRepository,
                configurationValidator);
    }

    @Test
    void completeIndexPublishesReadyVersionAndSucceedsJobInOrder() {
        IngestionTaskLease lease = lease();
        DocumentVersion version = version();
        when(ingestionJobRepository.findActiveLeaseForUpdate(JOB_ID, VERSION_ID, IngestionStageEnum.PUBLISH,
                "f08-test-worker", LEASE_TOKEN)).thenReturn(Optional.of(new IngestionJob()));
        when(documentVersionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
        when(documentChunkRepository.countByVersionId(VERSION_ID)).thenReturn(2L);
        when(documentVectorRepository.countByVersionId(VERSION_ID)).thenReturn(2L);
        when(documentVectorRepository.countByVersionIdAndDimensions(VERSION_ID, 1024)).thenReturn(2L);
        when(documentVersionRepository.markReady(VERSION_ID, 2)).thenReturn(true);
        when(knowledgeDocumentRepository.publishPendingVersion(DOCUMENT_ID, VERSION_ID)).thenReturn(true);
        when(ingestionJobRepository.markSucceeded(JOB_ID, "f08-test-worker", LEASE_TOKEN, 2)).thenReturn(true);

        assertThat(publicationService.publish(lease)).isEqualTo(2);

        verify(configurationValidator).requireCurrent(version);
        InOrder calls = inOrder(documentVersionRepository, knowledgeDocumentRepository, ingestionJobRepository);
        calls.verify(documentVersionRepository).markReady(VERSION_ID, 2);
        calls.verify(knowledgeDocumentRepository).publishPendingVersion(DOCUMENT_ID, VERSION_ID);
        calls.verify(ingestionJobRepository).markSucceeded(JOB_ID, "f08-test-worker", LEASE_TOKEN, 2);
    }

    @Test
    void incompleteIndexDoesNotActivatePendingVersion() {
        IngestionTaskLease lease = lease();
        when(ingestionJobRepository.findActiveLeaseForUpdate(JOB_ID, VERSION_ID, IngestionStageEnum.PUBLISH,
                "f08-test-worker", LEASE_TOKEN)).thenReturn(Optional.of(new IngestionJob()));
        when(documentVersionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version()));
        when(documentChunkRepository.countByVersionId(VERSION_ID)).thenReturn(2L);
        when(documentVectorRepository.countByVersionId(VERSION_ID)).thenReturn(1L);
        when(documentVectorRepository.countByVersionIdAndDimensions(VERSION_ID, 1024)).thenReturn(1L);

        assertThatThrownBy(() -> publicationService.publish(lease)).isInstanceOf(DocumentIndexIntegrityException.class);

        verify(documentVersionRepository, never()).markReady(VERSION_ID, 2);
        verify(knowledgeDocumentRepository, never()).publishPendingVersion(DOCUMENT_ID, VERSION_ID);
        verify(ingestionJobRepository, never()).markSucceeded(JOB_ID, "f08-test-worker", LEASE_TOKEN, 2);
    }

    private IngestionTaskLease lease() {
        return new IngestionTaskLease(JOB_ID, VERSION_ID, IngestionStageEnum.PUBLISH, 1, 3, "f08-test-worker",
                LEASE_TOKEN, OffsetDateTime.parse("2026-09-23T00:00:00Z"));
    }

    private DocumentVersion version() {
        DocumentVersion version = new DocumentVersion();
        version.setId(VERSION_ID);
        version.setDocumentId(DOCUMENT_ID);
        version.setChunkCount(2);
        return version;
    }
}
