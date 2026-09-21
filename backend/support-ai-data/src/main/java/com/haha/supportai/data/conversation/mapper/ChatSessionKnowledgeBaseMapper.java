package com.haha.supportai.data.conversation.mapper;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.conversation.entity.po.ChatSessionKnowledgeBase;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatSessionKnowledgeBaseMapper {

    @Insert("""
            -- chat_session_kb：写入创建时已授权的固定知识库范围。
            INSERT INTO chat_session_kb (session_id, kb_id, created_at)
            VALUES (
                #{sessionKnowledgeBase.sessionId,jdbcType=OTHER},
                #{sessionKnowledgeBase.knowledgeBaseId,jdbcType=OTHER},
                #{sessionKnowledgeBase.createdAt}
            )
            """)
    int insert(@Param("sessionKnowledgeBase") ChatSessionKnowledgeBase sessionKnowledgeBase);

    @Select("""
            -- chat_session_kb：按固定顺序读取会话范围，供提交消息时再次校验访问权限。
            SELECT kb_id
            FROM chat_session_kb
            WHERE session_id = #{sessionId,jdbcType=OTHER}
            ORDER BY kb_id
            """)
    List<UUID> selectKnowledgeBaseIdsBySessionId(@Param("sessionId") UUID sessionId);
}
