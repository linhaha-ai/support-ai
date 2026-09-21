package com.haha.supportai.knowledge.service;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.knowledge.repository.KnowledgeBaseRepository;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KnowledgeScopeAccessService implements KnowledgeScopeAccess {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAllReadable(UUID userId, List<UUID> knowledgeBaseIds) {
        if (userId == null || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
                || knowledgeBaseIds.stream().anyMatch(id -> id == null)) {
            return false;
        }
        return knowledgeBaseRepository.findAllActiveByIdsAndUserForUpdate(knowledgeBaseIds, userId).size()
                == knowledgeBaseIds.size();
    }
}
