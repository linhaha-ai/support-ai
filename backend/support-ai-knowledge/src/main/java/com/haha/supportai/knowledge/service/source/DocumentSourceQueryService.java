package com.haha.supportai.knowledge.service.source;

import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.AuthorizedDocumentSource;
import com.haha.supportai.data.knowledge.repository.AuthorizedDocumentSourceRepository;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.exception.DocumentNotFoundException;
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
     * 后台流式线程使用准备阶段确认的用户标识，只返回当前仍可读取的来源。
     */
    @Transactional(readOnly = true)
    public Optional<DocumentSourceResponse> findReadableSource(UUID userId, UUID chunkId) {
        if (userId == null || chunkId == null) {
            throw new DocumentNotFoundException();
        }
        return authorizedDocumentSourceRepository.findReadableByChunkId(userId, chunkId)
                .map(this::toResponse);
    }

    private DocumentSourceResponse toResponse(AuthorizedDocumentSource source) {
        return new DocumentSourceResponse(source.getKnowledgeBaseId(), source.getDocumentId(),
                source.getDocumentName(), source.getVersionId(), source.getVersionNo(), source.getChunkId(),
                source.getChunkIndex(), source.getSection(), source.getPageNo(), source.getStartOffset(),
                source.getEndOffset(), source.isCurrent(), source.getContent());
    }
}
