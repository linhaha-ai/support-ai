package com.haha.supportai.knowledge.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeBase;
import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import com.haha.supportai.data.knowledge.enums.KnowledgeBaseStatusEnum;
import com.haha.supportai.data.knowledge.repository.KbMemberRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeBaseRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.knowledge.entity.dto.CreateKnowledgeBaseRequest;
import com.haha.supportai.knowledge.entity.dto.UpdateKnowledgeBaseRequest;
import com.haha.supportai.knowledge.entity.vo.KnowledgeBaseDetailResponse;
import com.haha.supportai.knowledge.entity.vo.KnowledgeBaseManagementResponse;
import com.haha.supportai.knowledge.entity.vo.KnowledgeBaseSummaryResponse;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseConflictException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseInvalidRequestException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbMemberRepository kbMemberRepository;
    private final ActiveAccountGuard activeAccountGuard;

    public List<KnowledgeBaseSummaryResponse> listAccessible() {
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        return knowledgeBaseRepository.listActiveByUser(currentUser.getId()).stream()
                .map(knowledgeBase -> toSummaryIfStillMember(knowledgeBase, currentUser.getId()))
                .flatMap(Optional::stream)
                .toList();
    }

    public List<KnowledgeBaseManagementResponse> listAll(KnowledgeBaseStatusEnum status) {
        requireAdmin();
        return knowledgeBaseRepository.listForManagement(status).stream()
                .map(KnowledgeBaseManagementResponse::from)
                .toList();
    }

    @Transactional
    public KnowledgeBaseDetailResponse create(CreateKnowledgeBaseRequest request) {
        AuthenticatedUser currentUser = requireAdmin();
        if (request == null) {
            throw new KnowledgeBaseInvalidRequestException();
        }

        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeBase knowledgeBase = new KnowledgeBase(UUID.randomUUID(), normalizeRequiredName(request.getName()),
                normalizeDescription(request.getDescription()), currentUser.getId(), KnowledgeBaseStatusEnum.ACTIVE, 0,
                now, now);
        knowledgeBaseRepository.insert(knowledgeBase);

        KbMember member = new KbMember(knowledgeBase.getId(), currentUser.getId(), KbPermissionEnum.WRITE,
                currentUser.getId(), now);
        kbMemberRepository.insert(member);
        return KnowledgeBaseDetailResponse.from(knowledgeBase, member.getPermission());
    }

    public KnowledgeBaseDetailResponse getAccessible(UUID knowledgeBaseId) {
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findActiveByIdAndUser(knowledgeBaseId, currentUser.getId())
                .orElseThrow(KnowledgeBaseNotFoundException::new);
        KbMember member = kbMemberRepository.findByKbIdAndUserId(knowledgeBaseId, currentUser.getId())
                .orElseThrow(KnowledgeBaseNotFoundException::new);
        return KnowledgeBaseDetailResponse.from(knowledgeBase, member.getPermission());
    }

    @Transactional
    public KnowledgeBaseManagementResponse update(UUID knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        requireAdmin();
        if (request == null || request.getRevision() == null || request.getRevision() < 0) {
            throw new KnowledgeBaseInvalidRequestException();
        }
        if (request.getName() == null && request.getDescription() == null && request.getStatus() == null) {
            throw new KnowledgeBaseInvalidRequestException();
        }

        KnowledgeBase current = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(KnowledgeBaseNotFoundException::new);
        String name = request.getName() == null ? current.getName() : normalizeRequiredName(request.getName());
        String description = request.getDescription() == null ? current.getDescription()
                : normalizeDescription(request.getDescription());
        KnowledgeBaseStatusEnum status = request.getStatus() == null ? current.getStatus() : request.getStatus();

        boolean updated = knowledgeBaseRepository.updateMetadataByIdAndRevision(knowledgeBaseId, request.getRevision(),
                name, description, status);
        if (!updated) {
            if (knowledgeBaseRepository.findById(knowledgeBaseId).isEmpty()) {
                throw new KnowledgeBaseNotFoundException();
            }
            throw new KnowledgeBaseConflictException();
        }

        KnowledgeBase updatedKnowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(KnowledgeBaseNotFoundException::new);
        return KnowledgeBaseManagementResponse.from(updatedKnowledgeBase);
    }

    private Optional<KnowledgeBaseSummaryResponse> toSummaryIfStillMember(KnowledgeBase knowledgeBase,
            UUID userId) {
        return kbMemberRepository.findByKbIdAndUserId(knowledgeBase.getId(), userId)
                .map(member -> KnowledgeBaseSummaryResponse.from(knowledgeBase, member.getPermission()));
    }

    private AuthenticatedUser requireAdmin() {
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        if (currentUser.getRole() != UserRoleEnum.ADMIN) {
            throw new KnowledgeBaseAccessDeniedException();
        }
        return currentUser;
    }

    private String normalizeRequiredName(String name) {
        if (name == null) {
            throw new KnowledgeBaseInvalidRequestException();
        }
        String normalizedName = name.trim();
        if (normalizedName.isEmpty()) {
            throw new KnowledgeBaseInvalidRequestException();
        }
        return normalizedName;
    }

    private String normalizeDescription(String description) {
        return description != null && description.isEmpty() ? null : description;
    }
}
