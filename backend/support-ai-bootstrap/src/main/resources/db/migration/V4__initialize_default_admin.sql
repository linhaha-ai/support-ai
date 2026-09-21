-- app_user 默认管理员：应用首次迁移时自动补齐，已有同名账号时不覆盖密码、角色或状态。
INSERT INTO app_user (
    id,            -- 用户标识，由数据库生成 UUID
    username,      -- 规范化且唯一的登录用户名
    password_hash, -- BCrypt 密码摘要，默认密码与用户名相同
    display_name,  -- 用户展示名称
    role,          -- 用户角色：ADMIN 管理员
    enabled,       -- 是否启用账号
    created_at,    -- 创建时间
    updated_at     -- 最近更新时间
)
VALUES (
    gen_random_uuid(),
    'admin',
    '$2a$12$g73UB6bVwry7220BrIZbR.3OBxAtd/imZJfpCCCHax2vwJa4OVsLq',
    '系统管理员',
    'ADMIN',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (username) DO NOTHING;
