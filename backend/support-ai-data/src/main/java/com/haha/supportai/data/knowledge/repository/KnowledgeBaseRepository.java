package com.haha.supportai.data.knowledge.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeBase;
import com.haha.supportai.data.knowledge.enums.KnowledgeBaseStatusEnum;
import com.haha.supportai.data.knowledge.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class KnowledgeBaseRepository {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBase insert(KnowledgeBase knowledgeBase) {
        knowledgeBaseMapper.insert(knowledgeBase);
        return knowledgeBase;
    }

    public Optional<KnowledgeBase> findById(UUID id) {
        return Optional.ofNullable(knowledgeBaseMapper.selectById(id));
    }

    public List<KnowledgeBase> listForManagement(KnowledgeBaseStatusEnum status) {
        return knowledgeBaseMapper.selectList(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(status != null, KnowledgeBase::getStatus, status)
                .orderByDesc(KnowledgeBase::getUpdatedAt)
                .orderByAsc(KnowledgeBase::getId));
    }

    public List<KnowledgeBase> listActiveByUser(UUID userId) {
        return knowledgeBaseMapper.selectActiveByUserId(userId);
    }

    public Optional<KnowledgeBase> findActiveByIdAndUser(UUID id, UUID userId) {
        return knowledgeBaseMapper.selectActiveByIdAndUserId(id, userId);
    }

    public Optional<KnowledgeBase> findActiveByIdAndUserForUpdate(UUID id, UUID userId) {
        return knowledgeBaseMapper.selectActiveByIdAndUserIdForUpdate(id, userId);
    }

    public List<KnowledgeBase> findAllActiveByIdsAndUserForUpdate(List<UUID> ids, UUID userId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return knowledgeBaseMapper.selectActiveByIdsAndUserIdForUpdate(ids, userId);
    }

    public boolean updateMetadataByIdAndRevision(UUID id, long revision, String name, String description,
                                                 KnowledgeBaseStatusEnum status) {
        return knowledgeBaseMapper.updateMetadataByIdAndRevision(id, revision, name, description, status) == 1;
    }
}
