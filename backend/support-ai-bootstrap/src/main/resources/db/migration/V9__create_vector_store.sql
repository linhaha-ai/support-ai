-- vector_store：保存可检索分块正文、元数据和固定 1024 维嵌入向量。
CREATE TABLE vector_store (
    id UUID PRIMARY KEY,
    content TEXT NOT NULL,
    metadata JSON NOT NULL,
    embedding vector(1024) NOT NULL,
    CONSTRAINT ck_vector_store_content_not_blank CHECK (LENGTH(BTRIM(content)) > 0),
    CONSTRAINT fk_vector_store_document_chunk
        FOREIGN KEY (id) REFERENCES document_chunk (id) ON DELETE CASCADE
);

COMMENT ON TABLE vector_store IS '向量存储表，保存文档分块正文、检索元数据和 1024 维嵌入向量';
COMMENT ON COLUMN vector_store.id IS '分块唯一标识，同时引用 document_chunk.id 并级联删除';
COMMENT ON COLUMN vector_store.content IS '用于检索和上下文拼接的分块正文';
COMMENT ON COLUMN vector_store.metadata IS '分块检索元数据 JSON，保存版本、位置和摘要等可追溯信息';
COMMENT ON COLUMN vector_store.embedding IS '固定 1024 维嵌入向量，使用 pgvector vector 类型';
