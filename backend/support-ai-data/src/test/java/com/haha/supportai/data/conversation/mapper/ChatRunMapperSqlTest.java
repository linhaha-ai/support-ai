package com.haha.supportai.data.conversation.mapper;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRunMapperSqlTest {

    @Test
    void ownerQueriesJoinOnlyUndeletedSessionAndLockRunWhenRequested() throws NoSuchMethodException {
        String querySql = selectSql("selectOwnedByIdAndUserId", UUID.class, UUID.class);
        String lockSql = selectSql("selectOwnedByIdAndUserIdForUpdate", UUID.class, UUID.class);

        assertThat(querySql)
                .contains("INNER JOIN chat_session session ON session.id = run.session_id")
                .contains("session.user_id = #{userId,jdbcType=OTHER}")
                .contains("session.deleted_at IS NULL");
        assertThat(lockSql)
                .contains("session.deleted_at IS NULL")
                .contains("FOR UPDATE OF run");
    }

    @Test
    void cancelOnlyChangesActiveRunsToCancelledWithSanitizedReason() throws NoSuchMethodException {
        String sql = updateSql("cancel", UUID.class, OffsetDateTime.class);

        assertThat(sql)
                .contains("status = 'CANCELLED'")
                .contains("error_code = 'RUN_CANCELLED'")
                .contains("error_detail = '用户已取消生成'")
                .contains("cancel_requested_at = #{cancelledAt}")
                .contains("finished_at = #{cancelledAt}")
                .contains("status IN ('QUEUED', 'RUNNING')");
    }

    @Test
    void recoveryOnlyInterruptsRunsCreatedBeforeStartupBoundary() throws NoSuchMethodException {
        String sql = updateSql("interruptActiveBefore", OffsetDateTime.class, OffsetDateTime.class);

        assertThat(sql)
                .contains("status = 'FAILED'")
                .contains("error_code = 'RUN_INTERRUPTED'")
                .contains("finished_at = #{recoveredAt}")
                .contains("status IN ('QUEUED', 'RUNNING')")
                .contains("created_at < #{startupBoundary}");
    }

    private String selectSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ChatRunMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Select.class).value());
    }

    private String updateSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ChatRunMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Update.class).value());
    }
}
