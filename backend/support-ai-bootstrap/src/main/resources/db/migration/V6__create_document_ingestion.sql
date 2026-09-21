-- 文档接入及入库任务表：保存业务文档、版本化原文件和后续异步处理任务。
CREATE TABLE document (
    id UUID PRIMARY KEY,
    kb_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    active_version_id UUID,
    pending_version_id UUID,
    next_version_no INTEGER NOT NULL DEFAULT 1,
    created_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_document_name_not_blank CHECK (LENGTH(BTRIM(name)) > 0),
    CONSTRAINT ck_document_next_version_no_positive CHECK (next_version_no > 0),
    CONSTRAINT ck_document_revision_non_negative CHECK (revision >= 0),
    CONSTRAINT ck_document_version_pointers_different CHECK (
        active_version_id IS NULL
        OR pending_version_id IS NULL
        OR active_version_id <> pending_version_id
    ),
    CONSTRAINT fk_document_knowledge_base FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_document_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE document_version (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    file_hash CHAR(64) NOT NULL,
    file_size BIGINT NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    chunker_version VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    dimensions INTEGER NOT NULL,
    config_hash CHAR(64) NOT NULL,
    config_snapshot JSONB NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_document_version_document_version_no UNIQUE (document_id, version_no),
    CONSTRAINT uq_document_version_document_id_id UNIQUE (document_id, id),
    CONSTRAINT ck_document_version_no_positive CHECK (version_no > 0),
    CONSTRAINT ck_document_version_storage_key_not_blank CHECK (LENGTH(BTRIM(storage_key)) > 0),
    CONSTRAINT ck_document_version_file_hash_format CHECK (file_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_document_version_file_size_non_negative CHECK (file_size >= 0),
    CONSTRAINT ck_document_version_media_type_not_blank CHECK (LENGTH(BTRIM(media_type)) > 0),
    CONSTRAINT ck_document_version_original_filename_not_blank CHECK (LENGTH(BTRIM(original_filename)) > 0),
    CONSTRAINT ck_document_version_status CHECK (status IN ('QUEUED', 'BUILDING', 'READY', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_document_version_parser_version_not_blank CHECK (LENGTH(BTRIM(parser_version)) > 0),
    CONSTRAINT ck_document_version_chunker_version_not_blank CHECK (LENGTH(BTRIM(chunker_version)) > 0),
    CONSTRAINT ck_document_version_embedding_model_not_blank CHECK (LENGTH(BTRIM(embedding_model)) > 0),
    CONSTRAINT ck_document_version_dimensions_1024 CHECK (dimensions = 1024),
    CONSTRAINT ck_document_version_config_hash_format CHECK (config_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_document_version_chunk_count_non_negative CHECK (chunk_count >= 0),
    CONSTRAINT fk_document_version_document FOREIGN KEY (document_id) REFERENCES document (id)
);

ALTER TABLE document
    ADD CONSTRAINT fk_document_active_version
        FOREIGN KEY (id, active_version_id) REFERENCES document_version (document_id, id),
    ADD CONSTRAINT fk_document_pending_version
        FOREIGN KEY (id, pending_version_id) REFERENCES document_version (document_id, id);

CREATE TABLE ingestion_job (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    stage VARCHAR(16) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    lease_owner VARCHAR(100),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_chunks INTEGER,
    total_chunks INTEGER,
    error_code VARCHAR(64),
    error_detail TEXT,
    cancel_requested_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ingestion_job_version_id UNIQUE (version_id),
    CONSTRAINT ck_ingestion_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_ingestion_job_stage CHECK (stage IN ('PARSE', 'CHUNK', 'EMBED', 'PUBLISH')),
    CONSTRAINT ck_ingestion_job_attempt_non_negative CHECK (attempt >= 0),
    CONSTRAINT ck_ingestion_job_max_attempts_positive CHECK (max_attempts > 0),
    CONSTRAINT ck_ingestion_job_attempt_within_limit CHECK (attempt <= max_attempts),
    CONSTRAINT ck_ingestion_job_completed_chunks_non_negative CHECK (completed_chunks IS NULL OR completed_chunks >= 0),
    CONSTRAINT ck_ingestion_job_total_chunks_non_negative CHECK (total_chunks IS NULL OR total_chunks >= 0),
    CONSTRAINT ck_ingestion_job_completed_chunks_within_total CHECK (
        completed_chunks IS NULL
        OR total_chunks IS NULL
        OR completed_chunks <= total_chunks
    ),
    CONSTRAINT fk_ingestion_job_document_version FOREIGN KEY (version_id) REFERENCES document_version (id)
);

CREATE INDEX idx_document_kb_id_updated_at ON document (kb_id, updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_document_version_status_created_at ON document_version (status, created_at);
CREATE INDEX idx_ingestion_job_status_next_run_at ON ingestion_job (status, next_run_at);
CREATE INDEX idx_ingestion_job_status_lease_until ON ingestion_job (status, lease_until);

COMMENT ON TABLE document IS '文档逻辑表，保存知识库内文档元数据和当前活动、待处理版本指针';
COMMENT ON COLUMN document.id IS '文档唯一标识，使用 UUID';
COMMENT ON COLUMN document.kb_id IS '所属知识库标识，引用 knowledge_base.id';
COMMENT ON COLUMN document.name IS '文档展示名称，不作为物理存储路径';
COMMENT ON COLUMN document.active_version_id IS '当前可检索的已发布版本标识，与文档标识复合约束防止串文档引用';
COMMENT ON COLUMN document.pending_version_id IS '当前待处理版本标识，与文档标识复合约束防止串文档引用';
COMMENT ON COLUMN document.next_version_no IS '下次分配的正整数版本号';
COMMENT ON COLUMN document.created_by IS '创建文档的用户标识，引用 app_user.id';
COMMENT ON COLUMN document.deleted_at IS '逻辑删除时间，非空表示文档不可见';
COMMENT ON COLUMN document.revision IS '文档元数据修订号，用于后续乐观并发控制';
COMMENT ON COLUMN document.created_at IS '文档创建时间，带时区';
COMMENT ON COLUMN document.updated_at IS '文档最近更新时间，带时区';

COMMENT ON TABLE document_version IS '文档版本表，保存原文件受控存储信息、处理配置快照和版本状态';
COMMENT ON COLUMN document_version.id IS '文档版本唯一标识，使用 UUID';
COMMENT ON COLUMN document_version.document_id IS '所属文档标识，引用 document.id';
COMMENT ON COLUMN document_version.version_no IS '文档内递增版本号，从 1 开始';
COMMENT ON COLUMN document_version.storage_key IS '受控原文件内部存储键，不向客户端暴露';
COMMENT ON COLUMN document_version.file_hash IS '原文件 SHA-256 小写十六进制摘要';
COMMENT ON COLUMN document_version.file_size IS '原文件字节数';
COMMENT ON COLUMN document_version.media_type IS '已校验的文件媒体类型';
COMMENT ON COLUMN document_version.original_filename IS '清理路径后的原始文件名，仅用于展示和审计';
COMMENT ON COLUMN document_version.status IS '版本状态：QUEUED、BUILDING、READY、FAILED、CANCELLED';
COMMENT ON COLUMN document_version.parser_version IS '解析器版本，用于处理可追溯和重建';
COMMENT ON COLUMN document_version.chunker_version IS '分块器版本，用于处理可追溯和重建';
COMMENT ON COLUMN document_version.embedding_model IS '嵌入模型名称，更换模型需重建索引';
COMMENT ON COLUMN document_version.dimensions IS '嵌入向量维度，当前固定为 1024';
COMMENT ON COLUMN document_version.config_hash IS '处理配置快照 SHA-256 小写十六进制摘要';
COMMENT ON COLUMN document_version.config_snapshot IS '不含密钥的处理配置 JSON 快照';
COMMENT ON COLUMN document_version.chunk_count IS '已生成的文本分块数量';
COMMENT ON COLUMN document_version.created_at IS '文档版本创建时间，带时区';
COMMENT ON COLUMN document_version.updated_at IS '文档版本最近更新时间，带时区';

COMMENT ON TABLE ingestion_job IS '文档入库任务表，保存解析、分块、嵌入和发布的异步执行状态';
COMMENT ON COLUMN ingestion_job.id IS '入库任务唯一标识，使用 UUID';
COMMENT ON COLUMN ingestion_job.version_id IS '待处理文档版本标识，单版本只允许一个入库任务';
COMMENT ON COLUMN ingestion_job.status IS '任务状态：QUEUED、RUNNING、RETRY_WAIT、SUCCEEDED、FAILED、CANCELLED';
COMMENT ON COLUMN ingestion_job.stage IS '当前处理阶段：PARSE、CHUNK、EMBED、PUBLISH';
COMMENT ON COLUMN ingestion_job.attempt IS '已发起处理的次数';
COMMENT ON COLUMN ingestion_job.max_attempts IS '允许的最大处理次数';
COMMENT ON COLUMN ingestion_job.lease_owner IS '当前任务租约持有者标识';
COMMENT ON COLUMN ingestion_job.lease_token IS '当前任务租约令牌，用于防止失效工作者回写';
COMMENT ON COLUMN ingestion_job.lease_until IS '当前任务租约失效时间，带时区';
COMMENT ON COLUMN ingestion_job.heartbeat_at IS '当前任务工作者最近心跳时间，带时区';
COMMENT ON COLUMN ingestion_job.next_run_at IS '任务下次可领取执行时间，带时区';
COMMENT ON COLUMN ingestion_job.completed_chunks IS '已完成嵌入或发布的文本分块数';
COMMENT ON COLUMN ingestion_job.total_chunks IS '任务预计处理的文本分块总数';
COMMENT ON COLUMN ingestion_job.error_code IS '最近一次失败的脱敏错误码';
COMMENT ON COLUMN ingestion_job.error_detail IS '最近一次失败的脱敏错误详情';
COMMENT ON COLUMN ingestion_job.cancel_requested_at IS '取消请求接收时间，带时区';
COMMENT ON COLUMN ingestion_job.started_at IS '首次开始执行时间，带时区';
COMMENT ON COLUMN ingestion_job.finished_at IS '最终结束时间，带时区';
COMMENT ON COLUMN ingestion_job.created_at IS '任务创建时间，带时区';
COMMENT ON COLUMN ingestion_job.updated_at IS '任务最近更新时间，带时区';
