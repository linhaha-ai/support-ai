-- 通过增量迁移补齐账号表和字段说明，保留已执行的 V2 建表脚本校验值。
COMMENT ON TABLE app_user IS '用户账号表，保存登录凭据摘要、展示信息和账号状态';
COMMENT ON COLUMN app_user.id IS '用户唯一标识，使用 UUID';
COMMENT ON COLUMN app_user.username IS '登录用户名，去除首尾空白并转为小写，全局唯一';
COMMENT ON COLUMN app_user.password_hash IS 'BCrypt 密码摘要，不保存明文密码';
COMMENT ON COLUMN app_user.display_name IS '用户展示名称';
COMMENT ON COLUMN app_user.role IS '用户角色：ADMIN 管理员，USER 普通用户';
COMMENT ON COLUMN app_user.enabled IS '账号是否启用，停用后禁止登录并使已有登录态失效';
COMMENT ON COLUMN app_user.created_at IS '账号创建时间，带时区';
COMMENT ON COLUMN app_user.updated_at IS '账号最近更新时间，带时区';
