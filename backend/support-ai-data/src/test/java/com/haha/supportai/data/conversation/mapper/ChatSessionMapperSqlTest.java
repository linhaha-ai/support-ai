package com.haha.supportai.data.conversation.mapper;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSessionMapperSqlTest {

    @Test
    void pageQueryRestrictsOwnerAndUsesStableKeysetOrder() throws NoSuchMethodException {
        String sql = selectSql("selectOwnedPage", UUID.class, OffsetDateTime.class, UUID.class, int.class);

        assertThat(sql)
                .contains("user_id = #{userId,jdbcType=OTHER}")
                .contains("deleted_at IS NULL")
                .contains("updated_at &lt; #{beforeUpdatedAt}")
                .contains("updated_at = #{beforeUpdatedAt} AND id &lt; #{beforeId,jdbcType=OTHER}")
                .contains("ORDER BY updated_at DESC, id DESC")
                .contains("LIMIT #{limit}");
    }

    @Test
    void titleAndDeleteUpdatesKeepOwnerAndUndeletedConditions() throws NoSuchMethodException {
        String titleSql = updateSql("updateOwnedTitle", UUID.class, UUID.class, String.class,
                OffsetDateTime.class);
        String deleteSql = updateSql("softDeleteOwned", UUID.class, UUID.class, OffsetDateTime.class);

        assertOwnedUndeleted(titleSql);
        assertOwnedUndeleted(deleteSql);
        assertThat(titleSql).contains("SET title = #{title}");
        assertThat(deleteSql).contains("SET deleted_at = #{deletedAt}");
    }

    private void assertOwnedUndeleted(String sql) {
        assertThat(sql)
                .contains("id = #{sessionId,jdbcType=OTHER}")
                .contains("user_id = #{userId,jdbcType=OTHER}")
                .contains("deleted_at IS NULL");
    }

    private String selectSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ChatSessionMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Select.class).value());
    }

    private String updateSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ChatSessionMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Update.class).value());
    }
}
