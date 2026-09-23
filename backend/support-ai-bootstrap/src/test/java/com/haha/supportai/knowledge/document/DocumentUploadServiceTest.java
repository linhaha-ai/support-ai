package com.haha.supportai.knowledge.document;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.config.DocumentUploadProperties;
import com.haha.supportai.knowledge.document.entity.dto.DocumentVersionMatchCommand;
import com.haha.supportai.knowledge.document.entity.dto.DocumentVersionUploadCommand;
import com.haha.supportai.knowledge.document.entity.dto.NewDocumentVersionCommand;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.entity.vo.DocumentUploadReceiptResponse;
import com.haha.supportai.knowledge.document.entity.vo.DocumentVersionUploadResponse;
import com.haha.supportai.knowledge.document.exception.DocumentFileEncodingInvalidException;
import com.haha.supportai.knowledge.document.exception.DocumentFileTooLargeException;
import com.haha.supportai.knowledge.document.exception.DocumentFileTypeUnsupportedException;
import com.haha.supportai.knowledge.document.exception.DocumentUploadFailedException;
import com.haha.supportai.knowledge.document.exception.DocumentVersionConflictException;
import com.haha.supportai.knowledge.document.service.DocumentUploadPersistenceService;
import com.haha.supportai.knowledge.document.service.DocumentUploadService;
import com.haha.supportai.knowledge.document.support.DocumentProcessingSnapshotFactory;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.storage.FileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentUploadServiceTest {

    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID DOCUMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440010");
    private static final String STORAGE_KEY = "2026/09/550e8400-e29b-41d4-a716-446655440001.md";

    @Mock
    AccessPolicy accessPolicy;

    @Mock
    FileStore fileStore;

    @Mock
    DocumentUploadPersistenceService documentUploadPersistenceService;

    @Mock
    DocumentProcessingSnapshotFactory documentProcessingSnapshotFactory;

    DocumentUploadProperties documentUploadProperties;

    DocumentUploadService documentUploadService;

    @BeforeEach
    void setUp() {
        documentUploadProperties = new DocumentUploadProperties();
        documentUploadProperties.setMaxFileSize(DataSize.ofBytes(1024));
        documentUploadProperties.setParserVersion("plain-text-v1");
        documentUploadProperties.setChunkerVersion("token-budget-v1");
        documentUploadProperties.setMaxAttempts(3);
        documentUploadService = new DocumentUploadService(accessPolicy, fileStore, documentUploadPersistenceService,
                documentProcessingSnapshotFactory, documentUploadProperties);
        lenient().when(documentProcessingSnapshotFactory.create()).thenReturn(new ProcessingConfigurationSnapshot("bge-m3",
                1024, "a".repeat(64), "{\"embedding\":{\"model\":\"bge-m3\",\"dimensions\":1024}}"));
    }

    @Test
    void acceptsUtf8MarkdownAndReturnsQueuedReceipt() {
        when(fileStore.store(any(InputStream.class), eq("md"))).thenReturn(STORAGE_KEY);
        ArgumentCaptor<NewDocumentVersionCommand> commandCaptor = ArgumentCaptor.forClass(NewDocumentVersionCommand.class);

        DocumentUploadReceiptResponse receipt = documentUploadService.upload(KNOWLEDGE_BASE_ID,
                file("guide.md", "text/markdown", "# 支付故障\n请检查请求编号"), "支付排障指南");

        assertThat(receipt.getDocumentId()).isNotNull();
        assertThat(receipt.getVersionId()).isNotNull();
        assertThat(receipt.getJobId()).isNotNull();
        assertThat(receipt.getStatus()).isEqualTo(IngestionJobStatusEnum.QUEUED);
        verify(accessPolicy).requireWrite(KNOWLEDGE_BASE_ID);
        verify(documentUploadPersistenceService).createInitialVersion(commandCaptor.capture());
        NewDocumentVersionCommand command = commandCaptor.getValue();
        assertThat(command.getKnowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
        assertThat(command.getStorageKey()).isEqualTo(STORAGE_KEY);
        assertThat(command.getOriginalFilename()).isEqualTo("guide.md");
        assertThat(command.getMediaType()).isEqualTo("text/markdown");
        assertThat(command.getFileSize()).isPositive();
        assertThat(command.getDimensions()).isEqualTo(1024);
        assertThat(command.getConfigSnapshot()).doesNotContain("api-key");
    }

    @Test
    void rejectsReadOnlyMemberBeforeWritingFile() {
        doThrow(new KnowledgeBaseAccessDeniedException()).when(accessPolicy).requireWrite(KNOWLEDGE_BASE_ID);

        assertThatThrownBy(() -> documentUploadService.upload(KNOWLEDGE_BASE_ID,
                file("guide.md", "text/markdown", "# guide"), null))
                .isInstanceOf(KnowledgeBaseAccessDeniedException.class);

        verify(fileStore, never()).store(any(InputStream.class), any(String.class));
        verify(documentUploadPersistenceService, never()).createInitialVersion(any(NewDocumentVersionCommand.class));
    }

    @Test
    void rejectsFileLargerThanConfiguredLimit() {
        documentUploadProperties.setMaxFileSize(DataSize.ofBytes(3));

        assertThatThrownBy(() -> documentUploadService.upload(KNOWLEDGE_BASE_ID,
                file("guide.txt", "text/plain", "1234"), null))
                .isInstanceOf(DocumentFileTooLargeException.class);

        verify(fileStore, never()).store(any(InputStream.class), any(String.class));
        verify(documentUploadPersistenceService, never()).createInitialVersion(any(NewDocumentVersionCommand.class));
    }

    @Test
    void rejectsUnsupportedMediaType() {
        assertThatThrownBy(() -> documentUploadService.upload(KNOWLEDGE_BASE_ID,
                file("guide.txt", "application/pdf", "not a pdf"), null))
                .isInstanceOf(DocumentFileTypeUnsupportedException.class);

        verify(fileStore, never()).store(any(InputStream.class), any(String.class));
    }

    @Test
    void rejectsInvalidUtf8Content() {
        MockMultipartFile invalidUtf8File = new MockMultipartFile("file", "guide.txt", "text/plain",
                new byte[] { (byte) 0xc3, (byte) 0x28 });

        assertThatThrownBy(() -> documentUploadService.upload(KNOWLEDGE_BASE_ID, invalidUtf8File, null))
                .isInstanceOf(DocumentFileEncodingInvalidException.class);

        verify(fileStore, never()).store(any(InputStream.class), any(String.class));
    }

    @Test
    void keepsOnlyBasenameWhenOriginalFilenameContainsPathTraversal() {
        when(fileStore.store(any(InputStream.class), eq("md"))).thenReturn(STORAGE_KEY);
        ArgumentCaptor<NewDocumentVersionCommand> commandCaptor = ArgumentCaptor.forClass(NewDocumentVersionCommand.class);

        documentUploadService.upload(KNOWLEDGE_BASE_ID,
                file("C:\\upload\\..\\external\\guide.md", "text/markdown", "# guide"), null);

        verify(documentUploadPersistenceService).createInitialVersion(commandCaptor.capture());
        assertThat(commandCaptor.getValue().getOriginalFilename()).isEqualTo("guide.md");
        assertThat(commandCaptor.getValue().getDisplayName()).isEqualTo("guide.md");
        assertThat(commandCaptor.getValue().getStorageKey()).isEqualTo(STORAGE_KEY);
    }

    @Test
    void removesStoredFileWhenPersistenceFails() {
        when(fileStore.store(any(InputStream.class), eq("txt"))).thenReturn(STORAGE_KEY);
        doThrow(new IllegalStateException("模拟数据库提交失败"))
                .when(documentUploadPersistenceService).createInitialVersion(any(NewDocumentVersionCommand.class));

        assertThatThrownBy(() -> documentUploadService.upload(KNOWLEDGE_BASE_ID,
                file("guide.txt", "text/plain", "database failure compensation"), null))
                .isInstanceOf(DocumentUploadFailedException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        verify(fileStore).delete(STORAGE_KEY);
    }

    @Test
    void reusesCurrentVersionWithoutWritingFileOrCreatingJob() {
        UUID versionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
        UUID jobId = UUID.fromString("550e8400-e29b-41d4-a716-446655440012");
        DocumentVersionUploadResponse expected = new DocumentVersionUploadResponse(DOCUMENT_ID, versionId, jobId,
                IngestionJobStatusEnum.SUCCEEDED, 7, true);
        when(documentUploadPersistenceService.findReusableCurrentVersion(any(DocumentVersionMatchCommand.class)))
                .thenReturn(Optional.of(expected));
        ArgumentCaptor<DocumentVersionMatchCommand> commandCaptor = ArgumentCaptor.forClass(
                DocumentVersionMatchCommand.class);

        DocumentVersionUploadResponse actual = documentUploadService.uploadVersion(DOCUMENT_ID,
                file("guide.md", "text/markdown", "# 相同内容"), 7);

        assertThat(actual).isSameAs(expected);
        verify(documentUploadPersistenceService).requireVersionUploadAccess(DOCUMENT_ID);
        verify(documentUploadPersistenceService).findReusableCurrentVersion(commandCaptor.capture());
        verify(fileStore, never()).store(any(InputStream.class), any(String.class));
        verify(documentUploadPersistenceService, never()).createNewVersion(any(DocumentVersionUploadCommand.class));
        DocumentVersionMatchCommand command = commandCaptor.getValue();
        assertThat(command.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(command.getExpectedRevision()).isEqualTo(7);
        assertThat(command.getFileHash()).hasSize(64);
        assertThat(command.getEmbeddingModel()).isEqualTo("bge-m3");
        assertThat(command.getDimensions()).isEqualTo(1024);
    }

    @Test
    void createsPendingVersionWhenCurrentVersionCannotBeReused() {
        UUID versionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440013");
        UUID jobId = UUID.fromString("550e8400-e29b-41d4-a716-446655440014");
        DocumentVersionUploadResponse expected = new DocumentVersionUploadResponse(DOCUMENT_ID, versionId, jobId,
                IngestionJobStatusEnum.QUEUED, 8, false);
        when(documentUploadPersistenceService.findReusableCurrentVersion(any(DocumentVersionMatchCommand.class)))
                .thenReturn(Optional.empty());
        when(fileStore.store(any(InputStream.class), eq("md"))).thenReturn(STORAGE_KEY);
        when(documentUploadPersistenceService.createNewVersion(any(DocumentVersionUploadCommand.class)))
                .thenReturn(expected);
        ArgumentCaptor<DocumentVersionUploadCommand> commandCaptor = ArgumentCaptor.forClass(
                DocumentVersionUploadCommand.class);

        DocumentVersionUploadResponse actual = documentUploadService.uploadVersion(DOCUMENT_ID,
                file("guide.md", "text/markdown", "# 新版本内容"), 7);

        assertThat(actual).isSameAs(expected);
        verify(documentUploadPersistenceService).requireVersionUploadAccess(DOCUMENT_ID);
        verify(fileStore).store(any(InputStream.class), eq("md"));
        verify(documentUploadPersistenceService).createNewVersion(commandCaptor.capture());
        DocumentVersionUploadCommand command = commandCaptor.getValue();
        assertThat(command.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(command.getExpectedRevision()).isEqualTo(7);
        assertThat(command.getStorageKey()).isEqualTo(STORAGE_KEY);
        assertThat(command.getOriginalFilename()).isEqualTo("guide.md");
        assertThat(command.getDimensions()).isEqualTo(1024);
        assertThat(command.getConfigSnapshot()).doesNotContain("api-key");
    }

    @Test
    void rejectsVersionConflictBeforeWritingFile() {
        when(documentUploadPersistenceService.findReusableCurrentVersion(any(DocumentVersionMatchCommand.class)))
                .thenThrow(new DocumentVersionConflictException());

        assertThatThrownBy(() -> documentUploadService.uploadVersion(DOCUMENT_ID,
                file("guide.md", "text/markdown", "# 过期修订"), 7))
                .isInstanceOf(DocumentVersionConflictException.class);

        verify(documentUploadPersistenceService).requireVersionUploadAccess(DOCUMENT_ID);
        verify(fileStore, never()).store(any(InputStream.class), any(String.class));
        verify(documentUploadPersistenceService, never()).createNewVersion(any(DocumentVersionUploadCommand.class));
    }

    @Test
    void removesNewVersionFileWhenRegistrationFails() {
        when(documentUploadPersistenceService.findReusableCurrentVersion(any(DocumentVersionMatchCommand.class)))
                .thenReturn(Optional.empty());
        when(fileStore.store(any(InputStream.class), eq("txt"))).thenReturn(STORAGE_KEY);
        doThrow(new IllegalStateException("模拟版本登记失败"))
                .when(documentUploadPersistenceService).createNewVersion(any(DocumentVersionUploadCommand.class));

        assertThatThrownBy(() -> documentUploadService.uploadVersion(DOCUMENT_ID,
                file("guide.txt", "text/plain", "版本登记失败补偿"), 7))
                .isInstanceOf(DocumentUploadFailedException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        verify(fileStore).delete(STORAGE_KEY);
    }

    private MockMultipartFile file(String filename, String mediaType, String content) {
        return new MockMultipartFile("file", filename, mediaType, content.getBytes(StandardCharsets.UTF_8));
    }
}
