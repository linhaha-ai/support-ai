package com.haha.supportai.knowledge.service.source;

import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.knowledge.entity.po.AuthorizedDocumentSource;
import com.haha.supportai.data.knowledge.repository.AuthorizedDocumentSourceRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.exception.DocumentNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentSourceQueryServiceTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID KB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID DOCUMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
    private static final UUID VERSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");
    private static final UUID CHUNK_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440004");

    @Mock
    AuthorizedDocumentSourceRepository authorizedDocumentSourceRepository;

    @Mock
    AccessPolicy accessPolicy;

    @Test
    void returnsAuthorizedHistoricalReadySourceWithCurrentMarker() {
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(authorizedDocumentSourceRepository.findAuthorizedByChunkId(USER_ID, CHUNK_ID))
                .thenReturn(Optional.of(source(false)));

        var response = service().getReadableSource(CHUNK_ID);

        assertThat(response.getDocumentName()).isEqualTo("退款排查手册");
        assertThat(response.getVersionNo()).isEqualTo(2);
        assertThat(response.getSection()).isEqualTo("退款/状态");
        assertThat(response.getContent()).isEqualTo("退款状态仍在处理中");
        assertThat(response.isCurrent()).isFalse();
        verify(authorizedDocumentSourceRepository).findAuthorizedByChunkId(USER_ID, CHUNK_ID);
    }

    @Test
    void hidesMissingRevokedOrDeletedSource() {
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(authorizedDocumentSourceRepository.findAuthorizedByChunkId(USER_ID, CHUNK_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getReadableSource(CHUNK_ID))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void backgroundLookupUsesTrustedUserWithoutReadingRequestContext() {
        when(authorizedDocumentSourceRepository.findAuthorizedByChunkId(USER_ID, CHUNK_ID))
                .thenReturn(Optional.of(source(true)));

        var response = service().findReadableSource(USER_ID, CHUNK_ID);

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().getChunkId()).isEqualTo(CHUNK_ID);
        verify(accessPolicy, never()).currentUser();
    }

    @Test
    void keepsDeletedSourceMetadataForAuthorizedHistoryButHidesPreview() {
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(authorizedDocumentSourceRepository.findAuthorizedByChunkId(USER_ID, CHUNK_ID))
                .thenReturn(Optional.of(source(false, false)));

        var historySource = service().findAuthorizedSource(USER_ID, CHUNK_ID);

        assertThat(historySource).isPresent();
        assertThat(historySource.orElseThrow().getDocumentName()).isEqualTo("退款排查手册");
        assertThat(historySource.orElseThrow().isAvailable()).isFalse();
        assertThat(historySource.orElseThrow().isCurrent()).isFalse();
        assertThatThrownBy(() -> service().getReadableSource(CHUNK_ID))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void nullChunkIsHiddenBeforeLoadingCurrentUser() {
        assertThatThrownBy(() -> service().getReadableSource(null))
                .isInstanceOf(DocumentNotFoundException.class);

        verify(accessPolicy, never()).currentUser();
    }

    private DocumentSourceQueryService service() {
        return new DocumentSourceQueryService(authorizedDocumentSourceRepository, accessPolicy);
    }

    private AuthenticatedUser currentUser() {
        return new AuthenticatedUser(USER_ID, "support", "支持用户", UserRoleEnum.USER);
    }

    private AuthorizedDocumentSource source(boolean current) {
        return source(current, true);
    }

    private AuthorizedDocumentSource source(boolean current, boolean available) {
        return new AuthorizedDocumentSource(KB_ID, DOCUMENT_ID, "退款排查手册", VERSION_ID, 2, CHUNK_ID, 0,
                "退款/状态", null, 0, 10, "退款状态仍在处理中", current, available);
    }
}
