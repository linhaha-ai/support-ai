package com.haha.supportai.data.conversation.mapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSourceMapperSqlTest {

    @Test
    void inheritedSourceMustPointToEarlierDirectRootInSameOwnedSession() throws NoSuchMethodException {
        Method method = MessageSourceMapper.class.getMethod("insertAll", UUID.class, List.class);
        String sql = String.join("\n", method.getAnnotation(Insert.class).value());

        assertThat(sql)
                .contains("origin.id = CAST(#{source.originSourceId,jdbcType=OTHER} AS UUID)")
                .contains("origin.origin_source_id IS NULL")
                .contains("origin_session.id = chat.id")
                .contains("origin_session.user_id = chat.user_id")
                .contains("origin_message.seq &lt; message.seq")
                .contains("origin_message.status = 'COMPLETED'")
                .contains("origin_run.status = 'COMPLETED'")
                .contains("origin.source_type = #{source.sourceType,jdbcType=VARCHAR}")
                .contains("origin.kb_id = #{source.knowledgeBaseId,jdbcType=OTHER}")
                .contains("origin.chunk_id = #{source.chunkId,jdbcType=OTHER}")
                .contains("origin.locator = CAST(#{source.locator,jdbcType=VARCHAR} AS JSONB)")
                .contains("origin.snapshot = CAST(#{source.snapshot,jdbcType=VARCHAR} AS JSONB)");
    }

    @Test
    void historicalSourceQueryReturnsAllRowsAndMarksInvalidLineage() throws NoSuchMethodException {
        Method method = MessageSourceMapper.class.getMethod("selectHistoricalSources", UUID.class, UUID.class,
                UUID.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("INNER JOIN message_source source")
                .contains("LEFT JOIN message_source root")
                .contains("WHEN source.origin_source_id IS NULL THEN TRUE")
                .contains("root.origin_source_id IS NULL")
                .contains("root_message.session_id = history_message.session_id")
                .contains("root_message.seq < history_message.seq")
                .contains("END AS lineage_valid")
                .contains("history_message.id = #{assistantMessageId,jdbcType=OTHER}")
                .contains("history_message.session_id = #{sessionId,jdbcType=OTHER}")
                .contains("history_session.user_id = #{userId,jdbcType=OTHER}")
                .contains("ORDER BY source.citation_no")
                .doesNotContain("WHERE root.id IS NOT NULL");
    }
}
