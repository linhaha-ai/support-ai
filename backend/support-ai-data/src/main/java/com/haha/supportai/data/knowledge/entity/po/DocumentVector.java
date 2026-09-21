package com.haha.supportai.data.knowledge.entity.po;

import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.typehandler.VectorTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "vector_store", autoResultMap = true)
public class DocumentVector {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 分块唯一标识，同时引用 document_chunk.id

    @TableField("content")
    private String content; // 用于检索和上下文拼接的分块正文

    @TableField(value = "metadata", jdbcType = JdbcType.VARCHAR)
    private String metadata; // 分块检索元数据 JSON

    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    private float[] embedding; // 固定 1024 维嵌入向量
}
