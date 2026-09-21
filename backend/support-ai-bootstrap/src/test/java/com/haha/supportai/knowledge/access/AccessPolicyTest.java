package com.haha.supportai.knowledge.access;

import java.time.OffsetDateTime;
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
import com.haha.supportai.identity.security.AdministratorGuard;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.knowledge.entity.dto.KnowledgeBaseAccessContext;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessPolicyTest {

    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Mock
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    KbMemberRepository kbMemberRepository;

    @Mock
    ActiveAccountGuard activeAccountGuard;

    @Mock
    AdministratorGuard administratorGuard;

    @InjectMocks
    AccessPolicy accessPolicy;

    private AuthenticatedUser user;
    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T00:00:00Z");
        user = new AuthenticatedUser(USER_ID, "support1", "技术支持一号", UserRoleEnum.USER);
        knowledgeBase = new KnowledgeBase(KNOWLEDGE_BASE_ID, "支付故障手册", null, USER_ID,
                KnowledgeBaseStatusEnum.ACTIVE, 0, now, now);
    }

    @Test
    void writeMemberPassesReadAndWriteChecks() {
        KbMember member = member(KbPermissionEnum.WRITE);
        stubAccessible(member);

        KnowledgeBaseAccessContext result = accessPolicy.requireWrite(KNOWLEDGE_BASE_ID);

        assertThat(result.getKnowledgeBase()).isSameAs(knowledgeBase);
        assertThat(result.getMember()).isSameAs(member);
    }

    @Test
    void readMemberCannotPassWriteCheck() {
        stubAccessible(member(KbPermissionEnum.READ));

        assertThatThrownBy(() -> accessPolicy.requireWrite(KNOWLEDGE_BASE_ID))
                .isInstanceOf(KnowledgeBaseAccessDeniedException.class);
    }

    @Test
    void administratorWithoutMembershipCannotDiscoverDataResource() {
        AuthenticatedUser administrator = new AuthenticatedUser(USER_ID, "admin", "系统管理员", UserRoleEnum.ADMIN);
        when(activeAccountGuard.currentUser()).thenReturn(administrator);
        when(knowledgeBaseRepository.findActiveByIdAndUser(KNOWLEDGE_BASE_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessPolicy.requireRead(KNOWLEDGE_BASE_ID))
                .isInstanceOf(KnowledgeBaseNotFoundException.class);
    }

    @Test
    void administratorCanManageExistingKnowledgeBaseWithoutMembership() {
        AuthenticatedUser administrator = new AuthenticatedUser(USER_ID, "admin", "系统管理员", UserRoleEnum.ADMIN);
        when(administratorGuard.requireAdministrator()).thenReturn(administrator);
        when(knowledgeBaseRepository.findById(KNOWLEDGE_BASE_ID)).thenReturn(Optional.of(knowledgeBase));

        assertThat(accessPolicy.requireManagement(KNOWLEDGE_BASE_ID)).isSameAs(administrator);
    }

    private void stubAccessible(KbMember member) {
        when(activeAccountGuard.currentUser()).thenReturn(user);
        when(knowledgeBaseRepository.findActiveByIdAndUser(KNOWLEDGE_BASE_ID, USER_ID))
                .thenReturn(Optional.of(knowledgeBase));
        when(kbMemberRepository.findByKbIdAndUserId(KNOWLEDGE_BASE_ID, USER_ID))
                .thenReturn(Optional.of(member));
    }

    private KbMember member(KbPermissionEnum permission) {
        return new KbMember(KNOWLEDGE_BASE_ID, USER_ID, permission, USER_ID,
                OffsetDateTime.parse("2026-09-21T00:00:00Z"));
    }
}
