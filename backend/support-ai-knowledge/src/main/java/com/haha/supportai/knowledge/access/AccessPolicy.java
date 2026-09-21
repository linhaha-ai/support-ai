package com.haha.supportai.knowledge.access;

import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeBase;
import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import com.haha.supportai.data.knowledge.repository.KbMemberRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeBaseRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.security.AdministratorGuard;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.knowledge.entity.dto.KnowledgeBaseAccessContext;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AccessPolicy {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbMemberRepository kbMemberRepository;
    private final ActiveAccountGuard activeAccountGuard;
    private final AdministratorGuard administratorGuard;

    public AuthenticatedUser currentUser() {
        return activeAccountGuard.currentUser();
    }

    public AuthenticatedUser requireAdministrator() {
        return administratorGuard.requireAdministrator();
    }

    public AuthenticatedUser requireManagement(UUID knowledgeBaseId) {
        AuthenticatedUser administrator = requireAdministrator();
        knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(KnowledgeBaseNotFoundException::new);
        return administrator;
    }

    public KnowledgeBaseAccessContext requireRead(UUID knowledgeBaseId) {
        AuthenticatedUser currentUser = currentUser();
        KnowledgeBase knowledgeBase = knowledgeBaseRepository
                .findActiveByIdAndUser(knowledgeBaseId, currentUser.getId())
                .orElseThrow(KnowledgeBaseNotFoundException::new);
        KbMember member = kbMemberRepository.findByKbIdAndUserId(knowledgeBaseId, currentUser.getId())
                .orElseThrow(KnowledgeBaseNotFoundException::new);
        return new KnowledgeBaseAccessContext(knowledgeBase, member);
    }

    public KnowledgeBaseAccessContext requireWrite(UUID knowledgeBaseId) {
        KnowledgeBaseAccessContext accessContext = requireRead(knowledgeBaseId);
        if (accessContext.getMember().getPermission() != KbPermissionEnum.WRITE) {
            throw new KnowledgeBaseAccessDeniedException();
        }
        return accessContext;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public KnowledgeBaseAccessContext requireWriteForUpdate(UUID knowledgeBaseId) {
        AuthenticatedUser currentUser = currentUser();
        KnowledgeBase knowledgeBase = knowledgeBaseRepository
                .findActiveByIdAndUserForUpdate(knowledgeBaseId, currentUser.getId())
                .orElseThrow(KnowledgeBaseNotFoundException::new);
        KbMember member = kbMemberRepository.findByKbIdAndUserId(knowledgeBaseId, currentUser.getId())
                .orElseThrow(KnowledgeBaseNotFoundException::new);
        if (member.getPermission() != KbPermissionEnum.WRITE) {
            throw new KnowledgeBaseAccessDeniedException();
        }
        return new KnowledgeBaseAccessContext(knowledgeBase, member);
    }
}
