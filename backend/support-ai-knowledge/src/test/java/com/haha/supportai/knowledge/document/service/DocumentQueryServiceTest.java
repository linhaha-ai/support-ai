package com.haha.supportai.knowledge.document.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.knowledge.entity.po.DocumentChunkContent;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeBase;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeDocument;
import com.haha.supportai.data.knowledge.enums.DocumentVersionStatusEnum;
import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeDocumentRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.exception.DocumentNotFoundException;
import com.haha.supportai.knowledge.document.exception.DocumentQueryInvalidException;
import com.haha.supportai.knowledge.document.parsing.service.DocumentSourceReader;
import com.haha.supportai.knowledge.entity.dto.KnowledgeBaseAccessContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentQueryServiceTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID KB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID DOCUMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
    private static final UUID VERSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");

    @Mock
    KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Mock
    DocumentVersionRepository documentVersionRepository;

    @Mock
    IngestionJobRepository ingestionJobRepository;

    @Mock
    DocumentChunkRepository documentChunkRepository;

    @Mock
    DocumentSourceReader documentSourceReader;

    @Mock
    AccessPolicy accessPolicy;

    @Test
    void listsOnlyAfterKnowledgeBaseMembershipHasBeenValidated() {
        KbMember member = new KbMember();
        member.setKbId(KB_ID);
        member.setUserId(USER_ID);
        member.setPermission(KbPermissionEnum.READ);
        when(accessPolicy.requireRead(KB_ID))
                .thenReturn(new KnowledgeBaseAccessContext(new KnowledgeBase(), member));
        when(knowledgeDocumentRepository.listAccessibleByKnowledgeBaseId(KB_ID, USER_ID)).thenReturn(List.of());

        assertThat(service().list(KB_ID)).isEmpty();

        verify(accessPolicy).requireRead(KB_ID);
        verify(knowledgeDocumentRepository).listAccessibleByKnowledgeBaseId(KB_ID, USER_ID);
    }

    @Test
    void hidesMissingOrInaccessibleDocument() {
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(knowledgeDocumentRepository.findAccessibleByIdAndUserId(DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(DOCUMENT_ID))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void rejectsInvalidLimitAndOpaqueCursor() {
        KnowledgeDocument document = document();
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(knowledgeDocumentRepository.findAccessibleByIdAndUserId(DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service().listVersions(DOCUMENT_ID, 101, null))
                .isInstanceOf(DocumentQueryInvalidException.class);
        assertThatThrownBy(() -> service().listVersions(DOCUMENT_ID, 20, "not-a-cursor"))
                .isInstanceOf(DocumentQueryInvalidException.class);
    }

    @Test
    void readsVerifiedSourceOnlyAfterDocumentAndVersionAuthorization() {
        KnowledgeDocument document = document();
        DocumentVersion version = version();
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(knowledgeDocumentRepository.findAccessibleByIdAndUserId(DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.of(document));
        when(documentVersionRepository.findAccessibleByDocumentIdAndIdAndUserId(
                DOCUMENT_ID, VERSION_ID, USER_ID)).thenReturn(Optional.of(version));
        when(documentSourceReader.readVerifiedUtf8(version)).thenReturn("已校验原文");

        var response = service().getContent(DOCUMENT_ID, VERSION_ID);

        assertThat(response.getContent()).isEqualTo("已校验原文");
        assertThat(response.isActive()).isTrue();
        assertThat(response.isPending()).isFalse();
        verify(documentSourceReader).readVerifiedUtf8(version);
    }

    @Test
    void returnsChunkLocationAndNullContentWithNextCursor() {
        DocumentVersion version = version();
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(documentVersionRepository.findAccessibleByDocumentIdAndIdAndUserId(
                DOCUMENT_ID, VERSION_ID, USER_ID)).thenReturn(Optional.of(version));
        when(documentChunkRepository.listContentByVersionId(VERSION_ID, null, 2))
                .thenReturn(List.of(chunk(0, null), chunk(1, "下一块")));

        var response = service().listChunks(DOCUMENT_ID, VERSION_ID, 1, null);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getSection()).isEqualTo("支付/退款");
        assertThat(response.getItems().get(0).getContent()).isNull();
        assertThat(response.getNextCursor()).isNotBlank();
    }

    private DocumentQueryService service() {
        return new DocumentQueryService(knowledgeDocumentRepository, documentVersionRepository,
                ingestionJobRepository, documentChunkRepository, documentSourceReader, accessPolicy);
    }

    private AuthenticatedUser currentUser() {
        return new AuthenticatedUser(USER_ID, "support1", "支持用户", UserRoleEnum.USER);
    }

    private KnowledgeDocument document() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T00:00:00Z");
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(DOCUMENT_ID);
        document.setKbId(KB_ID);
        document.setName("退款说明");
        document.setActiveVersionId(VERSION_ID);
        document.setRevision(1);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        return document;
    }

    private DocumentVersion version() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T00:00:00Z");
        DocumentVersion version = new DocumentVersion();
        version.setId(VERSION_ID);
        version.setDocumentId(DOCUMENT_ID);
        version.setVersionNo(1);
        version.setOriginalFilename("refund.md");
        version.setMediaType("text/markdown");
        version.setStatus(DocumentVersionStatusEnum.READY);
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        return version;
    }

    private DocumentChunkContent chunk(int index, String content) {
        return new DocumentChunkContent(UUID.randomUUID(), VERSION_ID, index, "支付/退款", null,
                index * 10, index * 10 + 10, 10, 4, content);
    }
}
