package com.haha.supportai.data.knowledge.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("document")
public class KnowledgeDocument {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 文档标识，由业务层生成 UUID

    @TableField("kb_id")
    private UUID kbId; // 所属知识库标识

    @TableField("name")
    private String name; // 文档展示名称，不作为物理存储路径

    @TableField("active_version_id")
    private UUID activeVersionId; // 当前可检索版本标识

    @TableField("pending_version_id")
    private UUID pendingVersionId; // 当前待处理版本标识

    @TableField("next_version_no")
    private int nextVersionNo; // 下次分配的文档版本号

    @TableField("created_by")
    private UUID createdBy; // 创建文档的用户标识

    @TableField("deleted_at")
    private OffsetDateTime deletedAt; // 逻辑删除时间

    @TableField("revision")
    private long revision; // 文档元数据修订号

    @TableField("created_at")
    private OffsetDateTime createdAt; // 创建时间

    @TableField("updated_at")
    private OffsetDateTime updatedAt; // 最近更新时间
}
