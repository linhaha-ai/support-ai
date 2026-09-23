-- 模拟工单表：保存可由网页和后续只读工具实时查询的业务状态。
CREATE TABLE ticket (
    id UUID PRIMARY KEY,
    ticket_no VARCHAR(32) NOT NULL,
    kb_id UUID NOT NULL,
    title VARCHAR(200),
    description TEXT,
    status VARCHAR(24) NOT NULL,
    owner_name VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ticket_ticket_no UNIQUE (ticket_no),
    CONSTRAINT ck_ticket_no_not_blank CHECK (LENGTH(BTRIM(ticket_no)) > 0),
    CONSTRAINT ck_ticket_title_not_blank CHECK (title IS NULL OR LENGTH(BTRIM(title)) > 0),
    CONSTRAINT ck_ticket_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_ticket_owner_name_not_blank CHECK (owner_name IS NULL OR LENGTH(BTRIM(owner_name)) > 0),
    CONSTRAINT fk_ticket_knowledge_base FOREIGN KEY (kb_id) REFERENCES knowledge_base (id)
);

CREATE INDEX idx_ticket_kb_status_updated_at ON ticket (kb_id, status, updated_at DESC);

COMMENT ON TABLE ticket IS '模拟工单表，保存只读业务查询所需的当前状态和展示信息';
COMMENT ON COLUMN ticket.id IS '工单内部唯一标识，使用 UUID';
COMMENT ON COLUMN ticket.ticket_no IS '外部业务工单编号，全局唯一';
COMMENT ON COLUMN ticket.kb_id IS '工单所属知识库标识，当前成员权限决定可读范围';
COMMENT ON COLUMN ticket.title IS '工单标题，可为空';
COMMENT ON COLUMN ticket.description IS '工单问题说明，可为空';
COMMENT ON COLUMN ticket.status IS '工单当前状态：OPEN、IN_PROGRESS、RESOLVED、CLOSED';
COMMENT ON COLUMN ticket.owner_name IS '当前模拟处理人名称，可为空';
COMMENT ON COLUMN ticket.created_at IS '工单创建时间，带时区';
COMMENT ON COLUMN ticket.updated_at IS '工单最近状态更新时间，带时区';
