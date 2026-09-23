package com.haha.supportai.data.knowledge.repository;

import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.AuthorizedDocumentSource;
import com.haha.supportai.data.knowledge.mapper.AuthorizedDocumentSourceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizedDocumentSourceRepositoryTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID CHUNK_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Mock
    AuthorizedDocumentSourceMapper authorizedDocumentSourceMapper;

    @Test
    void readableLookupFiltersUnavailableDeletedSourceButAuthorizedLookupRetainsMetadata() {
        AuthorizedDocumentSource deletedSource = new AuthorizedDocumentSource(UUID.randomUUID(), UUID.randomUUID(),
                "已删除手册", UUID.randomUUID(), 1, CHUNK_ID, 0, "故障处理", null, 0, 10, null, false, false);
        when(authorizedDocumentSourceMapper.findAuthorizedByChunkId(USER_ID, CHUNK_ID))
                .thenReturn(Optional.of(deletedSource));
        AuthorizedDocumentSourceRepository repository = new AuthorizedDocumentSourceRepository(
                authorizedDocumentSourceMapper);

        assertThat(repository.findAuthorizedByChunkId(USER_ID, CHUNK_ID)).contains(deletedSource);
        assertThat(repository.findReadableByChunkId(USER_ID, CHUNK_ID)).isEmpty();
    }
}
