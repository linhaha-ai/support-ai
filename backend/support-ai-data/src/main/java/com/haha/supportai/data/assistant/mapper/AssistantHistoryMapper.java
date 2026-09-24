package com.haha.supportai.data.assistant.mapper;

import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssistantHistoryMapper {

    String VALID_SOURCE = """
            FROM message_source source
            INNER JOIN chat_message message ON message.id = source.message_id
            INNER JOIN chat_run run ON run.id = message.run_id AND run.session_id = message.session_id
            INNER JOIN chat_session chat ON chat.id = message.session_id
            INNER JOIN app_user account ON account.id = chat.user_id
            INNER JOIN message_source root ON root.id = COALESCE(source.origin_source_id, source.id)
            INNER JOIN chat_message root_message ON root_message.id = root.message_id
            INNER JOIN chat_run root_run
                ON root_run.id = root_message.run_id AND root_run.session_id = root_message.session_id
            INNER JOIN tool_call call ON call.id = root.tool_call_id AND call.run_id = root_run.id
            INNER JOIN knowledge_base kb ON kb.id = root.kb_id
            INNER JOIN chat_session_kb scope ON scope.session_id = chat.id AND scope.kb_id = kb.id
            INNER JOIN kb_member member ON member.kb_id = kb.id AND member.user_id = chat.user_id
            WHERE source.id = #{sourceId,jdbcType=OTHER}
              AND chat.user_id = #{userId,jdbcType=OTHER}
              AND (CAST(#{sessionId,jdbcType=OTHER} AS UUID) IS NULL OR chat.id = #{sessionId,jdbcType=OTHER})
              AND chat.mode = 'ASSISTANT' AND chat.deleted_at IS NULL AND account.enabled = TRUE
              AND member.permission IN ('READ', 'WRITE') AND kb.status = 'ACTIVE'
              AND message.role = 'ASSISTANT' AND message.status IN ('STREAMING', 'COMPLETED')
              AND run.status IN ('RUNNING', 'COMPLETED')
              AND root.origin_source_id IS NULL
              AND root_message.session_id = chat.id AND root_message.role = 'ASSISTANT'
              AND (source.origin_source_id IS NULL OR (
                  root_message.seq < message.seq AND root_message.status = 'COMPLETED'
                  AND root_run.status = 'COMPLETED'
              ))
              AND root.source_type = source.source_type AND root.kb_id = source.kb_id
              AND root.chunk_id IS NOT DISTINCT FROM source.chunk_id
              AND root.ticket_id IS NOT DISTINCT FROM source.ticket_id
              AND root.tool_call_id = source.tool_call_id
              AND root.locator = source.locator AND root.snapshot = source.snapshot
              AND call.status = 'SUCCEEDED'
              AND ((root.source_type = 'DOCUMENT' AND call.tool_name = 'searchKnowledge'
                    AND EXISTS (
                        SELECT 1 FROM document_chunk chunk
                        INNER JOIN document_version version ON version.id = chunk.version_id
                        INNER JOIN document document ON document.id = version.document_id
                        INNER JOIN vector_store vector ON vector.id = chunk.id
                        WHERE chunk.id = root.chunk_id AND document.kb_id = kb.id
                          AND document.deleted_at IS NULL AND document.active_version_id = version.id
                          AND version.status = 'READY' AND LENGTH(BTRIM(vector.content)) > 0
                    )
                    AND EXISTS (
                        SELECT 1 FROM jsonb_array_elements(CASE
                            WHEN jsonb_typeof(call.result_snapshot -> 'sources') = 'array'
                            THEN call.result_snapshot -> 'sources' ELSE '[]'::jsonb END) evidence
                        WHERE evidence ->> 'sourceHandle' = root.id::text
                    ))
                OR (root.source_type = 'TICKET' AND call.tool_name = 'getTicketStatus'
                    AND EXISTS (SELECT 1 FROM ticket
                        WHERE ticket.id = root.ticket_id AND ticket.kb_id = kb.id
                          AND ticket.ticket_no = root.snapshot ->> 'ticketNo')
                    AND call.result_snapshot ->> 'sourceHandle' = root.id::text
                    AND call.result_snapshot ->> 'ticketNo' = root.snapshot ->> 'ticketNo'
                    AND call.result_snapshot ->> 'status' = root.snapshot ->> 'status'
                    AND call.result_snapshot ->> 'queriedAt' = root.snapshot ->> 'queriedAt'
                    AND call.result_snapshot ->> 'updatedAt' = root.snapshot ->> 'updatedAt'
                ))
            """;

    @Select("""
            /* message_source、tool_call：复核同一用户会话内的直接根来源、成功审计和当前资源权限；
               工单快照只代表原查询时刻，文档必须仍为当前有效版本。 */
            SELECT COUNT(*)
            """ + VALID_SOURCE)
    int countUsableSource(@Param("sourceId") UUID sourceId,
                          @Param("sessionId") UUID sessionId,
                          @Param("userId") UUID userId);

    @Insert("""
            /* message_source：从已授权历史根来源复制不可变定位和快照，保留历史 tool_call_id；
               不接受调用方指定旧快照或伪造本轮调用，仅写入当前执行中助手消息。 */
            WITH authorized_root AS (
                SELECT root.*, root_message.seq AS root_sequence
            """ + VALID_SOURCE + """
                  AND source.origin_source_id IS NULL
                  AND root_message.status = 'COMPLETED' AND root_run.status = 'COMPLETED'
            )
            INSERT INTO message_source (
                id, message_id, origin_source_id, citation_no, is_cited, source_type,
                kb_id, chunk_id, ticket_id, tool_call_id, locator, snapshot, created_at
            )
            SELECT #{newSourceId,jdbcType=OTHER}, message.id, root.id, #{citationNo}, FALSE,
                   root.source_type, root.kb_id, root.chunk_id, root.ticket_id, root.tool_call_id,
                   root.locator, root.snapshot, CURRENT_TIMESTAMP
            FROM authorized_root root
            INNER JOIN chat_message message ON message.id = #{assistantMessageId,jdbcType=OTHER}
            INNER JOIN chat_run run ON run.id = message.run_id AND run.session_id = message.session_id
            INNER JOIN chat_session chat ON chat.id = message.session_id
            WHERE run.id = #{runId,jdbcType=OTHER} AND chat.id = #{sessionId,jdbcType=OTHER}
              AND chat.user_id = #{userId,jdbcType=OTHER} AND chat.mode = 'ASSISTANT'
              AND chat.deleted_at IS NULL AND message.role = 'ASSISTANT'
              AND run.status = 'RUNNING' AND message.status = 'STREAMING'
              AND root.root_sequence < message.seq
            """)
    int insertInheritedSource(@Param("sourceId") UUID rootSourceId,
                              @Param("sessionId") UUID sessionId,
                              @Param("userId") UUID userId,
                              @Param("runId") UUID runId,
                              @Param("assistantMessageId") UUID assistantMessageId,
                              @Param("newSourceId") UUID newSourceId,
                              @Param("citationNo") int citationNo);
}
