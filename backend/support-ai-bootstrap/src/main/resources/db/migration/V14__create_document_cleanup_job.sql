-- 文档清理任务表：文档逻辑删除提交后可靠回收受控原文件和向量正文。
CREATE TABLE document_cleanup_job (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    lease_owner VARCHAR(100),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_code VARCHAR(64),
    error_detail VARCHAR(500),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_document_cleanup_job_document_id UNIQUE (document_id),
    CONSTRAINT ck_document_cleanup_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED')),
    CONSTRAINT ck_document_cleanup_job_attempt_non_negative CHECK (attempt >= 0),
    CONSTRAINT ck_document_cleanup_job_lease_state CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_document_cleanup_job_finished_state CHECK (
        (status = 'SUCCEEDED' AND finished_at IS NOT NULL)
        OR (status <> 'SUCCEEDED' AND finished_at IS NULL)
    ),
    CONSTRAINT fk_document_cleanup_job_document FOREIGN KEY (document_id) REFERENCES document (id)
);

CREATE INDEX idx_document_cleanup_job_status_next_run_at
    ON document_cleanup_job (status, next_run_at);
CREATE INDEX idx_document_cleanup_job_running_lease_until
    ON document_cleanup_job (lease_until)
    WHERE status = 'RUNNING';

COMMENT ON TABLE document_cleanup_job IS '已删除文档的受控文件和向量清理任务，独立于导入任务重试流程';
COMMENT ON COLUMN document_cleanup_job.id IS '清理任务唯一标识，使用 UUID';
COMMENT ON COLUMN document_cleanup_job.document_id IS '待清理文档标识，同一文档只允许一条清理任务';
COMMENT ON COLUMN document_cleanup_job.status IS '任务状态：PENDING、RUNNING、RETRY_WAIT、SUCCEEDED';
COMMENT ON COLUMN document_cleanup_job.attempt IS '累计领取次数，用于清理失败诊断';
COMMENT ON COLUMN document_cleanup_job.lease_owner IS '当前清理执行者标识，非运行状态为空';
COMMENT ON COLUMN document_cleanup_job.lease_token IS '当前清理租约令牌，用于拒绝失效执行者回写';
COMMENT ON COLUMN document_cleanup_job.lease_until IS '当前清理租约失效时间，带时区';
COMMENT ON COLUMN document_cleanup_job.next_run_at IS '下一次允许领取时间，带时区';
COMMENT ON COLUMN document_cleanup_job.error_code IS '最近一次清理失败的稳定错误码';
COMMENT ON COLUMN document_cleanup_job.error_detail IS '最近一次清理失败的脱敏详情，不保存文件路径或异常原文';
COMMENT ON COLUMN document_cleanup_job.started_at IS '首次开始清理时间，带时区';
COMMENT ON COLUMN document_cleanup_job.finished_at IS '成功完成清理时间，带时区';
COMMENT ON COLUMN document_cleanup_job.created_at IS '清理任务创建时间，带时区';
COMMENT ON COLUMN document_cleanup_job.updated_at IS '清理任务最近更新时间，带时区';
