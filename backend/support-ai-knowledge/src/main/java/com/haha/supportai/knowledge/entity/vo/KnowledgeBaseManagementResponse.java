package com.haha.supportai.knowledge.entity.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.KnowledgeBase;
import com.haha.supportai.data.knowledge.enums.KnowledgeBaseStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "KnowledgeBaseManagementResponse", description = "管理员知识库元数据")
public class KnowledgeBaseManagementResponse {

    @Schema(description = "知识库标识", example = "550e8400-e29b-41d4-a716-446655440000", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID id; // 知识库标识

    @Schema(description = "知识库名称", example = "订单支付故障处理", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String name; // 知识库名称

    @Schema(description = "知识库业务范围，可为空", example = "沉淀支付回调失败资料")
    private final String description; // 知识库业务范围

    @Schema(description = "负责人用户标识", example = "550e8400-e29b-41d4-a716-446655440000", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID ownerId; // 负责人用户标识

    @Schema(description = "知识库状态", example = "ACTIVE", requiredMode = Schema.RequiredMode.REQUIRED)
    private final KnowledgeBaseStatusEnum status; // 知识库状态

    @Schema(description = "元数据版本号", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long revision; // 元数据版本号

    @Schema(description = "创建时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime createdAt; // 创建时间

    @Schema(description = "最近更新时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime updatedAt; // 最近更新时间

    public static KnowledgeBaseManagementResponse from(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseManagementResponse(knowledgeBase.getId(), knowledgeBase.getName(),
                knowledgeBase.getDescription(), knowledgeBase.getOwnerId(), knowledgeBase.getStatus(),
                knowledgeBase.getRevision(), knowledgeBase.getCreatedAt(), knowledgeBase.getUpdatedAt());
    }
}
