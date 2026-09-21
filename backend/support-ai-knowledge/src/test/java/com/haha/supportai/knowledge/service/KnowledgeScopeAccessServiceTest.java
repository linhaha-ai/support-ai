package com.haha.supportai.knowledge.service;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.KnowledgeBase;
import com.haha.supportai.data.knowledge.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class KnowledgeScopeAccessServiceTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID FIRST_KB_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID SECOND_KB_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");

    @Mock
    KnowledgeBaseRepository knowledgeBaseRepository;

    @InjectMocks
    KnowledgeScopeAccessService scopeAccessService;

    @Test
    void allReadableRequiresEveryKnowledgeBaseToRemainAuthorized() {
        List<UUID> knowledgeBaseIds = List.of(FIRST_KB_ID, SECOND_KB_ID);
        given(knowledgeBaseRepository.findAllActiveByIdsAndUser(knowledgeBaseIds, USER_ID))
                .willReturn(List.of(new KnowledgeBase(), new KnowledgeBase()));

        assertThat(scopeAccessService.allReadable(USER_ID, knowledgeBaseIds)).isTrue();
    }

    @Test
    void allReadableRejectsPartiallyRevokedScope() {
        List<UUID> knowledgeBaseIds = List.of(FIRST_KB_ID, SECOND_KB_ID);
        given(knowledgeBaseRepository.findAllActiveByIdsAndUser(knowledgeBaseIds, USER_ID))
                .willReturn(List.of(new KnowledgeBase()));

        assertThat(scopeAccessService.allReadable(USER_ID, knowledgeBaseIds)).isFalse();
    }

    @Test
    void allReadableRejectsEmptyScopeWithoutQueryingDatabase() {
        assertThat(scopeAccessService.allReadable(USER_ID, List.of())).isFalse();

        then(knowledgeBaseRepository).shouldHaveNoInteractions();
    }
}
