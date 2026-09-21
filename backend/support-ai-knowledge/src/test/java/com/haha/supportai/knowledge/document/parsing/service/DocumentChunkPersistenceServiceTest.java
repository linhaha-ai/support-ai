package com.haha.supportai.knowledge.document.parsing.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentChunk;
import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentChunkDraft;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionLeaseLostException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionStateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentChunkPersistenceServiceTest {

    private static final UUID JOB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440201");
    private static final UUID VERSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440202");
    private static final UUID LEASE_TOKEN = UUID.fromString("550e8400-e29b-41d4-a716-446655440203");

    @Mock
    IngestionJobRepository ingestionJobRepository;

    @Mock
    DocumentVersionRepository documentVersionRepository;

    @Mock
    DocumentChunkRepository documentChunkRepository;

    private DocumentChunkPersistenceService documentChunkPersistenceService;

    @BeforeEach
    void setUp() {
        documentChunkPersistenceService = new DocumentChunkPersistenceService(ingestionJobRepository,
                documentVersionRepository, documentChunkRepository);
    }

    @Test
    void activeChunkLeaseUpsertsStableChunksAndMarksVersionBuilding() {
        IngestionTaskLease lease = activeLease();
        when(ingestionJobRepository.findActiveLeaseForUpdate(JOB_ID, VERSION_ID, IngestionStageEnum.CHUNK,
                "worker-test", LEASE_TOKEN)).thenReturn(Optional.of(new IngestionJob()));
        when(documentVersionRepository.updateBuildingChunkCount(VERSION_ID, 2)).thenReturn(true);

        documentChunkPersistenceService.replaceChunks(lease, drafts());
        documentChunkPersistenceService.replaceChunks(lease, drafts());

        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkRepository, times(2)).upsertBatch(chunksCaptor.capture());
        List<List<DocumentChunk>> batches = chunksCaptor.getAllValues();
        assertThat(batches.getFirst()).extracting(DocumentChunk::getChunkIndex).containsExactly(0, 1);
        assertThat(batches.getFirst()).extracting(DocumentChunk::getId)
                .containsExactlyElementsOf(batches.get(1).stream().map(DocumentChunk::getId).toList());
        assertThat(batches.getFirst()).extracting(DocumentChunk::getSection).containsExactly("支付", null);
        verify(documentChunkRepository, times(2))
                .deleteByVersionIdAndChunkIndexGreaterThanOrEqual(VERSION_ID, 2);
        verify(documentVersionRepository, times(2)).updateBuildingChunkCount(VERSION_ID, 2);
    }

    @Test
    void activeParseLeaseMarksVersionBuildingWithoutWritingChunks() {
        IngestionTaskLease lease = lease(IngestionStageEnum.PARSE);
        when(ingestionJobRepository.findActiveLeaseForUpdate(JOB_ID, VERSION_ID, IngestionStageEnum.PARSE,
                "worker-test", LEASE_TOKEN)).thenReturn(Optional.of(new IngestionJob()));
        when(documentVersionRepository.updateBuildingChunkCount(VERSION_ID, 0)).thenReturn(true);

        documentChunkPersistenceService.markParsed(lease);

        verify(documentVersionRepository).updateBuildingChunkCount(VERSION_ID, 0);
        verify(documentChunkRepository, never()).upsertBatch(any());
    }

    @Test
    void missingOrExpiredLeaseDoesNotWriteChunks() {
        IngestionTaskLease lease = activeLease();
        when(ingestionJobRepository.findActiveLeaseForUpdate(eq(JOB_ID), eq(VERSION_ID),
                eq(IngestionStageEnum.CHUNK), eq("worker-test"), eq(LEASE_TOKEN))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentChunkPersistenceService.replaceChunks(lease, drafts()))
                .isInstanceOf(IngestionLeaseLostException.class);

        verify(documentChunkRepository, never()).upsertBatch(any());
        verify(documentChunkRepository, never()).deleteByVersionIdAndChunkIndexGreaterThanOrEqual(any(), anyInt());
        verify(documentVersionRepository, never()).updateBuildingChunkCount(any(), anyInt());
    }

    @Test
    void mismatchedContentHashDoesNotWriteChunks() {
        IngestionTaskLease lease = activeLease();
        when(ingestionJobRepository.findActiveLeaseForUpdate(JOB_ID, VERSION_ID, IngestionStageEnum.CHUNK,
                "worker-test", LEASE_TOKEN)).thenReturn(Optional.of(new IngestionJob()));
        List<DocumentChunkDraft> invalidDrafts = List.of(
                new DocumentChunkDraft(0, "支付", 0, 4, "a".repeat(64), 4, 2, "支付流程"));

        assertThatThrownBy(() -> documentChunkPersistenceService.replaceChunks(lease, invalidDrafts))
                .isInstanceOf(IngestionStateConflictException.class);

        verify(documentChunkRepository, never()).upsertBatch(any());
        verify(documentVersionRepository, never()).updateBuildingChunkCount(any(), anyInt());
    }

    private IngestionTaskLease activeLease() {
        return lease(IngestionStageEnum.CHUNK);
    }

    private IngestionTaskLease lease(IngestionStageEnum stage) {
        return new IngestionTaskLease(JOB_ID, VERSION_ID, stage, 1, 3, "worker-test",
                LEASE_TOKEN, OffsetDateTime.parse("2026-09-23T00:00:00Z"));
    }

    private List<DocumentChunkDraft> drafts() {
        return List.of(
                draft(0, "支付", 0, "支付流程"),
                draft(1, "", 4, "处理说明"));
    }

    private DocumentChunkDraft draft(int index, String section, int startOffset, String content) {
        return new DocumentChunkDraft(index, section, startOffset, startOffset + content.length(), sha256(content),
                content.length(), 2, content);
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError("测试环境不支持 SHA-256", exception);
        }
    }
}
