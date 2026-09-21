package com.haha.supportai.data.knowledge.mapper;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentCleanupJob;
import com.haha.supportai.data.knowledge.enums.DocumentCleanupStatusEnum;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface DocumentCleanupJobMapper extends BaseMapper<DocumentCleanupJob> {

    @Insert("""
            -- document_cleanup_job：文档删除事务内幂等登记唯一清理任务。
            INSERT INTO document_cleanup_job (
                id, document_id, status, attempt, next_run_at, created_at, updated_at
            ) VALUES (
                #{job.id,jdbcType=OTHER}, #{job.documentId,jdbcType=OTHER}, #{job.status,jdbcType=VARCHAR},
                #{job.attempt}, #{job.nextRunAt}, #{job.createdAt}, #{job.updatedAt}
            )
            ON CONFLICT (document_id) DO NOTHING
            """)
    int insertPendingIfAbsent(@Param("job") DocumentCleanupJob job);

    @Select("""
            -- document_cleanup_job、document：原子领取一条已删除文档的到期清理任务，并回收过期租约。
            WITH candidate AS (
                SELECT cleanup.id
                FROM document_cleanup_job cleanup
                INNER JOIN document ON document.id = cleanup.document_id
                WHERE document.deleted_at IS NOT NULL
                  AND (
                      (cleanup.status IN ('PENDING', 'RETRY_WAIT')
                          AND cleanup.next_run_at <= CURRENT_TIMESTAMP)
                      OR (cleanup.status = 'RUNNING'
                          AND (cleanup.lease_until IS NULL OR cleanup.lease_until <= CURRENT_TIMESTAMP))
                  )
                ORDER BY cleanup.next_run_at, cleanup.created_at, cleanup.id
                FOR UPDATE OF cleanup SKIP LOCKED
                LIMIT 1
            )
            UPDATE document_cleanup_job cleanup
            SET status = 'RUNNING',
                attempt = cleanup.attempt + 1,
                lease_owner = #{leaseOwner},
                lease_token = #{leaseToken,jdbcType=OTHER},
                lease_until = #{leaseUntil},
                started_at = COALESCE(cleanup.started_at, CURRENT_TIMESTAMP),
                finished_at = NULL,
                error_code = NULL,
                error_detail = NULL,
                updated_at = CURRENT_TIMESTAMP
            FROM candidate
            WHERE cleanup.id = candidate.id
              AND #{leaseUntil} > CURRENT_TIMESTAMP
            RETURNING cleanup.id, cleanup.document_id, cleanup.status, cleanup.attempt,
                      cleanup.lease_owner, cleanup.lease_token, cleanup.lease_until, cleanup.next_run_at,
                      cleanup.error_code, cleanup.error_detail, cleanup.started_at, cleanup.finished_at,
                      cleanup.created_at, cleanup.updated_at
            """)
    @Results(id = "documentCleanupJobResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "document_id", property = "documentId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "status", property = "status", javaType = DocumentCleanupStatusEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "attempt", property = "attempt"),
            @Result(column = "lease_owner", property = "leaseOwner"),
            @Result(column = "lease_token", property = "leaseToken", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "lease_until", property = "leaseUntil"),
            @Result(column = "next_run_at", property = "nextRunAt"),
            @Result(column = "error_code", property = "errorCode"),
            @Result(column = "error_detail", property = "errorDetail"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "finished_at", property = "finishedAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<DocumentCleanupJob> claimNext(@Param("leaseOwner") String leaseOwner,
                                           @Param("leaseToken") UUID leaseToken,
                                           @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Select("""
            -- document_cleanup_job：完成清理前锁定并复核当前未过期租约。
            SELECT id, document_id, status, attempt, lease_owner, lease_token, lease_until, next_run_at,
                   error_code, error_detail, started_at, finished_at, created_at, updated_at
            FROM document_cleanup_job
            WHERE id = #{jobId,jdbcType=OTHER}
              AND document_id = #{documentId,jdbcType=OTHER}
              AND status = 'RUNNING'
              AND lease_owner = #{leaseOwner}
              AND lease_token = #{leaseToken,jdbcType=OTHER}
              AND lease_until > CURRENT_TIMESTAMP
            FOR UPDATE
            """)
    @ResultMap("documentCleanupJobResultMap")
    Optional<DocumentCleanupJob> selectActiveLeaseForUpdate(@Param("jobId") UUID jobId,
                                                             @Param("documentId") UUID documentId,
                                                             @Param("leaseOwner") String leaseOwner,
                                                             @Param("leaseToken") UUID leaseToken);

    @Update("""
            -- document_cleanup_job：仅当前有效租约可提交清理成功终态。
            UPDATE document_cleanup_job
            SET status = 'SUCCEEDED',
                lease_owner = NULL,
                lease_token = NULL,
                lease_until = NULL,
                error_code = NULL,
                error_detail = NULL,
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{jobId,jdbcType=OTHER}
              AND status = 'RUNNING'
              AND lease_owner = #{leaseOwner}
              AND lease_token = #{leaseToken,jdbcType=OTHER}
              AND lease_until > CURRENT_TIMESTAMP
            """)
    int markSucceeded(@Param("jobId") UUID jobId,
                      @Param("leaseOwner") String leaseOwner,
                      @Param("leaseToken") UUID leaseToken);

    @Update("""
            -- document_cleanup_job：清理失败后释放当前租约并以脱敏原因等待自动重试。
            UPDATE document_cleanup_job
            SET status = 'RETRY_WAIT',
                lease_owner = NULL,
                lease_token = NULL,
                lease_until = NULL,
                next_run_at = #{nextRunAt},
                error_code = #{errorCode},
                error_detail = #{errorDetail},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{jobId,jdbcType=OTHER}
              AND status = 'RUNNING'
              AND lease_owner = #{leaseOwner}
              AND lease_token = #{leaseToken,jdbcType=OTHER}
              AND lease_until > CURRENT_TIMESTAMP
            """)
    int markRetryWait(@Param("jobId") UUID jobId,
                      @Param("leaseOwner") String leaseOwner,
                      @Param("leaseToken") UUID leaseToken,
                      @Param("nextRunAt") OffsetDateTime nextRunAt,
                      @Param("errorCode") String errorCode,
                      @Param("errorDetail") String errorDetail);
}
