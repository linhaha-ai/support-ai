-- ingestion_job.attempt 按处理阶段计数，阶段成功推进后由任务领取 SQL 重置。
COMMENT ON COLUMN ingestion_job.attempt IS '当前处理阶段已发起的执行次数，阶段成功推进后重置为 0';
COMMENT ON COLUMN ingestion_job.max_attempts IS '单个处理阶段允许的最大执行次数';
