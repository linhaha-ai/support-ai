package com.haha.supportai.data.knowledge.repository;

import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.mapper.DocumentVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentVersionRepository {

    private final DocumentVersionMapper documentVersionMapper;

    public DocumentVersion insert(DocumentVersion documentVersion) {
        documentVersionMapper.insertDocumentVersion(documentVersion);
        return documentVersion;
    }
}
