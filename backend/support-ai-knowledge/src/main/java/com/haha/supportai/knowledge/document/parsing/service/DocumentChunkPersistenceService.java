package com.haha.supportai.knowledge.document.parsing.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentChunk;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentChunkDraft;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionLeaseLostException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionStateConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DocumentChunkPersistenceService {

    private static final int BATCH_SIZE = 200;

    private final IngestionJobRepository ingestionJobRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentChunkRepository documentChunkRepository;

    @Transactional
    public void markParsed(IngestionTaskLease lease) {
        if (lease.getStage() != IngestionStageEnum.PARSE) {
            throw new IngestionStateConflictException("解析阶段结果无效");
        }
        requireActiveLease(lease);
        if (!documentVersionRepository.updateBuildingChunkCount(lease.getVersionId(), 0)) {
            throw new IngestionStateConflictException("文档版本不允许进入构建状态");
        }
    }

    @Transactional
    public void replaceChunks(IngestionTaskLease lease, List<DocumentChunkDraft> drafts) {
        if (lease.getStage() != IngestionStageEnum.CHUNK || drafts == null || drafts.isEmpty()) {
            throw new IngestionStateConflictException("分块阶段结果无效");
        }
        requireActiveLease(lease);

        List<DocumentChunk> chunks = toChunks(lease.getVersionId(), drafts);
        for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, chunks.size());
            documentChunkRepository.upsertBatch(chunks.subList(start, end));
        }
        documentChunkRepository.deleteByVersionIdAndChunkIndexGreaterThanOrEqual(lease.getVersionId(), chunks.size());
        if (!documentVersionRepository.updateBuildingChunkCount(lease.getVersionId(), chunks.size())) {
            throw new IngestionStateConflictException("文档版本不允许写入分块");
        }
    }

    private void requireActiveLease(IngestionTaskLease lease) {
        ingestionJobRepository.findActiveLeaseForUpdate(lease.getJobId(), lease.getVersionId(), lease.getStage(),
                        lease.getLeaseOwner(), lease.getLeaseToken())
                .orElseThrow(IngestionLeaseLostException::new);
    }

    private List<DocumentChunk> toChunks(UUID versionId, List<DocumentChunkDraft> drafts) {
        List<DocumentChunk> chunks = new ArrayList<>(drafts.size());
        int previousEndOffset = 0;
        for (int index = 0; index < drafts.size(); index++) {
            DocumentChunkDraft draft = drafts.get(index);
            validateDraft(draft, index, previousEndOffset);
            String section = StringUtils.hasText(draft.getSection()) ? draft.getSection() : null;
            chunks.add(new DocumentChunk(stableChunkId(versionId, index), versionId, index, section, null,
                    draft.getStartOffset(), draft.getEndOffset(), draft.getContentHash(), draft.getCharCount(),
                    draft.getTokenCount()));
            previousEndOffset = draft.getEndOffset();
        }
        return chunks;
    }

    private void validateDraft(DocumentChunkDraft draft, int expectedIndex, int previousEndOffset) {
        if (draft == null || draft.getChunkIndex() != expectedIndex || draft.getStartOffset() < previousEndOffset
                || draft.getEndOffset() <= draft.getStartOffset()
                || draft.getCharCount() != draft.getEndOffset() - draft.getStartOffset()
                || draft.getContent() == null || draft.getContent().length() != draft.getCharCount()
                || draft.getTokenCount() <= 0 || draft.getContentHash() == null
                || !draft.getContentHash().matches("[0-9a-f]{64}")
                || !draft.getContentHash().equals(sha256(draft.getContent()))) {
            throw new IngestionStateConflictException("分块阶段结果无效");
        }
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    private UUID stableChunkId(UUID versionId, int chunkIndex) {
        String source = versionId + ":" + chunkIndex;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }
}
