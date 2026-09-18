package com.haha.supportai.data.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, UUID value, JdbcType jdbcType)
            throws SQLException {
        // 按原生 UUID 绑定，避免字符串参数与 PostgreSQL uuid 列比较时类型不匹配。
        statement.setObject(index, value, Types.OTHER);
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getObject(columnName, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return resultSet.getObject(columnIndex, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return statement.getObject(columnIndex, UUID.class);
    }
}
