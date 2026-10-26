package cn.iocoder.yudao.framework.mybatis.config;

import cn.iocoder.yudao.framework.mybatis.core.enums.DbTypeEnum;
import cn.iocoder.yudao.framework.mybatis.core.handler.NumericBooleanTypeHandler;
import cn.iocoder.yudao.framework.mybatis.core.util.JdbcUtils;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigurationTest {

    @Test
    void shouldShipBothJdbcDriversAndDetectTheirProtocols() throws Exception {
        assertEquals(DbType.POSTGRE_SQL, JdbcUtils.getDbType("jdbc:postgresql://localhost/test"));
        assertEquals(DbType.KINGBASE_ES, JdbcUtils.getDbType("jdbc:kingbase8://localhost/test"));
        assertEquals("org.postgresql.Driver", DriverManager.getDriver("jdbc:postgresql://localhost/test").getClass().getName());
        assertEquals("com.kingbase8.Driver", DriverManager.getDriver("jdbc:kingbase8://localhost/test").getClass().getName());
    }

    @Test
    void shouldHandleProductNamesWithoutNullPointerException() {
        assertEquals(DbType.KINGBASE_ES, DbTypeEnum.find(" KingbaseES "));
        assertEquals(DbType.POSTGRE_SQL, DbTypeEnum.find("postgresql"));
        assertNull(DbTypeEnum.find("Unknown database"));
        assertNull(DbTypeEnum.find(null));
    }

    @Test
    void shouldUseSequenceIdsWithDefaultMasterDataSource() {
        for (String protocol : new String[]{"postgresql", "kingbase8"}) {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("spring.datasource.dynamic.datasource.master.url", "jdbc:" + protocol + "://localhost/test");
            IdTypeEnvironmentPostProcessor processor = new IdTypeEnvironmentPostProcessor();
            processor.postProcessEnvironment(environment, null);
            assertEquals(IdType.INPUT, processor.getIdType(environment));
            assertEquals("org.quartz.impl.jdbcjobstore.PostgreSQLDelegate", environment.getProperty(
                    "spring.quartz.properties.org.quartz.jobStore.driverDelegateClass"));
        }
    }

    @Test
    void shouldPreserveExplicitIdAndQuartzSettings() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.dynamic.primary", "main")
                .withProperty("spring.datasource.dynamic.datasource.main.url", "jdbc:kingbase8://localhost/test")
                .withProperty("mybatis-plus.global-config.db-config.id-type", "ASSIGN_ID")
                .withProperty("spring.quartz.properties.org.quartz.jobStore.driverDelegateClass", "custom.Delegate");
        IdTypeEnvironmentPostProcessor processor = new IdTypeEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, null);
        assertEquals(IdType.ASSIGN_ID, processor.getIdType(environment));
        assertEquals("custom.Delegate", environment.getProperty(
                "spring.quartz.properties.org.quartz.jobStore.driverDelegateClass"));
    }

    @Test
    void shouldRegisterNumericBooleanForWrapperAndPrimitive() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        new YudaoMybatisAutoConfiguration().numericBooleanConfigurationCustomizer().customize(configuration);
        assertInstanceOf(NumericBooleanTypeHandler.class, configuration.getTypeHandlerRegistry().getTypeHandler(Boolean.class));
        assertInstanceOf(NumericBooleanTypeHandler.class, configuration.getTypeHandlerRegistry().getTypeHandler(boolean.class));
    }

}
