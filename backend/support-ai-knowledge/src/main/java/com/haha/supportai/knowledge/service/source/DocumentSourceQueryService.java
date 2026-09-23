package com.haha.supportai.knowledge.service.source;

import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.AuthorizedDocumentSource;
import com.haha.supportai.data.knowledge.repository.AuthorizedDocumentSourceRepository;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.exception.DocumentNotFoundException;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceAvailabilityResponse;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentSourceQueryService {

    private final AuthorizedDocumentSourceRepository authorizedDocumentSourceRepository;
    private final AccessPolicy accessPolicy;

    @Transactional(readOnly = true)
    public DocumentSourceResponse getReadableSource(UUID chunkId) {
        if (chunkId == null) {
            throw new DocumentNotFoundException();
        }
        UUID userId = accessPolicy.currentUser().getId();
        return findReadableSource(userId, chunkId).orElseThrow(DocumentNotFoundException::new);
    }

    /**
     * 后台任务与历史上下文只可使用当前仍可读取的来源正文。
     */
    @Transactional(readOnly = true)
    public Optional<DocumentSourceResponse> findReadableSource(UUID userId, UUID chunkId) {
        return loadAuthorizedSource(userId, chunkId)
                .filter(AuthorizedDocumentSource::isAvailable)
                .map(this::toResponse);
    }

    /**
     * 历史回答展示使用当前用户授权重新读取来源状态；已删除文档只返回元数据和不可用标记。
     */
    @Transactional(readOnly = true)
    public Optional<DocumentSourceAvailabilityResponse> findAuthorizedSource(UUID userId, UUID chunkId) {
        return loadAuthorizedSource(userId, chunkId).map(this::toAvailabilityResponse);
    }

    private Optional<AuthorizedDocumentSource> loadAuthorizedSource(UUID userId, UUID chunkId) {
        if (userId == null || chunkId == null) {
            throw new DocumentNotFoundException();
        }
        return authorizedDocumentSourceRepository.findAuthorizedByChunkId(userId, chunkId);
    }

    private DocumentSourceResponse toResponse(AuthorizedDocumentSource source) {
        return new DocumentSourceResponse(source.getKnowledgeBaseId(), source.getDocumentId(),
                source.getDocumentName(), source.getVersionId(), source.getVersionNo(), source.getChunkId(),
                source.getChunkIndex(), source.getSection(), source.getPageNo(), source.getStartOffset(),
                source.getEndOffset(), source.isCurrent(), source.getContent());
    }

    private DocumentSourceAvailabilityResponse toAvailabilityResponse(AuthorizedDocumentSource source) {
        return new DocumentSourceAvailabilityResponse(source.getKnowledgeBaseId(), source.getDocumentId(),
                source.getDocumentName(), source.getVersionId(), source.getVersionNo(), source.getChunkId(),
                source.getChunkIndex(), source.getSection(), source.getPageNo(), source.getStartOffset(),
                source.getEndOffset(), source.isCurrent(), source.isAvailable());
    }
}
