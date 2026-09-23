package com.haha.supportai.data.knowledge.mapper;

import java.lang.reflect.Method;
import java.util.UUID;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizedDocumentSourceMapperSqlTest {

    @Test
    void authorizedSourceQueryKeepsDeletedMetadataButNeverReturnsDeletedBody() throws NoSuchMethodException {
        Method method = AuthorizedDocumentSourceMapper.class.getMethod("findAuthorizedByChunkId", UUID.class,
                UUID.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("LEFT JOIN vector_store vector ON vector.id = chunk.id")
                .contains("CASE WHEN document.deleted_at IS NULL THEN vector.content ELSE NULL END AS content")
                .contains("document.deleted_at IS NULL AND document.active_version_id = version.id")
                .contains("document.deleted_at IS NULL AND LENGTH(BTRIM(vector.content)) > 0")
                .contains("AS is_available")
                .contains("document.deleted_at IS NOT NULL OR LENGTH(BTRIM(vector.content)) > 0")
                .contains("member.permission IN ('READ', 'WRITE')")
                .contains("account.enabled = TRUE")
                .contains("kb.status = 'ACTIVE'")
                .contains("version.status = 'READY'");
    }
}
