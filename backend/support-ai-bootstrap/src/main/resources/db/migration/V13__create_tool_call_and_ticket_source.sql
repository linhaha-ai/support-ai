-- 工具调用表：记录受控只读工具的参数、结果快照、状态和耗时依据。
CREATE TABLE tool_call (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    call_no INTEGER NOT NULL,
    provider_call_id VARCHAR(200),
    tool_name VARCHAR(100) NOT NULL,
    arguments JSONB NOT NULL,
    result_snapshot JSONB,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CONSTRAINT uq_tool_call_run_call_no UNIQUE (run_id, call_no),
    CONSTRAINT ck_tool_call_no_positive CHECK (call_no > 0),
    CONSTRAINT ck_tool_call_provider_call_id_not_blank CHECK (
        provider_call_id IS NULL OR LENGTH(BTRIM(provider_call_id)) > 0
    ),
    CONSTRAINT ck_tool_call_name CHECK (tool_name IN ('searchKnowledge', 'getTicketStatus')),
    CONSTRAINT ck_tool_call_arguments_object CHECK (jsonb_typeof(arguments) = 'object'),
    CONSTRAINT ck_tool_call_result_object CHECK (
        result_snapshot IS NULL OR jsonb_typeof(result_snapshot) = 'object'
    ),
    CONSTRAINT ck_tool_call_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_tool_call_error_code_not_blank CHECK (
        error_code IS NULL OR LENGTH(BTRIM(error_code)) > 0
    ),
    CONSTRAINT ck_tool_call_status_fields CHECK (
        (status = 'RUNNING' AND result_snapshot IS NULL AND error_code IS NULL AND finished_at IS NULL)
        OR (status = 'SUCCEEDED' AND result_snapshot IS NOT NULL AND error_code IS NULL AND finished_at IS NOT NULL)
        OR (status IN ('FAILED', 'CANCELLED') AND result_snapshot IS NULL
            AND error_code IS NOT NULL AND finished_at IS NOT NULL)
    ),
    CONSTRAINT fk_tool_call_run FOREIGN KEY (run_id) REFERENCES chat_run (id)
);

CREATE INDEX idx_tool_call_run_id ON tool_call (run_id);

COMMENT ON TABLE tool_call IS '受控工具调用审计表，保存每次只读工具执行的参数、受限结果和终态';
COMMENT ON COLUMN tool_call.id IS '工具调用唯一标识，使用 UUID';
COMMENT ON COLUMN tool_call.run_id IS '所属问答运行标识，引用 chat_run.id';
COMMENT ON COLUMN tool_call.call_no IS '同一运行内由服务端分配的正整数调用序号';
COMMENT ON COLUMN tool_call.provider_call_id IS '模型或框架返回的调用标识，可为空且不作为授权依据';
COMMENT ON COLUMN tool_call.tool_name IS '服务端白名单工具名：searchKnowledge 或 getTicketStatus';
COMMENT ON COLUMN tool_call.arguments IS '经过边界处理的工具参数 JSON，不包含用户身份或自由执行配置';
COMMENT ON COLUMN tool_call.result_snapshot IS '成功工具结果的受限 JSON 快照，失败或执行中为空';
COMMENT ON COLUMN tool_call.status IS '工具调用状态：RUNNING、SUCCEEDED、FAILED、CANCELLED';
COMMENT ON COLUMN tool_call.error_code IS '失败或取消时的稳定错误码，不保存底层异常详情';
COMMENT ON COLUMN tool_call.started_at IS '工具调用开始时间，带时区';
COMMENT ON COLUMN tool_call.finished_at IS '工具调用终态完成时间，带时区';

-- message_source：扩展工单与工具调用来源，同时保留既有文档来源数据。
ALTER TABLE message_source DROP CONSTRAINT ck_message_source_type_document;
ALTER TABLE message_source ALTER COLUMN chunk_id DROP NOT NULL;
ALTER TABLE message_source ADD COLUMN ticket_id UUID;
ALTER TABLE message_source ADD COLUMN tool_call_id UUID;
ALTER TABLE message_source ADD CONSTRAINT ck_message_source_type_fields CHECK (
    (source_type = 'DOCUMENT' AND chunk_id IS NOT NULL AND ticket_id IS NULL)
    OR (source_type = 'TICKET' AND chunk_id IS NULL AND ticket_id IS NOT NULL AND tool_call_id IS NOT NULL)
);
ALTER TABLE message_source ADD CONSTRAINT fk_message_source_ticket
    FOREIGN KEY (ticket_id) REFERENCES ticket (id);
ALTER TABLE message_source ADD CONSTRAINT fk_message_source_tool_call
    FOREIGN KEY (tool_call_id) REFERENCES tool_call (id);

CREATE INDEX idx_message_source_ticket_id ON message_source (ticket_id) WHERE ticket_id IS NOT NULL;
CREATE INDEX idx_message_source_tool_call_id ON message_source (tool_call_id) WHERE tool_call_id IS NOT NULL;

COMMENT ON TABLE message_source IS '消息来源表，保存实际进入模型的文档证据、工单结果及最终引用状态';
COMMENT ON COLUMN message_source.source_type IS '来源类型：DOCUMENT 文档证据或 TICKET 工单结果';
COMMENT ON COLUMN message_source.chunk_id IS '文档来源的分块标识；工单来源为空';
COMMENT ON COLUMN message_source.ticket_id IS '工单来源的内部工单标识；文档来源为空';
COMMENT ON COLUMN message_source.tool_call_id IS '直接工具来源对应的实际工具调用标识；普通 RAG 文档来源为空';
