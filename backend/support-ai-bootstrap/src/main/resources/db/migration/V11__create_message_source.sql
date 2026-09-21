-- 消息来源表：在调用模型前登记实际进入上下文的文档证据，支持答案引用和来源追溯。
CREATE TABLE message_source (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL,
    origin_source_id UUID,
    citation_no INTEGER NOT NULL,
    is_cited BOOLEAN NOT NULL DEFAULT FALSE,
    source_type VARCHAR(16) NOT NULL,
    kb_id UUID NOT NULL,
    chunk_id UUID NOT NULL,
    locator JSONB NOT NULL,
    snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_message_source_message_citation UNIQUE (message_id, citation_no),
    CONSTRAINT ck_message_source_citation_no_positive CHECK (citation_no > 0),
    CONSTRAINT ck_message_source_type_document CHECK (source_type = 'DOCUMENT'),
    CONSTRAINT ck_message_source_locator_object CHECK (jsonb_typeof(locator) = 'object'),
    CONSTRAINT ck_message_source_snapshot_object CHECK (jsonb_typeof(snapshot) = 'object'),
    CONSTRAINT fk_message_source_message FOREIGN KEY (message_id) REFERENCES chat_message (id),
    CONSTRAINT fk_message_source_origin FOREIGN KEY (origin_source_id) REFERENCES message_source (id),
    CONSTRAINT fk_message_source_knowledge_base FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_message_source_document_chunk FOREIGN KEY (chunk_id) REFERENCES document_chunk (id)
);

CREATE INDEX idx_message_source_chunk_id ON message_source (chunk_id);
CREATE INDEX idx_message_source_kb_id ON message_source (kb_id);
CREATE INDEX idx_message_source_origin_source_id
    ON message_source (origin_source_id)
    WHERE origin_source_id IS NOT NULL;

COMMENT ON TABLE message_source IS '消息来源表，保存实际送入模型的文档证据及最终引用状态';
COMMENT ON COLUMN message_source.id IS '消息来源唯一标识，使用 UUID';
COMMENT ON COLUMN message_source.message_id IS '助手消息标识，引用 chat_message.id';
COMMENT ON COLUMN message_source.origin_source_id IS '继承来源的原始来源标识，直接检索来源为空';
COMMENT ON COLUMN message_source.citation_no IS '本轮答案中的正整数引用编号，例如 1 对应 [1]';
COMMENT ON COLUMN message_source.is_cited IS '最终答案是否显式引用该来源，未引用但进入模型的来源仍保留';
COMMENT ON COLUMN message_source.source_type IS '来源类型；F12 仅支持 DOCUMENT 文档来源';
COMMENT ON COLUMN message_source.kb_id IS '来源所属知识库标识，用于当前访问权限复核';
COMMENT ON COLUMN message_source.chunk_id IS '文档分块标识，引用 document_chunk.id';
COMMENT ON COLUMN message_source.locator IS '来源定位 JSON，保存章节、页码和字符偏移等信息';
COMMENT ON COLUMN message_source.snapshot IS '来源快照 JSON，保存文档与版本等生成时信息';
COMMENT ON COLUMN message_source.created_at IS '消息来源登记时间，带时区';
