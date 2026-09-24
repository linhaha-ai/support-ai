package com.haha.supportai.data.knowledge.mapper;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentDeletionMapperSqlTest {

    @Test
    void writableLockIncludesDeletedDocumentButRequiresCurrentWriteMembership() throws NoSuchMethodException {
        String sql = selectSql(KnowledgeDocumentMapper.class,
                "selectWritableByIdAndUserIdForUpdateIncludingDeleted", UUID.class, UUID.class);

        assertThat(sql)
                .contains("member.permission = 'WRITE'")
                .contains("FOR UPDATE OF document")
                .doesNotContain("document.deleted_at IS NULL");
    }

    @Test
    void logicalDeleteClearsPublishedAndPendingPointers() throws NoSuchMethodException {
        String sql = updateSql(KnowledgeDocumentMapper.class, "softDeleteAndClearVersions",
                UUID.class, OffsetDateTime.class);

        assertThat(sql)
                .contains("deleted_at = #{deletedAt}")
                .contains("active_version_id = NULL")
                .contains("pending_version_id = NULL")
                .contains("revision = revision + 1")
                .contains("deleted_at IS NULL");
    }

    @Test
    void deleteCancelsWorkAndRevokesEveryIngestionLease() throws NoSuchMethodException {
        String sql = updateSql(IngestionJobMapper.class, "cancelNonTerminalByDocumentId",
                UUID.class, OffsetDateTime.class);

        assertThat(sql)
                .contains("status = 'CANCELLED'")
                .contains("lease_owner = NULL")
                .contains("lease_token = NULL")
                .contains("lease_until = NULL")
                .contains("heartbeat_at = NULL")
                .contains("job.status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')");
    }

    @Test
    void workerClaimRequiresUndeletedDocumentAndMatchingPendingVersion() throws NoSuchMethodException {
        String sql = selectSql(IngestionJobMapper.class, "claimNextByStage",
                IngestionStageEnum.class, String.class, UUID.class, OffsetDateTime.class);

        assertThat(sql)
                .contains("document.deleted_at IS NULL")
                .contains("document.pending_version_id = version.id")
                .contains("FOR UPDATE OF job SKIP LOCKED");
    }

    @Test
    void physicalCleanupPreservesHistoryRowsAndDeletesOnlyVectors() throws NoSuchMethodException {
        String storageSql = selectSql(DocumentVersionMapper.class,
                "selectStorageKeysForDeletedDocument", UUID.class);
        Method deleteMethod = DocumentVectorMapper.class.getMethod("deleteByDeletedDocumentId", UUID.class);
        String vectorSql = String.join("\n", deleteMethod.getAnnotation(Delete.class).value());

        assertThat(storageSql).contains("document.deleted_at IS NOT NULL");
        assertThat(vectorSql)
                .contains("DELETE FROM vector_store")
                .contains("document.deleted_at IS NOT NULL")
                .doesNotContain("DELETE FROM document_chunk");
    }

    private String selectSql(Class<?> mapperType, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = mapperType.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Select.class).value());
    }

    private String updateSql(Class<?> mapperType, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = mapperType.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Update.class).value());
    }
}
