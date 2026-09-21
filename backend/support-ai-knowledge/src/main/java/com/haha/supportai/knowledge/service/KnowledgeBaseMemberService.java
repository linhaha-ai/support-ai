package com.haha.supportai.knowledge.service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.repository.KbMemberRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.entity.vo.UserSummaryResponse;
import com.haha.supportai.identity.service.UserDirectoryService;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.entity.dto.UpdateKnowledgeBaseMemberRequest;
import com.haha.supportai.knowledge.entity.vo.KnowledgeBaseMemberResponse;
import com.haha.supportai.knowledge.exception.KnowledgeBaseInvalidRequestException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseMemberTargetUnavailableException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseMemberService {

    private final KbMemberRepository kbMemberRepository;
    private final UserDirectoryService userDirectoryService;
    private final AccessPolicy accessPolicy;

    public List<KnowledgeBaseMemberResponse> list(UUID knowledgeBaseId) {
        accessPolicy.requireManagement(knowledgeBaseId);
        Map<UUID, UserSummaryResponse> usersById = userDirectoryService.listForAdministration().stream()
                .collect(Collectors.toMap(UserSummaryResponse::getId, Function.identity()));
        return kbMemberRepository.listByKbId(knowledgeBaseId).stream()
                .map(member -> toResponse(member, usersById.get(member.getUserId())))
                .sorted(Comparator.comparing(KnowledgeBaseMemberResponse::getUsername))
                .toList();
    }

    @Transactional
    public KnowledgeBaseMemberResponse upsert(UUID knowledgeBaseId, UUID userId,
            UpdateKnowledgeBaseMemberRequest request) {
        AuthenticatedUser administrator = accessPolicy.requireManagement(knowledgeBaseId);
        if (request == null || request.getPermission() == null) {
            throw new KnowledgeBaseInvalidRequestException();
        }
        UserSummaryResponse targetUser = userDirectoryService.findEnabledForAdministration(userId)
                .orElseThrow(KnowledgeBaseMemberTargetUnavailableException::new);
        KbMember member = new KbMember(knowledgeBaseId, userId, request.getPermission(), administrator.getId(),
                OffsetDateTime.now());
        kbMemberRepository.upsert(member);
        KbMember persistedMember = kbMemberRepository.findByKbIdAndUserId(knowledgeBaseId, userId)
                .orElseThrow(KnowledgeBaseNotFoundException::new);
        return KnowledgeBaseMemberResponse.from(persistedMember, targetUser);
    }

    @Transactional
    public void revoke(UUID knowledgeBaseId, UUID userId) {
        accessPolicy.requireManagement(knowledgeBaseId);
        kbMemberRepository.deleteByKbIdAndUserId(knowledgeBaseId, userId);
    }

    private KnowledgeBaseMemberResponse toResponse(KbMember member, UserSummaryResponse user) {
        if (user == null) {
            throw new KnowledgeBaseMemberTargetUnavailableException();
        }
        return KnowledgeBaseMemberResponse.from(member, user);
    }
}
