package com.haha.supportai.data.knowledge.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.knowledge.enums.DocumentVersionStatusEnum;
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
@TableName(value = "document_version", autoResultMap = true)
public class DocumentVersion {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 文档版本标识，由业务层生成 UUID

    @TableField("document_id")
    private UUID documentId; // 所属文档标识

    @TableField("version_no")
    private int versionNo; // 文档内递增版本号

    @TableField("storage_key")
    private String storageKey; // 受控原文件内部存储键

    @TableField("file_hash")
    private String fileHash; // 原文件 SHA-256 摘要

    @TableField("file_size")
    private long fileSize; // 原文件字节数

    @TableField("media_type")
    private String mediaType; // 已校验的文件媒体类型

    @TableField("original_filename")
    private String originalFilename; // 清理路径后的原始文件名

    @TableField(value = "status", typeHandler = EnumTypeHandler.class)
    private DocumentVersionStatusEnum status; // 文档版本处理状态

    @TableField("parser_version")
    private String parserVersion; // 解析器版本

    @TableField("chunker_version")
    private String chunkerVersion; // 分块器版本

    @TableField("embedding_model")
    private String embeddingModel; // 嵌入模型名称

    @TableField("dimensions")
    private int dimensions; // 嵌入向量维度

    @TableField("config_hash")
    private String configHash; // 处理配置快照 SHA-256 摘要

    @TableField(value = "config_snapshot", jdbcType = JdbcType.VARCHAR)
    private String configSnapshot; // 不含密钥的处理配置 JSON 快照

    @TableField("chunk_count")
    private int chunkCount; // 已生成的文本分块数量

    @TableField("created_at")
    private OffsetDateTime createdAt; // 创建时间

    @TableField("updated_at")
    private OffsetDateTime updatedAt; // 最近更新时间
}
