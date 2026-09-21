package com.haha.supportai.storage;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import com.haha.supportai.ai.model.ValidatedEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PgVectorDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @Test
    void migratesEmptyDatabaseIdempotentlyAndStoresOnlyValidated1024Vectors() {
        var productionMigrations = PostgresFixture.migrations(DATABASE, false);
        assertThat(productionMigrations.migrate().migrationsExecuted).isGreaterThanOrEqualTo(10);
        assertThat(productionMigrations.migrate().migrationsExecuted).isZero();
        var jdbc = PostgresFixture.jdbc(DATABASE);
        assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class))
                .startsWith("0.8.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'acceptance_%'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'app_user'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'document_chunk'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'vector_store'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT username FROM app_user ORDER BY username", String.class))
                .containsExactly("admin");
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE username = 'admin'", String.class))
                .isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT enabled FROM app_user WHERE username = 'admin'", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_name IN ('knowledge_base', 'kb_member')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.table_constraints
                WHERE table_name = 'knowledge_base'
                  AND constraint_name IN ('ck_knowledge_base_name_not_blank', 'ck_knowledge_base_status',
                                          'ck_knowledge_base_revision_non_negative', 'fk_knowledge_base_owner')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.table_constraints
                WHERE table_name = 'kb_member'
                  AND constraint_name IN ('pk_kb_member', 'ck_kb_member_permission', 'fk_kb_member_knowledge_base',
                                          'fk_kb_member_user', 'fk_kb_member_granted_by')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_constraint
                WHERE conname IN ('fk_knowledge_base_owner', 'fk_kb_member_knowledge_base', 'fk_kb_member_user',
                                  'fk_kb_member_granted_by')
                  AND confdeltype = 'a'
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT obj_description('knowledge_base'::regclass)", String.class))
                .isEqualTo("知识库表，保存知识空间元数据、负责人、状态和并发修订号");
        assertThat(jdbc.queryForObject("""
                SELECT col_description('kb_member'::regclass,
                    (SELECT attnum FROM pg_attribute WHERE attrelid = 'kb_member'::regclass AND attname = 'permission'))
                """, String.class)).isEqualTo("成员权限：READ 可读取，WRITE 可维护资料");
        assertThat(jdbc.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE tablename IN ('knowledge_base', 'kb_member')
                """, String.class)).contains(
                        "idx_knowledge_base_status_updated_at",
                        "idx_knowledge_base_owner_id",
                        "idx_kb_member_user_id_kb_id");
        assertThat(PostgresFixture.migrations(DATABASE, true).migrate().migrationsExecuted).isEqualTo(1);

        AtomicInteger calls = new AtomicInteger();
        EmbeddingModel deterministic = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                calls.incrementAndGet();
                return new EmbeddingResponse(IntStream.range(0, request.getInstructions().size())
                        .mapToObj(index -> {
                            float[] vector = new float[1024];
                            vector[0] = 1;
                            return new Embedding(vector, index);
                        }).toList());
            }

            @Override
            public float[] embed(Document document) {
                throw new UnsupportedOperationException();
            }
        };
        var store = PgVectorStore.builder(jdbc, new ValidatedEmbeddingModel(deterministic, false))
                .vectorTableName("acceptance_ollama_vectors").dimensions(1024).initializeSchema(false).build();
        store.afterPropertiesSet();
        var document = new Document("订单可在发货前取消");
        store.add(List.of(document));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(store.similaritySearch(SearchRequest.builder().query("取消订单").topK(1).build()))
                .extracting(Document::getId).containsExactly(document.getId());
        assertThat(calls.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM acceptance_dashscope_vectors", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO acceptance_ollama_vectors VALUES (gen_random_uuid(), 'bad', '{}', '[1,2,3]')"))
                .isInstanceOf(DataAccessException.class);
    }
}
