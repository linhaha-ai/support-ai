-- ticket：F25 模拟工单种子脚本，须先完成 Flyway V13 和 identity/init_app_user.sql。
-- 脚本创建独立演示知识库，为 support1/support2 建立成员关系，并幂等补齐 30 条工单。
-- admin 仅作为知识库负责人，不自动获得成员权限，用于验证管理员身份不能绕过资料授权。
BEGIN;

INSERT INTO knowledge_base (
    id,          -- 知识库标识，使用稳定 UUID 便于重复执行
    name,        -- 知识库名称
    description, -- 知识库说明
    owner_id,    -- 负责人账号标识
    status,      -- 知识库状态
    revision,    -- 元数据修订号
    created_at,  -- 创建时间
    updated_at   -- 最近更新时间
)
SELECT
    '25000000-0000-4000-8000-000000000001'::UUID,
    '模拟订单系统工单',
    'F25 只读工单查询演示数据',
    app_user.id,
    'ACTIVE',
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM app_user
WHERE app_user.username = 'admin'
  AND app_user.enabled = TRUE
ON CONFLICT (id) DO NOTHING;

INSERT INTO kb_member (
    kb_id,      -- 演示知识库标识
    user_id,    -- 被授权账号标识
    permission, -- 成员权限
    granted_by, -- 授权管理员标识
    created_at  -- 首次授权时间
)
SELECT
    knowledge_base.id,
    support_user.id,
    CASE WHEN support_user.username = 'support1' THEN 'WRITE' ELSE 'READ' END,
    administrator.id,
    CURRENT_TIMESTAMP
FROM knowledge_base
INNER JOIN app_user administrator ON administrator.username = 'admin' AND administrator.enabled = TRUE
INNER JOIN app_user support_user ON support_user.username IN ('support1', 'support2')
        AND support_user.enabled = TRUE
WHERE knowledge_base.id = '25000000-0000-4000-8000-000000000001'::UUID
ON CONFLICT (kb_id, user_id) DO NOTHING;

WITH ticket_seed AS (
    SELECT
        sequence_no,
        'T' || (10085 + sequence_no)::TEXT AS ticket_no,
        CASE sequence_no % 5
            WHEN 0 THEN '支付回调状态异常'
            WHEN 1 THEN '订单关闭结果未同步'
            WHEN 2 THEN '退款处理时间过长'
            WHEN 3 THEN '渠道对账金额不一致'
            ELSE '商户签名校验失败'
        END AS title,
        CASE sequence_no % 4
            WHEN 0 THEN 'OPEN'
            WHEN 1 THEN 'IN_PROGRESS'
            WHEN 2 THEN 'RESOLVED'
            ELSE 'CLOSED'
        END AS status
    FROM generate_series(1, 30) AS sequence_no
)
INSERT INTO ticket (
    id,          -- 工单内部标识，使用稳定 UUID
    ticket_no,   -- 外部工单编号
    kb_id,       -- 所属演示知识库
    title,       -- 工单标题
    description, -- 工单问题说明
    status,      -- 当前工单状态
    owner_name,  -- 当前模拟处理人
    created_at,  -- 创建时间
    updated_at   -- 最近状态更新时间
)
SELECT
    ('25100000-0000-4000-8000-' || LPAD(ticket_seed.sequence_no::TEXT, 12, '0'))::UUID,
    ticket_seed.ticket_no,
    knowledge_base.id,
    ticket_seed.title,
    '模拟订单系统工单 ' || ticket_seed.ticket_no || '，用于验证只读状态查询和知识库成员授权。',
    ticket_seed.status,
    CASE WHEN ticket_seed.status = 'OPEN' THEN NULL ELSE '支持工程师' || ((ticket_seed.sequence_no % 3) + 1)::TEXT END,
    CURRENT_TIMESTAMP - (31 - ticket_seed.sequence_no) * INTERVAL '1 day',
    CURRENT_TIMESTAMP - (ticket_seed.sequence_no % 6) * INTERVAL '1 hour'
FROM ticket_seed
INNER JOIN knowledge_base ON knowledge_base.id = '25000000-0000-4000-8000-000000000001'::UUID
ON CONFLICT (ticket_no) DO NOTHING;

COMMIT;
