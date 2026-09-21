-- 会话与运行表：保存固定知识库范围、幂等请求以及后续 RAG 流式问答所需的消息状态。
CREATE TABLE chat_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    next_message_seq BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_chat_session_title_not_blank CHECK (LENGTH(BTRIM(title)) > 0),
    CONSTRAINT ck_chat_session_mode CHECK (mode IN ('RAG', 'ASSISTANT')),
    CONSTRAINT ck_chat_session_next_message_seq_positive CHECK (next_message_seq > 0),
    CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

CREATE TABLE chat_session_kb (
    session_id UUID NOT NULL,
    kb_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_chat_session_kb PRIMARY KEY (session_id, kb_id),
    CONSTRAINT fk_chat_session_kb_session FOREIGN KEY (session_id) REFERENCES chat_session (id),
    CONSTRAINT fk_chat_session_kb_knowledge_base FOREIGN KEY (kb_id) REFERENCES knowledge_base (id)
);

CREATE TABLE chat_run (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    config_snapshot JSONB NOT NULL,
    usage JSONB,
    metrics JSONB NOT NULL DEFAULT '{}'::JSONB,
    error_code VARCHAR(64),
    error_detail TEXT,
    cancel_requested_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_chat_run_session_client_request UNIQUE (session_id, client_request_id),
    CONSTRAINT uq_chat_run_session_id UNIQUE (session_id, id),
    CONSTRAINT ck_chat_run_request_hash_format CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chat_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_chat_run_config_snapshot_object CHECK (jsonb_typeof(config_snapshot) = 'object'),
    CONSTRAINT ck_chat_run_usage_object CHECK (usage IS NULL OR jsonb_typeof(usage) = 'object'),
    CONSTRAINT ck_chat_run_metrics_object CHECK (jsonb_typeof(metrics) = 'object'),
    CONSTRAINT ck_chat_run_terminal_finished_at CHECK (
        (status IN ('QUEUED', 'RUNNING') AND finished_at IS NULL)
        OR (status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND finished_at IS NOT NULL)
    ),
    CONSTRAINT fk_chat_run_session FOREIGN KEY (session_id) REFERENCES chat_session (id)
);

CREATE TABLE chat_message (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    session_id UUID NOT NULL,
    seq BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_chat_message_session_seq UNIQUE (session_id, seq),
    CONSTRAINT uq_chat_message_run_role UNIQUE (run_id, role),
    CONSTRAINT ck_chat_message_seq_positive CHECK (seq > 0),
    CONSTRAINT ck_chat_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_chat_message_status CHECK (status IN ('STREAMING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_chat_message_role_status CHECK (
        (role = 'USER' AND status = 'COMPLETED' AND LENGTH(BTRIM(content)) > 0)
        OR (role = 'ASSISTANT' AND status IN ('STREAMING', 'COMPLETED', 'FAILED', 'CANCELLED'))
    ),
    CONSTRAINT fk_chat_message_session FOREIGN KEY (session_id) REFERENCES chat_session (id),
    CONSTRAINT fk_chat_message_run_session FOREIGN KEY (session_id, run_id)
        REFERENCES chat_run (session_id, id)
);

CREATE UNIQUE INDEX uq_chat_run_active_session
    ON chat_run (session_id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_chat_session_user_updated_at
    ON chat_session (user_id, updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_chat_session_kb_kb_session ON chat_session_kb (kb_id, session_id);
CREATE INDEX idx_chat_run_session_created_at ON chat_run (session_id, created_at DESC);
CREATE INDEX idx_chat_message_run_id ON chat_message (run_id);

COMMENT ON TABLE chat_session IS '会话表，保存用户创建的 RAG 会话及其消息序号分配状态';
COMMENT ON COLUMN chat_session.id IS '会话唯一标识，使用 UUID';
COMMENT ON COLUMN chat_session.user_id IS '会话所属用户标识，引用 app_user.id';
COMMENT ON COLUMN chat_session.title IS '会话展示标题，初始值由应用层提供';
COMMENT ON COLUMN chat_session.mode IS '会话模式：RAG 或 ASSISTANT；F10 仅允许创建 RAG';
COMMENT ON COLUMN chat_session.next_message_seq IS '下一个可分配的正消息序号，每次问答预留两个序号';
COMMENT ON COLUMN chat_session.deleted_at IS '会话逻辑删除时间，非空表示不再可访问';
COMMENT ON COLUMN chat_session.created_at IS '会话创建时间，带时区';
COMMENT ON COLUMN chat_session.updated_at IS '会话最近更新时间，带时区';

COMMENT ON TABLE chat_session_kb IS '会话知识库范围表，创建后固定会话允许检索的知识库集合';
COMMENT ON COLUMN chat_session_kb.session_id IS '会话标识，引用 chat_session.id';
COMMENT ON COLUMN chat_session_kb.kb_id IS '固定知识库标识，引用 knowledge_base.id';
COMMENT ON COLUMN chat_session_kb.created_at IS '会话知识库范围创建时间，带时区';

COMMENT ON TABLE chat_run IS '问答运行表，保存幂等键、请求摘要、配置快照和执行状态';
COMMENT ON COLUMN chat_run.id IS '运行唯一标识，使用 UUID';
COMMENT ON COLUMN chat_run.session_id IS '所属会话标识，引用 chat_session.id';
COMMENT ON COLUMN chat_run.client_request_id IS '客户端幂等请求 UUID，会话内唯一';
COMMENT ON COLUMN chat_run.request_hash IS '规范化请求内容的 SHA-256 小写十六进制摘要';
COMMENT ON COLUMN chat_run.status IS '运行状态：QUEUED、RUNNING、COMPLETED、FAILED、CANCELLED';
COMMENT ON COLUMN chat_run.config_snapshot IS '不含密钥的检索和模型配置 JSON 快照';
COMMENT ON COLUMN chat_run.usage IS '模型用量 JSON，未执行时为空';
COMMENT ON COLUMN chat_run.metrics IS '检索与生成指标 JSON，不含敏感内容';
COMMENT ON COLUMN chat_run.error_code IS '运行失败的稳定错误码';
COMMENT ON COLUMN chat_run.error_detail IS '脱敏后的运行失败详情';
COMMENT ON COLUMN chat_run.cancel_requested_at IS '取消请求接收时间，带时区';
COMMENT ON COLUMN chat_run.started_at IS '运行开始时间，带时区';
COMMENT ON COLUMN chat_run.finished_at IS '运行终态完成时间，带时区';
COMMENT ON COLUMN chat_run.created_at IS '运行创建时间，带时区';
COMMENT ON COLUMN chat_run.updated_at IS '运行最近更新时间，带时区';

COMMENT ON TABLE chat_message IS '会话消息表，保存每次运行的一条用户消息和一条助手消息';
COMMENT ON COLUMN chat_message.id IS '消息唯一标识，使用 UUID';
COMMENT ON COLUMN chat_message.run_id IS '所属运行标识，与会话标识复合引用 chat_run';
COMMENT ON COLUMN chat_message.session_id IS '所属会话标识，引用 chat_session.id';
COMMENT ON COLUMN chat_message.seq IS '会话内递增消息序号，用于稳定排序';
COMMENT ON COLUMN chat_message.role IS '消息角色：USER 或 ASSISTANT';
COMMENT ON COLUMN chat_message.content IS '消息正文，助手流式占位消息允许为空字符串';
COMMENT ON COLUMN chat_message.status IS '消息状态：STREAMING、COMPLETED、FAILED、CANCELLED';
COMMENT ON COLUMN chat_message.created_at IS '消息创建时间，带时区';
COMMENT ON COLUMN chat_message.updated_at IS '消息最近更新时间，带时区';
