-- document_chunk：保存文档版本内可定位分块的归属和内容摘要，分块正文由后续 vector_store 作为唯一副本。
CREATE TABLE document_chunk (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    section TEXT,
    page_no INTEGER,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    content_hash CHAR(64) NOT NULL,
    char_count INTEGER NOT NULL,
    token_count INTEGER,
    CONSTRAINT uq_document_chunk_version_index UNIQUE (version_id, chunk_index),
    CONSTRAINT ck_document_chunk_index_non_negative CHECK (chunk_index >= 0),
    CONSTRAINT ck_document_chunk_page_no_positive CHECK (page_no IS NULL OR page_no > 0),
    CONSTRAINT ck_document_chunk_start_offset_non_negative CHECK (start_offset >= 0),
    CONSTRAINT ck_document_chunk_end_offset_valid CHECK (end_offset > start_offset),
    CONSTRAINT ck_document_chunk_content_hash_format CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_document_chunk_char_count_positive CHECK (char_count > 0),
    CONSTRAINT ck_document_chunk_char_count_matches_offset CHECK (char_count = end_offset - start_offset),
    CONSTRAINT ck_document_chunk_token_count_positive CHECK (token_count IS NULL OR token_count > 0),
    CONSTRAINT fk_document_chunk_document_version FOREIGN KEY (version_id) REFERENCES document_version (id)
);

-- uq_document_chunk_version_index 自动创建的唯一索引按 version_id、chunk_index 支持顺序读取。

COMMENT ON TABLE document_chunk IS '文档分块表，保存版本内分块归属、可定位位置和内容摘要，正文由 vector_store 保存';
COMMENT ON COLUMN document_chunk.id IS '稳定分块唯一标识，后续同时作为 vector_store.id';
COMMENT ON COLUMN document_chunk.version_id IS '所属文档版本标识，引用 document_version.id';
COMMENT ON COLUMN document_chunk.chunk_index IS '文档版本内分块序号，从 0 开始';
COMMENT ON COLUMN document_chunk.section IS '分块所属章节层级，可为空';
COMMENT ON COLUMN document_chunk.page_no IS '原始页码，预留 PDF 扩展使用，从 1 开始';
COMMENT ON COLUMN document_chunk.start_offset IS '规范化文本起始字符偏移量，左闭区间';
COMMENT ON COLUMN document_chunk.end_offset IS '规范化文本结束字符偏移量，右开区间';
COMMENT ON COLUMN document_chunk.content_hash IS '当前分块正文的 SHA-256 小写十六进制摘要';
COMMENT ON COLUMN document_chunk.char_count IS '当前分块正文的字符数';
COMMENT ON COLUMN document_chunk.token_count IS '当前分块正文的 token 估计值，可为空';
