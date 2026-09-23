package com.haha.supportai.data.knowledge.mapper;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface IngestionJobMapper extends BaseMapper<IngestionJob> {

    @Select("""
            -- ingestion_job：原子领取同一阶段的一条可执行任务，并跳过其他工作者已锁定的记录
            WITH candidate AS (
                SELECT job.id
                FROM ingestion_job job
                INNER JOIN document_version version ON version.id = job.version_id
                INNER JOIN document document ON document.id = version.document_id
                WHERE job.stage = #{stage,jdbcType=VARCHAR}
                  AND job.attempt < job.max_attempts
                  AND job.cancel_requested_at IS NULL
                  AND version.status IN ('QUEUED', 'BUILDING')
                  AND document.pending_version_id = version.id
                  AND document.deleted_at IS NULL
                  AND (
                      (job.status IN ('QUEUED', 'RETRY_WAIT') AND job.next_run_at <= CURRENT_TIMESTAMP)
                      OR (job.status = 'RUNNING' AND (job.lease_until IS NULL OR job.lease_until <= CURRENT_TIMESTAMP))
                  )
                ORDER BY job.next_run_at, job.created_at, job.id
                FOR UPDATE OF job SKIP LOCKED
                LIMIT 1
            )
            UPDATE ingestion_job job
            SET status = 'RUNNING',
                attempt = job.attempt + 1,
                lease_owner = #{leaseOwner},
                lease_token = #{leaseToken,jdbcType=OTHER},
                lease_until = #{leaseUntil},
                heartbeat_at = CURRENT_TIMESTAMP,
                started_at = COALESCE(job.started_at, CURRENT_TIMESTAMP),
                finished_at = NULL,
                error_code = NULL,
                error_detail = NULL,
                updated_at = CURRENT_TIMESTAMP
            FROM candidate
            WHERE job.id = candidate.id
              AND #{leaseUntil} > CURRENT_TIMESTAMP
            RETURNING job.id, job.version_id, job.status, job.stage, job.attempt, job.max_attempts,
                      job.lease_owner, job.lease_token, job.lease_until, job.heartbeat_at, job.next_run_at,
                      job.completed_chunks, job.total_chunks, job.error_code, job.error_detail,
                      job.cancel_requested_at, job.started_at, job.finished_at, job.created_at, job.updated_at
            """)
    @Results(id = "ingestionJobResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "version_id", property = "versionId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "status", property = "status", javaType = IngestionJobStatusEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "stage", property = "stage", javaType = IngestionStageEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "attempt", property = "attempt"),
            @Result(column = "max_attempts", property = "maxAttempts"),
            @Result(column = "lease_owner", property = "leaseOwner"),
            @Result(column = "lease_token", property = "leaseToken", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "lease_until", property = "leaseUntil"),
            @Result(column = "heartbeat_at", property = "heartbeatAt"),
            @Result(column = "next_run_at", property = "nextRunAt"),
            @Result(column = "completed_chunks", property = "completedChunks"),
            @Result(column = "total_chunks", property = "totalChunks"),
            @Result(column = "error_code", property = "errorCode"),
            @Result(column = "error_detail", property = "errorDetail"),
            @Result(column = "cancel_requested_at", property = "cancelRequestedAt"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "finished_at", property = "finishedAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<IngestionJob> claimNextByStage(@Param("stage") IngestionStageEnum stage,
                                            @Param("leaseOwner") String leaseOwner,
                                            @Param("leaseToken") UUID leaseToken,
                                            @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Select("""
            <script>
            -- ingestion_job：按文档版本批量读取任务摘要，供已完成授权校验的文档列表聚合使用。
            SELECT job.id, job.version_id, job.status, job.stage, job.attempt, job.max_attempts,
                   job.lease_owner, job.lease_token, job.lease_until, job.heartbeat_at, job.next_run_at,
                   job.completed_chunks, job.total_chunks, job.error_code, job.error_detail,
                   job.cancel_requested_at, job.started_at, job.finished_at, job.created_at, job.updated_at
            FROM ingestion_job job
            WHERE job.version_id IN
            <foreach collection="versionIds" item="versionId" open="(" separator="," close=")">
              #{versionId,jdbcType=OTHER}
            </foreach>
            </script>
            """)
    @ResultMap("ingestionJobResultMap")
    List<IngestionJob> selectByVersionIds(@Param("versionIds") Collection<UUID> versionIds);

    @Select("""
            -- ingestion_job：经版本、文档、启用知识库和成员关系联查，避免暴露无权任务
            SELECT job.id, job.version_id, job.status, job.stage, job.attempt, job.max_attempts,
                   job.lease_owner, job.lease_token, job.lease_until, job.heartbeat_at, job.next_run_at,
                   job.completed_chunks, job.total_chunks, job.error_code, job.error_detail,
                   job.cancel_requested_at, job.started_at, job.finished_at, job.created_at, job.updated_at
            FROM ingestion_job job
            INNER JOIN document_version version ON version.id = job.version_id
            INNER JOIN document document ON document.id = version.document_id
            INNER JOIN knowledge_base kb ON kb.id = document.kb_id
            INNER JOIN kb_member member ON member.kb_id = kb.id
            WHERE job.id = #{jobId,jdbcType=OTHER}
              AND member.user_id = #{userId,jdbcType=OTHER}
              AND kb.status = 'ACTIVE'
              AND document.deleted_at IS NULL
            """)
    @ResultMap("ingestionJobResultMap")
    Optional<IngestionJob> selectAccessibleByIdAndUserId(@Param("jobId") UUID jobId, @Param("userId") UUID userId);

    @Select("""
            -- ingestion_job、document、knowledge_base、kb_member、app_user：复核当前用户是否仍有任务控制权限。
            SELECT EXISTS (
                SELECT 1
                FROM ingestion_job job
                INNER JOIN document_version version ON version.id = job.version_id
                INNER JOIN document document ON document.id = version.document_id
                INNER JOIN knowledge_base kb ON kb.id = document.kb_id
                INNER JOIN kb_member member ON member.kb_id = kb.id
                INNER JOIN app_user account ON account.id = member.user_id
                WHERE job.id = #{jobId,jdbcType=OTHER}
                  AND member.user_id = #{userId,jdbcType=OTHER}
                  AND member.permission = 'WRITE'
                  AND kb.status = 'ACTIVE'
                  AND account.enabled = TRUE
                  AND document.deleted_at IS NULL
            )
            """)
    boolean hasWriteAccess(@Param("jobId") UUID jobId, @Param("userId") UUID userId);

    @Select("""
            -- ingestion_job、document_version、document：取消任务、使租约失效并仅清理本版本待处理指针。
            WITH candidate AS (
                SELECT job.id, job.version_id
                FROM ingestion_job job
                INNER JOIN document_version version ON version.id = job.version_id
                INNER JOIN document document ON document.id = version.document_id
                INNER JOIN knowledge_base kb ON kb.id = document.kb_id
                INNER JOIN kb_member member ON member.kb_id = kb.id
                INNER JOIN app_user account ON account.id = member.user_id
                WHERE job.id = #{jobId,jdbcType=OTHER}
                  AND member.user_id = #{userId,jdbcType=OTHER}
                  AND member.permission = 'WRITE'
                  AND kb.status = 'ACTIVE'
                  AND account.enabled = TRUE
                  AND document.deleted_at IS NULL
                  AND job.status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                  AND version.status IN ('QUEUED', 'BUILDING')
                FOR UPDATE OF job, version, document
            ), cancelled_job AS (
                UPDATE ingestion_job job
                SET status = 'CANCELLED',
                    lease_owner = NULL,
                    lease_token = NULL,
                    lease_until = NULL,
                    error_code = NULL,
                    error_detail = NULL,
                    cancel_requested_at = COALESCE(job.cancel_requested_at, CURRENT_TIMESTAMP),
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id, job.version_id, job.status, job.stage, job.attempt, job.max_attempts,
                          job.lease_owner, job.lease_token, job.lease_until, job.heartbeat_at, job.next_run_at,
                          job.completed_chunks, job.total_chunks, job.error_code, job.error_detail,
                          job.cancel_requested_at, job.started_at, job.finished_at, job.created_at, job.updated_at
            ), cancelled_version AS (
                UPDATE document_version version
                SET status = 'CANCELLED',
                    updated_at = CURRENT_TIMESTAMP
                FROM cancelled_job job
                WHERE version.id = job.version_id
                  AND version.status IN ('QUEUED', 'BUILDING')
                RETURNING version.id, version.document_id
            ), cleared_document AS (
                UPDATE document document
                SET pending_version_id = NULL,
                    revision = document.revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                FROM cancelled_version version
                WHERE document.id = version.document_id
                  AND document.pending_version_id = version.id
                RETURNING document.id
            )
            SELECT job.id, job.version_id, job.status, job.stage, job.attempt, job.max_attempts,
                   job.lease_owner, job.lease_token, job.lease_until, job.heartbeat_at, job.next_run_at,
                   job.completed_chunks, job.total_chunks, job.error_code, job.error_detail,
                   job.cancel_requested_at, job.started_at, job.finished_at, job.created_at, job.updated_at
            FROM cancelled_job job
            """)
    @ResultMap("ingestionJobResultMap")
    Optional<IngestionJob> cancelByIdAndUserId(@Param("jobId") UUID jobId, @Param("userId") UUID userId);

    @Select("""
            -- ingestion_job、document_version、document：人工重试只恢复仍可执行的失败版本并重新取得待处理指针。
            WITH candidate AS (
                SELECT job.id, job.version_id, job.stage, version.document_id
                FROM ingestion_job job
                INNER JOIN document_version version ON version.id = job.version_id
                INNER JOIN document document ON document.id = version.document_id
                INNER JOIN knowledge_base kb ON kb.id = document.kb_id
                INNER JOIN kb_member member ON member.kb_id = kb.id
                INNER JOIN app_user account ON account.id = member.user_id
                WHERE job.id = #{jobId,jdbcType=OTHER}
                  AND member.user_id = #{userId,jdbcType=OTHER}
                  AND member.permission = 'WRITE'
                  AND kb.status = 'ACTIVE'
                  AND account.enabled = TRUE
                  AND document.deleted_at IS NULL
                  AND job.status = 'FAILED'
                  AND job.attempt < job.max_attempts
                  AND job.cancel_requested_at IS NULL
                  AND version.status = 'FAILED'
                  AND (document.pending_version_id IS NULL OR document.pending_version_id = version.id)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM document_version active_version
                      WHERE active_version.id = document.active_version_id
                        AND active_version.version_no > version.version_no
                  )
                FOR UPDATE OF job, version, document
            ), restored_version AS (
                UPDATE document_version version
                SET status = CASE candidate.stage WHEN 'PARSE' THEN 'QUEUED' ELSE 'BUILDING' END,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE version.id = candidate.version_id
                RETURNING version.id, version.document_id
            ), claimed_document AS (
                UPDATE document document
                SET pending_version_id = candidate.version_id,
                    revision = document.revision
                        + CASE WHEN document.pending_version_id IS DISTINCT FROM candidate.version_id THEN 1 ELSE 0 END,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                INNER JOIN restored_version version ON version.id = candidate.version_id
                WHERE document.id = candidate.document_id
                  AND (document.pending_version_id IS NULL OR document.pending_version_id = candidate.version_id)
                RETURNING document.id
            ), retried_job AS (
                UPDATE ingestion_job job
                SET status = 'QUEUED',
                    lease_owner = NULL,
                    lease_token = NULL,
                    lease_until = NULL,
                    next_run_at = CURRENT_TIMESTAMP,
                    error_code = NULL,
                    error_detail = NULL,
                    finished_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                INNER JOIN claimed_document document ON document.id = candidate.document_id
                WHERE job.id = candidate.id
                RETURNING job.id, job.version_id, job.status, job.stage, job.attempt, job.max_attempts,
                          job.lease_owner, job.lease_token, job.lease_until, job.heartbeat_at, job.next_run_at,
                          job.completed_chunks, job.total_chunks, job.error_code, job.error_detail,
                          job.cancel_requested_at, job.started_at, job.finished_at, job.created_at, job.updated_at
            )
            SELECT job.id, job.version_id, job.status, job.stage, job.attempt, job.max_attempts,
                   job.lease_owner, job.lease_token, job.lease_until, job.heartbeat_at, job.next_run_at,
                   job.completed_chunks, job.total_chunks, job.error_code, job.error_detail,
                   job.cancel_requested_at, job.started_at, job.finished_at, job.created_at, job.updated_at
            FROM retried_job job
            """)
    @ResultMap("ingestionJobResultMap")
    Optional<IngestionJob> retryByIdAndUserId(@Param("jobId") UUID jobId, @Param("userId") UUID userId);

    @Select("""
            -- ingestion_job：分块落库前锁定并复核任务版本、阶段和未过期租约，拒绝失效工作者回写。
            SELECT job.id, job.version_id, job.status, job.stage, job.attempt, job.max_attempts,
                   job.lease_owner, job.lease_token, job.lease_until, job.heartbeat_at, job.next_run_at,
                   job.completed_chunks, job.total_chunks, job.error_code, job.error_detail,
                   job.cancel_requested_at, job.started_at, job.finished_at, job.created_at, job.updated_at
            FROM ingestion_job job
            INNER JOIN document_version version ON version.id = job.version_id
            INNER JOIN document document ON document.id = version.document_id
            WHERE job.id = #{jobId,jdbcType=OTHER}
              AND job.version_id = #{versionId,jdbcType=OTHER}
              AND job.status = 'RUNNING'
              AND job.stage = #{stage,jdbcType=VARCHAR}
              AND job.lease_owner = #{leaseOwner}
              AND job.lease_token = #{leaseToken,jdbcType=OTHER}
              AND job.lease_until > CURRENT_TIMESTAMP
              AND version.status IN ('QUEUED', 'BUILDING')
              AND document.pending_version_id = version.id
              AND document.deleted_at IS NULL
            FOR UPDATE OF job, version, document
            """)
    @ResultMap("ingestionJobResultMap")
    Optional<IngestionJob> selectActiveLeaseForUpdate(@Param("jobId") UUID jobId, @Param("versionId") UUID versionId,
                                                       @Param("stage") IngestionStageEnum stage,
                                                       @Param("leaseOwner") String leaseOwner,
                                                       @Param("leaseToken") UUID leaseToken);

    @Update("""
            -- ingestion_job：续租前必须仍持有未过期租约令牌
            UPDATE ingestion_job job
            SET lease_until = #{leaseUntil},
                heartbeat_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE job.id = #{jobId,jdbcType=OTHER}
              AND job.status = 'RUNNING'
              AND job.lease_owner = #{leaseOwner}
              AND job.lease_token = #{leaseToken,jdbcType=OTHER}
              AND job.lease_until > CURRENT_TIMESTAMP
              AND #{leaseUntil} > CURRENT_TIMESTAMP
            """)
    int renewLease(@Param("jobId") UUID jobId, @Param("leaseOwner") String leaseOwner,
                   @Param("leaseToken") UUID leaseToken,
                   @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Update("""
            -- ingestion_job：仅有效执行租约可写入单调递增且总量固定的处理进度
            UPDATE ingestion_job job
            SET completed_chunks = #{completedChunks},
                total_chunks = #{totalChunks},
                updated_at = CURRENT_TIMESTAMP
            WHERE job.id = #{jobId,jdbcType=OTHER}
              AND job.status = 'RUNNING'
              AND job.lease_owner = #{leaseOwner}
              AND job.lease_token = #{leaseToken,jdbcType=OTHER}
              AND job.lease_until > CURRENT_TIMESTAMP
              AND #{completedChunks} >= 0
              AND #{totalChunks} >= 0
              AND #{completedChunks} <= #{totalChunks}
              AND (job.total_chunks IS NULL OR job.total_chunks = #{totalChunks})
              AND (job.completed_chunks IS NULL OR job.completed_chunks <= #{completedChunks})
            """)
    int updateProgress(@Param("jobId") UUID jobId, @Param("leaseOwner") String leaseOwner,
                       @Param("leaseToken") UUID leaseToken,
                       @Param("completedChunks") int completedChunks, @Param("totalChunks") int totalChunks);

    @Update("""
            -- ingestion_job：推进阶段后重置本阶段尝试次数并重新入队，避免四阶段被默认上限提前耗尽
            UPDATE ingestion_job job
            SET status = 'QUEUED',
                stage = CASE job.stage
                    WHEN 'PARSE' THEN 'CHUNK'
                    WHEN 'CHUNK' THEN 'EMBED'
                    WHEN 'EMBED' THEN 'PUBLISH'
                END,
                attempt = 0,
                lease_owner = NULL,
                lease_token = NULL,
                lease_until = NULL,
                next_run_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE job.id = #{jobId,jdbcType=OTHER}
              AND job.status = 'RUNNING'
              AND job.lease_owner = #{leaseOwner}
              AND job.lease_token = #{leaseToken,jdbcType=OTHER}
              AND job.lease_until > CURRENT_TIMESTAMP
              AND job.stage = #{stage,jdbcType=VARCHAR}
              AND job.stage IN ('PARSE', 'CHUNK', 'EMBED')
            """)
    int advanceToNextStage(@Param("jobId") UUID jobId, @Param("leaseOwner") String leaseOwner,
                           @Param("leaseToken") UUID leaseToken, @Param("stage") IngestionStageEnum stage);

    @Update("""
            -- ingestion_job：仅持有有效 PUBLISH 租约的工作者可标记成功，完成数和总数必须完全一致。
            UPDATE ingestion_job job
            SET status = 'SUCCEEDED',
                completed_chunks = #{chunkCount},
                total_chunks = #{chunkCount},
                lease_owner = NULL,
                lease_token = NULL,
                lease_until = NULL,
                heartbeat_at = CURRENT_TIMESTAMP,
                error_code = NULL,
                error_detail = NULL,
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE job.id = #{jobId,jdbcType=OTHER}
              AND job.status = 'RUNNING'
              AND job.stage = 'PUBLISH'
              AND job.lease_owner = #{leaseOwner}
              AND job.lease_token = #{leaseToken,jdbcType=OTHER}
              AND job.lease_until > CURRENT_TIMESTAMP
              AND #{chunkCount} >= 0
              AND job.total_chunks = #{chunkCount}
              AND job.completed_chunks = #{chunkCount}
            """)
    int markSucceeded(@Param("jobId") UUID jobId, @Param("leaseOwner") String leaseOwner,
                      @Param("leaseToken") UUID leaseToken, @Param("chunkCount") int chunkCount);

    @Update("""
            -- ingestion_job：仅未过期租约可回写可重试状态，达到最大尝试次数的任务不能重新排队
            UPDATE ingestion_job job
            SET status = 'RETRY_WAIT',
                lease_owner = NULL,
                lease_token = NULL,
                lease_until = NULL,
                next_run_at = #{nextRunAt},
                error_code = #{errorCode},
                error_detail = #{errorDetail},
                updated_at = CURRENT_TIMESTAMP
            WHERE job.id = #{jobId,jdbcType=OTHER}
              AND job.status = 'RUNNING'
              AND job.lease_owner = #{leaseOwner}
              AND job.lease_token = #{leaseToken,jdbcType=OTHER}
              AND job.lease_until > CURRENT_TIMESTAMP
              AND job.attempt < job.max_attempts
              AND job.cancel_requested_at IS NULL
              AND EXISTS (
                  SELECT 1
                  FROM document_version version
                  INNER JOIN document document ON document.id = version.document_id
                  WHERE version.id = job.version_id
                    AND version.status IN ('QUEUED', 'BUILDING')
                    AND document.pending_version_id = version.id
                    AND document.deleted_at IS NULL
              )
            """)
    int markRetryWait(@Param("jobId") UUID jobId, @Param("leaseOwner") String leaseOwner,
                      @Param("leaseToken") UUID leaseToken,
                      @Param("nextRunAt") OffsetDateTime nextRunAt, @Param("errorCode") String errorCode,
                      @Param("errorDetail") String errorDetail);

    @Select("""
            -- ingestion_job、document_version、document：终止失败时同步版本终态并仅释放本任务待处理指针。
            WITH failed_job AS (
                UPDATE ingestion_job job
                SET status = 'FAILED',
                    lease_owner = NULL,
                    lease_token = NULL,
                    lease_until = NULL,
                    error_code = #{errorCode},
                    error_detail = #{errorDetail},
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE job.id = #{jobId,jdbcType=OTHER}
                  AND job.status = 'RUNNING'
                  AND job.lease_owner = #{leaseOwner}
                  AND job.lease_token = #{leaseToken,jdbcType=OTHER}
                  AND job.lease_until > CURRENT_TIMESTAMP
                RETURNING job.id, job.version_id, job.status, job.stage, job.attempt, job.max_attempts,
                          job.lease_owner, job.lease_token, job.lease_until, job.heartbeat_at, job.next_run_at,
                          job.completed_chunks, job.total_chunks, job.error_code, job.error_detail,
                          job.cancel_requested_at, job.started_at, job.finished_at, job.created_at, job.updated_at
            ), failed_version AS (
                UPDATE document_version version
                SET status = 'FAILED',
                    updated_at = CURRENT_TIMESTAMP
                FROM failed_job job
                WHERE version.id = job.version_id
                  AND version.status IN ('QUEUED', 'BUILDING')
                RETURNING version.id, version.document_id
            ), cleared_document AS (
                UPDATE document document
                SET pending_version_id = NULL,
                    revision = document.revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                FROM failed_version version
                WHERE document.id = version.document_id
                  AND document.pending_version_id = version.id
                RETURNING document.id
            )
            SELECT job.id, job.version_id, job.status, job.stage, job.attempt, job.max_attempts,
                   job.lease_owner, job.lease_token, job.lease_until, job.heartbeat_at, job.next_run_at,
                   job.completed_chunks, job.total_chunks, job.error_code, job.error_detail,
                   job.cancel_requested_at, job.started_at, job.finished_at, job.created_at, job.updated_at
            FROM failed_job job
            """)
    @ResultMap("ingestionJobResultMap")
    Optional<IngestionJob> markFailed(@Param("jobId") UUID jobId, @Param("leaseOwner") String leaseOwner,
                                      @Param("leaseToken") UUID leaseToken,
                                      @Param("errorCode") String errorCode,
                                      @Param("errorDetail") String errorDetail);

    @Select("""
            -- ingestion_job、document_version、document：租约耗尽时同步失败终态并释放本任务待处理指针。
            WITH failed_jobs AS (
                UPDATE ingestion_job job
                SET status = 'FAILED',
                    lease_owner = NULL,
                    lease_token = NULL,
                    lease_until = NULL,
                    error_code = 'INGESTION_LEASE_EXPIRED',
                    error_detail = '任务租约过期且已达到最大尝试次数',
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE job.status = 'RUNNING'
                  AND job.attempt >= job.max_attempts
                  AND (job.lease_until IS NULL OR job.lease_until <= CURRENT_TIMESTAMP)
                RETURNING job.id, job.version_id
            ), failed_versions AS (
                UPDATE document_version version
                SET status = 'FAILED',
                    updated_at = CURRENT_TIMESTAMP
                FROM failed_jobs job
                WHERE version.id = job.version_id
                  AND version.status IN ('QUEUED', 'BUILDING')
                RETURNING version.id, version.document_id
            ), cleared_documents AS (
                UPDATE document document
                SET pending_version_id = NULL,
                    revision = document.revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                FROM failed_versions version
                WHERE document.id = version.document_id
                  AND document.pending_version_id = version.id
                RETURNING document.id
            )
            SELECT COUNT(*)::INTEGER
            FROM failed_jobs
            """)
    int markExpiredExhaustedRunningAsFailed();
}
