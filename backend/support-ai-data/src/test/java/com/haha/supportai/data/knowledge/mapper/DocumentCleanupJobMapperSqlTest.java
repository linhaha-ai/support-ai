package com.haha.supportai.data.knowledge.mapper;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentCleanupJobMapperSqlTest {

    @Test
    void claimOnlySelectsDeletedDocumentsAndReclaimsExpiredLeaseAtomically() throws NoSuchMethodException {
        String sql = selectSql("claimNext", String.class, UUID.class, OffsetDateTime.class);

        assertThat(sql)
                .contains("INNER JOIN document ON document.id = cleanup.document_id")
                .contains("document.deleted_at IS NOT NULL")
                .contains("cleanup.status IN ('PENDING', 'RETRY_WAIT')")
                .contains("cleanup.status = 'RUNNING'")
                .contains("cleanup.lease_until <= CURRENT_TIMESTAMP")
                .contains("FOR UPDATE OF cleanup SKIP LOCKED")
                .contains("attempt = cleanup.attempt + 1");
    }

    @Test
    void completionRequiresCurrentUnexpiredLease() throws NoSuchMethodException {
        String lockSql = selectSql("selectActiveLeaseForUpdate", UUID.class, UUID.class, String.class, UUID.class);
        String successSql = updateSql("markSucceeded", UUID.class, String.class, UUID.class);

        assertThat(lockSql)
                .contains("lease_token = #{leaseToken,jdbcType=OTHER}")
                .contains("lease_until > CURRENT_TIMESTAMP")
                .contains("FOR UPDATE");
        assertThat(successSql)
                .contains("status = 'SUCCEEDED'")
                .contains("lease_until > CURRENT_TIMESTAMP");
    }

    @Test
    void retryWriteRequiresCurrentUnexpiredLease() throws NoSuchMethodException {
        String retrySql = updateSql("markRetryWait", UUID.class, String.class, UUID.class,
                OffsetDateTime.class, String.class, String.class);

        assertThat(retrySql)
                .contains("status = 'RETRY_WAIT'")
                .contains("lease_owner = #{leaseOwner}")
                .contains("lease_token = #{leaseToken,jdbcType=OTHER}")
                .contains("lease_until > CURRENT_TIMESTAMP");
    }

    private String selectSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = DocumentCleanupJobMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Select.class).value());
    }

    private String updateSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = DocumentCleanupJobMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Update.class).value());
    }
}
