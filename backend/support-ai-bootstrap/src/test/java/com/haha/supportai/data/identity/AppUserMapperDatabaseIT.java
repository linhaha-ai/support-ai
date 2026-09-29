package com.haha.supportai.data.identity;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.haha.supportai.data.identity.entity.po.AppUser;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.identity.mapper.AppUserMapper;
import com.haha.supportai.data.identity.repository.AppUserRepository;
import com.haha.supportai.storage.PostgresFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false",
        "support.ai.document.cleanup.enabled=false"
})
class AppUserMapperDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired
    AppUserMapper appUserMapper;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @EnumSource(UserRoleEnum.class)
    @Transactional
    void roundTripsUuidRoleTimestampsAndRepositoryQueries(UserRoleEnum role) {
        AppUser user = newUser(role);
        UUID assignedId = user.getId();

        assertThat(appUserMapper.insert(user)).isEqualTo(1);
        assertThat(user.getId()).isEqualTo(assignedId);
        assertStoredUser(appUserMapper.selectById(user.getId()), user);
        assertStoredUser(appUserRepository.findById(user.getId()).orElseThrow(), user);
        assertStoredUser(appUserRepository.findByUsername(user.getUsername()).orElseThrow(), user);
        assertThat(appUserRepository.isEnabled(user.getId())).isTrue();

        // 独立通过 JDBC 检查真实存储类型和值，避免 Mapper 的对称转换掩盖类型错误。
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username = ?",
                UUID.class, user.getUsername())).isEqualTo(user.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT pg_typeof(id)::text FROM app_user WHERE id = ?",
                String.class, user.getId())).isEqualTo("uuid");
        assertThat(jdbcTemplate.queryForObject("SELECT pg_typeof(role)::text FROM app_user WHERE id = ?",
                String.class, user.getId())).isEqualTo("character varying");
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM app_user WHERE id = ?",
                String.class, user.getId())).isEqualTo(role.name());

        user.setDisplayName("已修改的映射测试账号");
        user.setRole(role == UserRoleEnum.ADMIN ? UserRoleEnum.USER : UserRoleEnum.ADMIN);
        user.setEnabled(false);
        user.setUpdatedAt(user.getUpdatedAt().plusHours(1));

        assertThat(appUserMapper.updateById(user)).isEqualTo(1);
        assertStoredUser(appUserMapper.selectById(user.getId()), user);
        assertStoredUser(appUserRepository.findByUsername(user.getUsername()).orElseThrow(), user);
        assertThat(appUserRepository.isEnabled(user.getId())).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM app_user WHERE id = ?",
                String.class, user.getId())).isEqualTo(user.getRole().name());

        UUID missingId = UUID.randomUUID();
        assertThat(appUserRepository.findById(missingId)).isEmpty();
        assertThat(appUserRepository.findByUsername("missing-" + missingId)).isEmpty();
        assertThat(appUserRepository.isEnabled(missingId)).isFalse();
    }

    @Test
    void rollsBackMapperInsertAndUpdateInTheSharedSpringTransaction() {
        AppUser existing = newUser(UserRoleEnum.ADMIN);
        AppUser inserted = newUser(UserRoleEnum.USER);
        try {
            assertThat(appUserMapper.insert(existing)).isEqualTo(1);

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(appUserMapper.insert(inserted)).isEqualTo(1);
                AppUser changed = appUserMapper.selectById(existing.getId());
                changed.setEnabled(false);
                changed.setRole(UserRoleEnum.USER);
                changed.setUpdatedAt(changed.getUpdatedAt().plusHours(1));
                assertThat(appUserMapper.updateById(changed)).isEqualTo(1);
                assertThat(appUserRepository.findById(inserted.getId())).isPresent();
                assertThat(appUserRepository.isEnabled(existing.getId())).isFalse();
                assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM app_user WHERE id = ?",
                        Boolean.class, existing.getId())).isFalse();
                status.setRollbackOnly();
            });

            // 在事务外重新读取，确认 Mapper 未绕过 Spring 管理的连接而提前提交。
            assertThat(appUserMapper.selectById(inserted.getId())).isNull();
            assertThat(appUserRepository.findByUsername(inserted.getUsername())).isEmpty();
            assertStoredUser(appUserRepository.findById(existing.getId()).orElseThrow(), existing);
            assertThat(appUserRepository.isEnabled(existing.getId())).isTrue();
        } finally {
            jdbcTemplate.update("DELETE FROM app_user WHERE id IN (?, ?)", existing.getId(), inserted.getId());
        }
    }

    @Test
    void enabledUserLockSerializesPerUserQuotaChecks() throws Exception {
        AppUser user = newUser(UserRoleEnum.USER);
        assertThat(appUserMapper.insert(user)).isEqualTo(1);
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstLock = new CountDownLatch(1);
        CountDownLatch secondLookupStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        assertThat(appUserRepository.findEnabledByIdForUpdate(user.getId())).isPresent();
                        firstLockAcquired.countDown();
                        await(releaseFirstLock);
                    }));
            assertThat(firstLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            Future<AppUser> second = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        secondLookupStarted.countDown();
                        return appUserRepository.findEnabledByIdForUpdate(user.getId()).orElseThrow();
                    }));
            assertThat(secondLookupStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirstLock.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertStoredUser(second.get(5, TimeUnit.SECONDS), user);
        } finally {
            releaseFirstLock.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", user.getId());
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("账号行锁测试等待超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("账号行锁测试被中断", exception);
        }
    }

    private AppUser newUser(UserRoleEnum role) {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-18T09:10:11.123456+08:00");
        return new AppUser(id, "mapper-" + id, "mapping-test-hash", "映射测试账号", role, true,
                createdAt, createdAt.plusMinutes(1));
    }

    private void assertStoredUser(AppUser actual, AppUser expected) {
        assertThat(actual).isNotNull();
        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getUsername()).isEqualTo(expected.getUsername());
        assertThat(actual.getPasswordHash()).isEqualTo(expected.getPasswordHash());
        assertThat(actual.getDisplayName()).isEqualTo(expected.getDisplayName());
        assertThat(actual.getRole()).isEqualTo(expected.getRole());
        assertThat(actual.isEnabled()).isEqualTo(expected.isEnabled());
        // PostgreSQL 会规范化时区偏移，持久化后比较实际时间点并保留微秒精度。
        assertThat(actual.getCreatedAt().toInstant()).isEqualTo(expected.getCreatedAt().toInstant());
        assertThat(actual.getUpdatedAt().toInstant()).isEqualTo(expected.getUpdatedAt().toInstant());
    }
}
