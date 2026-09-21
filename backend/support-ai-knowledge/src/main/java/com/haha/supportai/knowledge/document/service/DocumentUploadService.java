package com.haha.supportai.knowledge.document.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.config.DocumentUploadProperties;
import com.haha.supportai.knowledge.document.entity.dto.NewDocumentVersionCommand;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.entity.vo.DocumentUploadReceiptResponse;
import com.haha.supportai.knowledge.document.exception.DocumentFileEmptyException;
import com.haha.supportai.knowledge.document.exception.DocumentFileEncodingInvalidException;
import com.haha.supportai.knowledge.document.exception.DocumentFileRequiredException;
import com.haha.supportai.knowledge.document.exception.DocumentFileTooLargeException;
import com.haha.supportai.knowledge.document.exception.DocumentFileTypeUnsupportedException;
import com.haha.supportai.knowledge.document.exception.DocumentNameInvalidException;
import com.haha.supportai.knowledge.document.exception.DocumentStorageUnavailableException;
import com.haha.supportai.knowledge.document.exception.DocumentUploadFailedException;
import com.haha.supportai.knowledge.document.support.DocumentProcessingSnapshotFactory;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseNotFoundException;
import com.haha.supportai.storage.FileStore;
import com.haha.supportai.storage.exception.StorageWriteException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentUploadService.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "markdown", "txt");
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "text/plain", "text/markdown", "text/x-markdown", "application/octet-stream");
    private static final int MAX_NAME_LENGTH = 255;

    private final AccessPolicy accessPolicy;
    private final FileStore fileStore;
    private final DocumentUploadPersistenceService documentUploadPersistenceService;
    private final DocumentProcessingSnapshotFactory documentProcessingSnapshotFactory;
    private final DocumentUploadProperties documentUploadProperties;

    public DocumentUploadReceiptResponse upload(UUID knowledgeBaseId, MultipartFile file, String displayName) {
        accessPolicy.requireWrite(knowledgeBaseId);
        ValidatedUpload validatedUpload = validate(file, displayName);
        ProcessingConfigurationSnapshot processing = documentProcessingSnapshotFactory.create();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String storageKey = store(validatedUpload);
        OffsetDateTime createdAt = OffsetDateTime.now();
        NewDocumentVersionCommand command = new NewDocumentVersionCommand(knowledgeBaseId, documentId, versionId,
                jobId, validatedUpload.displayName(), storageKey, sha256(validatedUpload.content()),
                validatedUpload.content().length, validatedUpload.mediaType(), validatedUpload.originalFilename(),
                documentUploadProperties.getParserVersion(), documentUploadProperties.getChunkerVersion(),
                processing.getEmbeddingModel(), processing.getDimensions(), processing.getConfigHash(),
                processing.getConfigSnapshot(), documentUploadProperties.getMaxAttempts(), createdAt);
        try {
            documentUploadPersistenceService.createInitialVersion(command);
        } catch (KnowledgeBaseAccessDeniedException | KnowledgeBaseNotFoundException exception) {
            compensateStorage(storageKey, exception);
            throw exception;
        } catch (RuntimeException exception) {
            compensateStorage(storageKey, exception);
            throw new DocumentUploadFailedException(exception);
        }
        return new DocumentUploadReceiptResponse(documentId, versionId, jobId, IngestionJobStatusEnum.QUEUED);
    }

    private ValidatedUpload validate(MultipartFile file, String displayName) {
        if (file == null) {
            throw new DocumentFileRequiredException();
        }
        if (file.isEmpty()) {
            throw new DocumentFileEmptyException();
        }
        long maxFileSize = documentUploadProperties.getMaxFileSize().toBytes();
        if (maxFileSize <= 0 || file.getSize() > maxFileSize) {
            throw new DocumentFileTooLargeException();
        }

        String originalFilename = safeOriginalFilename(file.getOriginalFilename());
        String extension = extensionOf(originalFilename);
        validateMediaType(file.getContentType());
        byte[] content = readContent(file);
        if (content.length == 0) {
            throw new DocumentFileEmptyException();
        }
        if (content.length > maxFileSize) {
            throw new DocumentFileTooLargeException();
        }
        validateUtf8(content);
        String normalizedDisplayName = normalizeDisplayName(displayName, originalFilename);
        String storageExtension = "txt".equals(extension) ? "txt" : "md";
        String mediaType = "txt".equals(extension) ? "text/plain" : "text/markdown";
        return new ValidatedUpload(content, originalFilename, normalizedDisplayName, storageExtension, mediaType);
    }

    private byte[] readContent(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new DocumentStorageUnavailableException(exception);
        }
    }

    private String safeOriginalFilename(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new DocumentFileRequiredException();
        }
        String normalizedPath = originalFilename.trim().replace('\\', '/');
        String filename = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1).trim();
        if (!StringUtils.hasText(filename) || filename.length() > MAX_NAME_LENGTH || containsControlCharacter(filename)) {
            throw new DocumentNameInvalidException();
        }
        return filename;
    }

    private String extensionOf(String filename) {
        int separator = filename.lastIndexOf('.');
        if (separator <= 0 || separator == filename.length() - 1) {
            throw new DocumentFileTypeUnsupportedException();
        }
        String extension = filename.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new DocumentFileTypeUnsupportedException();
        }
        return extension;
    }

    private void validateMediaType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            throw new DocumentFileTypeUnsupportedException();
        }
    }

    private void validateUtf8(byte[] content) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException exception) {
            throw new DocumentFileEncodingInvalidException();
        }
    }

    private String normalizeDisplayName(String displayName, String originalFilename) {
        String normalizedName = StringUtils.hasText(displayName) ? displayName.trim() : originalFilename;
        if (normalizedName.length() > MAX_NAME_LENGTH || containsControlCharacter(normalizedName)) {
            throw new DocumentNameInvalidException();
        }
        return normalizedName;
    }

    private boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private String store(ValidatedUpload upload) {
        try {
            return fileStore.store(new ByteArrayInputStream(upload.content()), upload.storageExtension());
        } catch (StorageWriteException exception) {
            throw new DocumentStorageUnavailableException(exception);
        }
    }

    private void compensateStorage(String storageKey, RuntimeException originalException) {
        try {
            fileStore.delete(storageKey);
        } catch (RuntimeException deleteException) {
            originalException.addSuppressed(deleteException);
            // 仅记录内部键与异常类型，避免文件内容、用户名或数据库详情进入日志。
            LOGGER.error("文档登记失败后的文件补偿未完成: storageKey={}, type={}", storageKey,
                    deleteException.getClass().getSimpleName());
        }
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    private record ValidatedUpload(byte[] content, String originalFilename, String displayName,
                                   String storageExtension, String mediaType) {
    }
}
