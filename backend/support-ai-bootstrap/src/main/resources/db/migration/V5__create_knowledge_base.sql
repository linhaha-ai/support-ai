-- 知识库及成员授权表：保存知识空间元数据和用户在知识空间内的读写权限。
CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    owner_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_knowledge_base_name_not_blank CHECK (LENGTH(BTRIM(name)) > 0),
    CONSTRAINT ck_knowledge_base_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_knowledge_base_revision_non_negative CHECK (revision >= 0),
    CONSTRAINT fk_knowledge_base_owner FOREIGN KEY (owner_id) REFERENCES app_user (id)
);

CREATE TABLE kb_member (
    kb_id UUID NOT NULL,
    user_id UUID NOT NULL,
    permission VARCHAR(16) NOT NULL,
    granted_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_kb_member PRIMARY KEY (kb_id, user_id),
    CONSTRAINT ck_kb_member_permission CHECK (permission IN ('READ', 'WRITE')),
    CONSTRAINT fk_kb_member_knowledge_base FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_kb_member_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_kb_member_granted_by FOREIGN KEY (granted_by) REFERENCES app_user (id)
);

CREATE INDEX idx_knowledge_base_status_updated_at ON knowledge_base (status, updated_at DESC);
CREATE INDEX idx_knowledge_base_owner_id ON knowledge_base (owner_id);
CREATE INDEX idx_kb_member_user_id_kb_id ON kb_member (user_id, kb_id);

COMMENT ON TABLE knowledge_base IS '知识库表，保存知识空间元数据、负责人、状态和并发修订号';
COMMENT ON COLUMN knowledge_base.id IS '知识库唯一标识，使用 UUID';
COMMENT ON COLUMN knowledge_base.name IS '知识库名称，去除首尾空白后不能为空';
COMMENT ON COLUMN knowledge_base.description IS '知识库说明，可为空';
COMMENT ON COLUMN knowledge_base.owner_id IS '知识库负责人用户标识，引用 app_user.id';
COMMENT ON COLUMN knowledge_base.status IS '知识库状态：ACTIVE 启用，DISABLED 停用';
COMMENT ON COLUMN knowledge_base.revision IS '元数据修订号，用于乐观并发控制';
COMMENT ON COLUMN knowledge_base.created_at IS '知识库创建时间，带时区';
COMMENT ON COLUMN knowledge_base.updated_at IS '知识库最近更新时间，带时区';

COMMENT ON TABLE kb_member IS '知识库成员授权表，保存用户在知识库内的读写权限';
COMMENT ON COLUMN kb_member.kb_id IS '知识库标识，引用 knowledge_base.id';
COMMENT ON COLUMN kb_member.user_id IS '被授权用户标识，引用 app_user.id';
COMMENT ON COLUMN kb_member.permission IS '成员权限：READ 可读取，WRITE 可维护资料';
COMMENT ON COLUMN kb_member.granted_by IS '授权操作人用户标识，引用 app_user.id';
COMMENT ON COLUMN kb_member.created_at IS '成员授权创建时间，带时区';
