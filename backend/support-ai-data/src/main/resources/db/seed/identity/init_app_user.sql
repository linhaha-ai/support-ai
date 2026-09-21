-- app_user：演示账号补齐脚本，须先由 Flyway 完成表结构及默认 admin 迁移，再按需手动执行。
-- 默认 admin 由 Flyway V4 自动补齐；本脚本同时保留 admin 作为幂等兜底，并补齐 support1、support2。
-- password_hash 使用 jBCrypt 0.4 生成的 BCrypt 摘要，计算成本为 12，不存储明文密码。
-- 本脚本不在 Flyway 自动扫描目录内，应用启动仅由 V4 创建默认管理员，不会自动创建其他演示用户。
-- 仅插入缺失的用户名；重复执行不覆盖已有账号的密码、角色、启用状态或其他资料。
BEGIN;

INSERT INTO app_user (
    id,            -- 用户标识，由数据库生成 UUID
    username,      -- 规范化且唯一的登录用户名
    password_hash, -- BCrypt 密码摘要
    display_name,  -- 用户展示名称
    role,          -- 用户角色：ADMIN 管理员、USER 普通用户
    enabled,       -- 是否启用账号
    created_at,    -- 创建时间
    updated_at     -- 最近更新时间
)
VALUES
    (gen_random_uuid(), 'admin', '$2a$12$g73UB6bVwry7220BrIZbR.3OBxAtd/imZJfpCCCHax2vwJa4OVsLq',
     '系统管理员', 'ADMIN', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), 'support1', '$2a$12$tLKiwTLcsqjZiwT3GRz8LuAzEvluOlWWMz5ZqlzWsdAGiencWGPQe',
     '支持用户一', 'USER', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), 'support2', '$2a$12$SRi7emWDxfHT1uGLQGAZeOQ0rzjCp8kqi/ZD1y0NEULE5AnjXWJRq',
     '支持用户二', 'USER', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (username) DO NOTHING;

COMMIT;
