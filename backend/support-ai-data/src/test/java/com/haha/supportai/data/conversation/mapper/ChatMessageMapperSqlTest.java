package com.haha.supportai.data.conversation.mapper;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageMapperSqlTest {

    @Test
    void historyPageJoinsOwnedUndeletedSessionAndOrdersBySequence() throws NoSuchMethodException {
        Method method = ChatMessageMapper.class.getMethod("selectOwnedSessionPage", UUID.class, UUID.class,
                long.class, int.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("INNER JOIN chat_session session ON session.id = message.session_id")
                .contains("session.user_id = #{userId,jdbcType=OTHER}")
                .contains("session.deleted_at IS NULL")
                .contains("message.seq > #{afterSequence}")
                .contains("ORDER BY message.seq")
                .contains("LIMIT #{limit}");
    }

    @Test
    void completionAndFailureUpdatesKeepStrictMessageConditionsWithoutRunActiveDependency()
            throws NoSuchMethodException {
        String completeSql = updateSql("completeAssistant", UUID.class, UUID.class, String.class,
                OffsetDateTime.class);
        String failSql = updateSql("failAssistant", UUID.class, UUID.class, String.class, OffsetDateTime.class);

        assertStrictStreamingAssistantCondition(completeSql);
        assertStrictStreamingAssistantCondition(failSql);
        assertThat(completeSql).doesNotContain("FROM chat_run");
        assertThat(failSql).doesNotContain("FROM chat_run");
    }

    @Test
    void cancelKeepsContentAndTargetsOnlyStreamingAssistant() throws NoSuchMethodException {
        String sql = updateSql("cancelAssistant", UUID.class, UUID.class, OffsetDateTime.class);

        assertStrictStreamingAssistantCondition(sql);
        assertThat(sql)
                .contains("status = 'CANCELLED'")
                .doesNotContain("content =");
    }

    @Test
    void recoveryOnlyFinishesStreamingAssistantsOfThisInterruptedRecoveryBatch() throws NoSuchMethodException {
        String sql = updateSql("interruptAssistants", OffsetDateTime.class);

        assertThat(sql)
                .contains("FROM chat_run run")
                .contains("message.role = 'ASSISTANT'")
                .contains("message.status = 'STREAMING'")
                .contains("run.status = 'FAILED'")
                .contains("run.error_code = 'RUN_INTERRUPTED'")
                .contains("run.finished_at = #{recoveredAt}")
                .contains("run.updated_at = #{recoveredAt}");
    }

    private void assertStrictStreamingAssistantCondition(String sql) {
        assertThat(sql)
                .contains("message.id = #{messageId,jdbcType=OTHER}")
                .contains("message.run_id = #{runId,jdbcType=OTHER}")
                .contains("message.role = 'ASSISTANT'")
                .contains("message.status = 'STREAMING'");
    }

    private String updateSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ChatMessageMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Update.class).value());
    }
}
