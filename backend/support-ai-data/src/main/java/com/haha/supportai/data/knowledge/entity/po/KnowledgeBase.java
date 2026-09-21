package com.haha.supportai.data.knowledge.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.knowledge.enums.KnowledgeBaseStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.EnumTypeHandler;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "knowledge_base", autoResultMap = true)
public class KnowledgeBase {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 知识库标识，由业务层生成 UUID

    @TableField("name")
    private String name; // 知识库名称

    @TableField("description")
    private String description; // 知识库说明

    @TableField("owner_id")
    private UUID ownerId; // 知识库负责人用户标识

    @TableField(value = "status", typeHandler = EnumTypeHandler.class)
    private KnowledgeBaseStatusEnum status; // 知识库状态，按枚举名称存储

    @TableField("revision")
    private long revision; // 元数据修订号

    @TableField("created_at")
    private OffsetDateTime createdAt; // 创建时间

    @TableField("updated_at")
    private OffsetDateTime updatedAt; // 更新时间
}
