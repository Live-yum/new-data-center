package cn.iocoder.yudao.framework.mybatis.core.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * 兼容业务表混用 SMALLINT 和 BOOLEAN 保存布尔值。
 *
 * Wrapper 的布尔参数不一定携带字段注解，因此以 JDBC 参数类型选择绑定方式，
 * 避免将所有 Boolean 无条件转换成数字而破坏原生 BOOLEAN 列。
 *
 * @author 芋道源码
 */
public class NumericBooleanTypeHandler extends BaseTypeHandler<Boolean> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Boolean parameter, JdbcType jdbcType)
            throws SQLException {
        int parameterType = jdbcType != null ? jdbcType.TYPE_CODE : ps.getParameterMetaData().getParameterType(i);
        switch (parameterType) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.NUMERIC:
            case Types.DECIMAL:
                ps.setShort(i, (short) (parameter ? 1 : 0));
                break;
            case Types.BOOLEAN:
            case Types.BIT:
                ps.setBoolean(i, parameter);
                break;
            default:
                throw new SQLException("布尔参数的 JDBC 类型不是 BOOLEAN 或数字类型：" + parameterType);
        }
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, String columnName) throws SQLException {
        boolean value = rs.getBoolean(columnName);
        return rs.wasNull() ? null : value;
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        boolean value = rs.getBoolean(columnIndex);
        return rs.wasNull() ? null : value;
    }

    @Override
    public Boolean getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        boolean value = cs.getBoolean(columnIndex);
        return cs.wasNull() ? null : value;
    }

}
