package com.haha.supportai.data.knowledge.repository;

import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.AuthorizedDocumentSource;
import com.haha.supportai.data.knowledge.mapper.AuthorizedDocumentSourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuthorizedDocumentSourceRepository {

    private final AuthorizedDocumentSourceMapper authorizedDocumentSourceMapper;

    public Optional<AuthorizedDocumentSource> findReadableByChunkId(UUID userId, UUID chunkId) {
        return findAuthorizedByChunkId(userId, chunkId).filter(AuthorizedDocumentSource::isAvailable);
    }

    public Optional<AuthorizedDocumentSource> findAuthorizedByChunkId(UUID userId, UUID chunkId) {
        return authorizedDocumentSourceMapper.findAuthorizedByChunkId(userId, chunkId);
    }
}
