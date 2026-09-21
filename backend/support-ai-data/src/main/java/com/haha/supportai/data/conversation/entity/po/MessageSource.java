package com.haha.supportai.data.conversation.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.EnumTypeHandler;
import org.apache.ibatis.type.JdbcType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "message_source", autoResultMap = true)
public class MessageSource {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 消息来源唯一标识，由业务层生成 UUID

    @TableField("message_id")
    private UUID messageId; // 所属助手消息标识

    @TableField("origin_source_id")
    private UUID originSourceId; // 继承来源的原始来源标识，直接检索来源为空

    @TableField("citation_no")
    private int citationNo; // 本轮答案中的正整数引用编号

    @TableField("is_cited")
    private boolean cited; // 最终答案是否显式引用该来源

    @TableField(value = "source_type", typeHandler = EnumTypeHandler.class)
    private MessageSourceTypeEnum sourceType; // 来源类型，F12 仅支持文档来源

    @TableField("kb_id")
    private UUID knowledgeBaseId; // 来源所属知识库标识

    @TableField("chunk_id")
    private UUID chunkId; // 文档分块标识

    @TableField(value = "locator", jdbcType = JdbcType.VARCHAR)
    private String locator; // 章节、页码和字符偏移等定位信息 JSON

    @TableField(value = "snapshot", jdbcType = JdbcType.VARCHAR)
    private String snapshot; // 文档与版本等生成时快照 JSON

    @TableField("created_at")
    private OffsetDateTime createdAt; // 消息来源登记时间
}
