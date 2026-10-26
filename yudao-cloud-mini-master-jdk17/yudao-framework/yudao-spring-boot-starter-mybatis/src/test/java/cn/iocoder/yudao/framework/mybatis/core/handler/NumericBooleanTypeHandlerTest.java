package cn.iocoder.yudao.framework.mybatis.core.handler;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeException;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NumericBooleanTypeHandlerTest {

    private final NumericBooleanTypeHandler typeHandler = new NumericBooleanTypeHandler();

    @Test
    void shouldWriteNumericBooleansAndPreserveNull() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        typeHandler.setParameter(statement, 1, true, JdbcType.SMALLINT);
        typeHandler.setParameter(statement, 2, false, JdbcType.SMALLINT);
        typeHandler.setParameter(statement, 3, null, JdbcType.SMALLINT);
        verify(statement).setShort(1, (short) 1);
        verify(statement).setShort(2, (short) 0);
        verify(statement).setNull(3, JdbcType.SMALLINT.TYPE_CODE);
        verifyNoMoreInteractions(statement);
    }

    @Test
    void shouldReadTrueFalseAndSqlNull() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getBoolean("enabled")).thenReturn(true);
        assertTrue(typeHandler.getNullableResult(resultSet, "enabled"));
        when(resultSet.getBoolean(1)).thenReturn(false);
        assertFalse(typeHandler.getNullableResult(resultSet, 1));
        when(resultSet.wasNull()).thenReturn(true);
        assertNull(typeHandler.getNullableResult(resultSet, "enabled"));
        assertNull(typeHandler.getNullableResult(resultSet, 1));
        CallableStatement statement = mock(CallableStatement.class);
        when(statement.wasNull()).thenReturn(true);
        assertNull(typeHandler.getNullableResult(statement, 1));
    }

    @Test
    void shouldBindWrapperParametersAccordingToActualColumnTypes() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ParameterMetaData metadata = mock(ParameterMetaData.class);
        when(statement.getParameterMetaData()).thenReturn(metadata);
        when(metadata.getParameterType(1)).thenReturn(Types.BOOLEAN);
        when(metadata.getParameterType(2)).thenReturn(Types.SMALLINT);
        when(metadata.getParameterType(4)).thenReturn(Types.OTHER);
        typeHandler.setParameter(statement, 1, false, null);
        typeHandler.setParameter(statement, 2, true, null);
        typeHandler.setParameter(statement, 3, true, JdbcType.BIT);
        assertInstanceOf(SQLException.class,
                assertThrows(TypeException.class, () -> typeHandler.setParameter(statement, 4, true, null)).getCause());
        verify(statement).setBoolean(1, false);
        verify(statement).setShort(2, (short) 1);
        verify(statement).setBoolean(3, true);
        verify(statement, times(3)).getParameterMetaData();
        verifyNoMoreInteractions(statement);
    }

}
