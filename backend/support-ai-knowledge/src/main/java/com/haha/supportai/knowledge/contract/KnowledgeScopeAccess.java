package com.haha.supportai.knowledge.contract;

import java.util.List;
import java.util.UUID;

public interface KnowledgeScopeAccess {

    boolean lockAllReadable(UUID userId, List<UUID> knowledgeBaseIds);
}
