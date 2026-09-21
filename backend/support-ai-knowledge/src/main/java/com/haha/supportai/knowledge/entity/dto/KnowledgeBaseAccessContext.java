package com.haha.supportai.knowledge.entity.dto;

import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeBase;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class KnowledgeBaseAccessContext {

    private final KnowledgeBase knowledgeBase; // 已通过授权校验的知识库

    private final KbMember member; // 当前用户的成员关系
}
