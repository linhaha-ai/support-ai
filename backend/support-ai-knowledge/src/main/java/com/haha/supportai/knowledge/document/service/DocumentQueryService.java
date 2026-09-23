package com.haha.supportai.knowledge.document.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.haha.supportai.data.knowledge.entity.po.DocumentChunkContent;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeDocument;
import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeDocumentRepository;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.entity.vo.DocumentChunkPageResponse;
import com.haha.supportai.knowledge.document.entity.vo.DocumentChunkResponse;
import com.haha.supportai.knowledge.document.entity.vo.DocumentContentResponse;
import com.haha.supportai.knowledge.document.entity.vo.DocumentDetailResponse;
import com.haha.supportai.knowledge.document.entity.vo.DocumentSummaryResponse;
import com.haha.supportai.knowledge.document.entity.vo.DocumentVersionPageResponse;
import com.haha.supportai.knowledge.document.entity.vo.DocumentVersionSummaryResponse;
import com.haha.supportai.knowledge.document.exception.DocumentNotFoundException;
import com.haha.supportai.knowledge.document.exception.DocumentQueryInvalidException;
import com.haha.supportai.knowledge.document.parsing.service.DocumentSourceReader;
import com.haha.supportai.knowledge.entity.dto.KnowledgeBaseAccessContext;
import com.haha.supportai.knowledge.ingestion.entity.vo.IngestionJobStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentQueryService {

    private static final int DEFAULT_VERSION_LIMIT = 20;
    private static final int DEFAULT_CHUNK_LIMIT = 50;
    private static final int MAX_PAGE_LIMIT = 100;
    private static final String VERSION_CURSOR_PREFIX = "version:";
    private static final String CHUNK_CURSOR_PREFIX = "chunk:";

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final IngestionJobRepository ingestionJobRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentSourceReader documentSourceReader;
    private final AccessPolicy accessPolicy;

    @Transactional(readOnly = true)
    public List<DocumentSummaryResponse> list(UUID knowledgeBaseId) {
        KnowledgeBaseAccessContext accessContext = accessPolicy.requireRead(knowledgeBaseId);
        UUID userId = accessContext.getMember().getUserId();
        List<KnowledgeDocument> documents = knowledgeDocumentRepository
                .listAccessibleByKnowledgeBaseId(knowledgeBaseId, userId);
        Map<UUID, DocumentVersion> versions = loadReferencedVersions(documents, userId);
        Map<UUID, IngestionJobStatusResponse> tasks = loadTasks(versions.keySet(), canManage(accessContext));
        return documents.stream()
                .map(document -> toSummary(document, versions, tasks))
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentDetailResponse get(UUID documentId) {
        UUID userId = accessPolicy.currentUser().getId();
        KnowledgeDocument document = requireAccessibleDocument(documentId, userId);
        Map<UUID, DocumentVersion> versions = loadReferencedVersions(List.of(document), userId);
        boolean canManage = canManage(accessPolicy.requireRead(document.getKbId()));
        Map<UUID, IngestionJobStatusResponse> tasks = loadTasks(versions.keySet(), canManage);
        return new DocumentDetailResponse(document.getId(), document.getKbId(), document.getName(),
                toVersionSummary(versions.get(document.getActiveVersionId()), document, tasks),
                toVersionSummary(versions.get(document.getPendingVersionId()), document, tasks),
                document.getRevision(), document.getCreatedAt(), document.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public DocumentVersionPageResponse listVersions(UUID documentId, Integer limit, String cursor) {
        UUID userId = accessPolicy.currentUser().getId();
        KnowledgeDocument document = requireAccessibleDocument(documentId, userId);
        int pageLimit = validateLimit(limit, DEFAULT_VERSION_LIMIT);
        Integer beforeVersionNo = decodeCursor(cursor, VERSION_CURSOR_PREFIX, false);
        List<DocumentVersion> fetched = documentVersionRepository.listAccessibleByDocumentId(
                documentId, userId, beforeVersionNo, pageLimit + 1);
        boolean hasNext = fetched.size() > pageLimit;
        List<DocumentVersion> page = hasNext ? fetched.subList(0, pageLimit) : fetched;
        boolean canManage = canManage(accessPolicy.requireRead(document.getKbId()));
        Map<UUID, IngestionJobStatusResponse> tasks = loadTasks(page.stream()
                .map(DocumentVersion::getId)
                .collect(Collectors.toSet()), canManage);
        List<DocumentVersionSummaryResponse> items = page.stream()
                .map(version -> toVersionSummary(version, document, tasks))
                .toList();
        String nextCursor = hasNext
                ? encodeCursor(VERSION_CURSOR_PREFIX, page.get(page.size() - 1).getVersionNo())
                : null;
        return new DocumentVersionPageResponse(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public DocumentChunkPageResponse listChunks(UUID documentId, UUID versionId, Integer limit, String cursor) {
        UUID userId = accessPolicy.currentUser().getId();
        requireAccessibleVersion(documentId, versionId, userId);
        int pageLimit = validateLimit(limit, DEFAULT_CHUNK_LIMIT);
        Integer afterChunkIndex = decodeCursor(cursor, CHUNK_CURSOR_PREFIX, true);
        List<DocumentChunkContent> fetched = documentChunkRepository.listContentByVersionId(
                versionId, afterChunkIndex, pageLimit + 1);
        boolean hasNext = fetched.size() > pageLimit;
        List<DocumentChunkContent> page = hasNext ? fetched.subList(0, pageLimit) : fetched;
        List<DocumentChunkResponse> items = page.stream()
                .map(chunk -> new DocumentChunkResponse(chunk.getId(), chunk.getChunkIndex(), chunk.getSection(),
                        chunk.getPageNo(), chunk.getStartOffset(), chunk.getEndOffset(), chunk.getCharCount(),
                        chunk.getTokenCount(), chunk.getContent()))
                .toList();
        String nextCursor = hasNext
                ? encodeCursor(CHUNK_CURSOR_PREFIX, page.get(page.size() - 1).getChunkIndex())
                : null;
        return new DocumentChunkPageResponse(items, nextCursor);
    }

    public DocumentContentResponse getContent(UUID documentId, UUID versionId) {
        UUID userId = accessPolicy.currentUser().getId();
        KnowledgeDocument document = requireAccessibleDocument(documentId, userId);
        DocumentVersion version = requireAccessibleVersion(documentId, versionId, userId);
        String content = documentSourceReader.readVerifiedUtf8(version);
        boolean active = Objects.equals(document.getActiveVersionId(), versionId);
        boolean pending = Objects.equals(document.getPendingVersionId(), versionId);
        return new DocumentContentResponse(documentId, versionId, version.getVersionNo(), active, pending,
                version.getOriginalFilename(), version.getMediaType(), content);
    }

    private KnowledgeDocument requireAccessibleDocument(UUID documentId, UUID userId) {
        return knowledgeDocumentRepository.findAccessibleByIdAndUserId(documentId, userId)
                .orElseThrow(DocumentNotFoundException::new);
    }

    private DocumentVersion requireAccessibleVersion(UUID documentId, UUID versionId, UUID userId) {
        return documentVersionRepository.findAccessibleByDocumentIdAndIdAndUserId(
                        documentId, versionId, userId)
                .orElseThrow(DocumentNotFoundException::new);
    }

    private Map<UUID, DocumentVersion> loadReferencedVersions(Collection<KnowledgeDocument> documents, UUID userId) {
        Map<UUID, DocumentVersion> versions = new HashMap<>();
        for (KnowledgeDocument document : documents) {
            loadReferencedVersion(document, document.getActiveVersionId(), userId, versions);
            loadReferencedVersion(document, document.getPendingVersionId(), userId, versions);
        }
        return versions;
    }

    private void loadReferencedVersion(KnowledgeDocument document, UUID versionId, UUID userId,
                                       Map<UUID, DocumentVersion> versions) {
        if (versionId == null || versions.containsKey(versionId)) {
            return;
        }
        documentVersionRepository.findAccessibleByDocumentIdAndIdAndUserId(document.getId(), versionId, userId)
                .ifPresent(version -> versions.put(versionId, version));
    }

    private Map<UUID, IngestionJobStatusResponse> loadTasks(Collection<UUID> versionIds, boolean canManage) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        return ingestionJobRepository.findByVersionIds(versionIds).stream()
                .collect(Collectors.toMap(IngestionJob::getVersionId,
                        job -> IngestionJobStatusResponse.from(job, canManage),
                        (first, ignored) -> first));
    }

    private boolean canManage(KnowledgeBaseAccessContext accessContext) {
        return accessContext.getMember().getPermission() == KbPermissionEnum.WRITE;
    }

    private DocumentSummaryResponse toSummary(KnowledgeDocument document, Map<UUID, DocumentVersion> versions,
                                                Map<UUID, IngestionJobStatusResponse> tasks) {
        return new DocumentSummaryResponse(document.getId(), document.getName(),
                toVersionSummary(versions.get(document.getActiveVersionId()), document, tasks),
                toVersionSummary(versions.get(document.getPendingVersionId()), document, tasks),
                document.getRevision(), document.getCreatedAt(), document.getUpdatedAt());
    }

    private DocumentVersionSummaryResponse toVersionSummary(DocumentVersion version, KnowledgeDocument document,
                                                              Map<UUID, IngestionJobStatusResponse> tasks) {
        if (version == null || !Objects.equals(version.getDocumentId(), document.getId())) {
            return null;
        }
        UUID versionId = version.getId();
        return new DocumentVersionSummaryResponse(versionId, version.getVersionNo(), version.getStatus(),
                Objects.equals(document.getActiveVersionId(), versionId),
                Objects.equals(document.getPendingVersionId(), versionId), version.getOriginalFilename(),
                version.getFileSize(), version.getMediaType(), version.getChunkCount(), tasks.get(versionId),
                version.getCreatedAt(), version.getUpdatedAt());
    }

    private int validateLimit(Integer limit, int defaultLimit) {
        int resolved = limit == null ? defaultLimit : limit;
        if (resolved < 1 || resolved > MAX_PAGE_LIMIT) {
            throw new DocumentQueryInvalidException();
        }
        return resolved;
    }

    private Integer decodeCursor(String cursor, String expectedPrefix, boolean allowZero) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (!value.startsWith(expectedPrefix)) {
                throw new DocumentQueryInvalidException();
            }
            int decoded = Integer.parseInt(value.substring(expectedPrefix.length()));
            if (allowZero ? decoded < 0 : decoded < 1) {
                throw new DocumentQueryInvalidException();
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new DocumentQueryInvalidException();
        }
    }

    private String encodeCursor(String prefix, int value) {
        String cursor = prefix + value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.getBytes(StandardCharsets.US_ASCII));
    }
}
