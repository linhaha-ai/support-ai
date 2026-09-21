package com.haha.supportai.data.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(float[].class)
@MappedJdbcTypes(value = JdbcType.VARCHAR, includeNullJdbcType = true)
public class VectorTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, float[] value, JdbcType jdbcType)
            throws SQLException {
        statement.setString(index, toVectorLiteral(value));
    }

    @Override
    public float[] getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return toFloatArray(resultSet.getString(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return toFloatArray(resultSet.getString(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return toFloatArray(statement.getString(columnIndex));
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 12 + 2);
        literal.append('[');
        for (int index = 0; index < vector.length; index++) {
            if (!Float.isFinite(vector[index])) {
                throw new IllegalArgumentException("嵌入向量存在非有限数值");
            }
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }

    private float[] toFloatArray(String literal) {
        if (literal == null) {
            return null;
        }
        String trimmedLiteral = literal.trim();
        if (trimmedLiteral.length() < 2 || trimmedLiteral.charAt(0) != '['
                || trimmedLiteral.charAt(trimmedLiteral.length() - 1) != ']') {
            throw new IllegalArgumentException("pgvector 返回值格式不合法");
        }
        String values = trimmedLiteral.substring(1, trimmedLiteral.length() - 1).trim();
        if (values.isEmpty()) {
            return new float[0];
        }
        String[] parts = values.split(",");
        float[] vector = new float[parts.length];
        for (int index = 0; index < parts.length; index++) {
            vector[index] = Float.parseFloat(parts[index]);
            if (!Float.isFinite(vector[index])) {
                throw new IllegalArgumentException("pgvector 返回值存在非有限数值");
            }
        }
        return vector;
    }
}
