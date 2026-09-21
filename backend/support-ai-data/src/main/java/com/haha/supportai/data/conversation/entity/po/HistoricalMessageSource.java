package com.haha.supportai.data.conversation.entity.po;

import java.util.UUID;

import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalMessageSource {

    private UUID sourceId; // 历史消息来源标识

    private UUID rootSourceId; // 来源继承链的直接根来源标识

    private int citationNo; // 历史回答中的引用编号

    private MessageSourceTypeEnum sourceType; // 来源类型

    private UUID knowledgeBaseId; // 来源所属知识库标识

    private UUID chunkId; // 来源文档分块标识

    private String locator; // 来源定位信息 JSON

    private String snapshot; // 来源生成时快照 JSON

    private boolean lineageValid; // 来源是否直接指向同会话的原始根来源
}
