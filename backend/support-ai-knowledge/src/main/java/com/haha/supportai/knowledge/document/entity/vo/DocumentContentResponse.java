package com.haha.supportai.knowledge.document.entity.vo;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "DocumentContentResponse", description = "校验后读取的文档版本原文")
public class DocumentContentResponse {

    @Schema(description = "文档标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID documentId; // 文档标识

    @Schema(description = "文档版本标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID versionId; // 文档版本标识

    @Schema(description = "文档内递增版本号", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int versionNo; // 文档内递增版本号

    @Schema(description = "是否为当前生效版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean active; // 是否为当前生效版本

    @Schema(description = "是否为当前待处理版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean pending; // 是否为当前待处理版本

    @Schema(description = "上传时的原始文件名", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String originalFilename; // 上传时的原始文件名

    @Schema(description = "已校验的媒体类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String mediaType; // 已校验的媒体类型

    @Schema(description = "通过完整性校验的 UTF-8 原文", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String content; // 通过完整性校验的 UTF-8 原文
}
