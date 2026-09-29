-- 失败诊断字段：将入口请求追踪标识关联到异步入库任务和问答运行。
ALTER TABLE ingestion_job
    ADD COLUMN trace_id VARCHAR(64),
    ADD CONSTRAINT ck_ingestion_job_trace_id_not_blank
        CHECK (trace_id IS NULL OR LENGTH(BTRIM(trace_id)) > 0);

ALTER TABLE chat_run
    ADD COLUMN trace_id VARCHAR(64),
    ADD CONSTRAINT ck_chat_run_trace_id_not_blank
        CHECK (trace_id IS NULL OR LENGTH(BTRIM(trace_id)) > 0);

CREATE INDEX idx_ingestion_job_trace_id
    ON ingestion_job (trace_id)
    WHERE trace_id IS NOT NULL;

CREATE INDEX idx_chat_run_trace_id
    ON chat_run (trace_id)
    WHERE trace_id IS NOT NULL;

COMMENT ON COLUMN ingestion_job.trace_id IS '创建入库任务的请求追踪标识，用于关联异步处理日志';
COMMENT ON COLUMN chat_run.trace_id IS '创建问答运行的请求追踪标识，用于关联流式执行日志';
