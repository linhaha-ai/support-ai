package com.haha.supportai.data.knowledge.mapper;

import java.time.OffsetDateTime;
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
                WHERE job.stage = #{stage,jdbcType=VARCHAR}
                  AND job.attempt < job.max_attempts
                  AND job.cancel_requested_at IS NULL
                  AND (
                      (job.status IN ('QUEUED', 'RETRY_WAIT') AND job.next_run_at <= CURRENT_TIMESTAMP)
                      OR (job.status = 'RUNNING' AND (job.lease_until IS NULL OR job.lease_until <= CURRENT_TIMESTAMP))
                  )
                ORDER BY job.next_run_at, job.created_at, job.id
                FOR UPDATE SKIP LOCKED
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
            """)
    int markRetryWait(@Param("jobId") UUID jobId, @Param("leaseOwner") String leaseOwner,
                      @Param("leaseToken") UUID leaseToken,
                      @Param("nextRunAt") OffsetDateTime nextRunAt, @Param("errorCode") String errorCode,
                      @Param("errorDetail") String errorDetail);

    @Update("""
            -- ingestion_job：仅未过期租约可写入最终失败结果并释放租约
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
            """)
    int markFailed(@Param("jobId") UUID jobId, @Param("leaseOwner") String leaseOwner,
                   @Param("leaseToken") UUID leaseToken,
                   @Param("errorCode") String errorCode, @Param("errorDetail") String errorDetail);

    @Update("""
            -- ingestion_job、document_version：租约耗尽时同步终止任务和仍在构建的版本，避免版本永久停留 BUILDING。
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
                RETURNING job.version_id
            )
            UPDATE document_version version
            SET status = 'FAILED',
                updated_at = CURRENT_TIMESTAMP
            FROM failed_jobs
            WHERE version.id = failed_jobs.version_id
              AND version.status IN ('QUEUED', 'BUILDING')
            """)
    int markExpiredExhaustedRunningAsFailed();
}
