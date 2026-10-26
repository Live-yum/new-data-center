package cn.iocoder.yudao.framework.mybatis;

import cn.iocoder.yudao.framework.mybatis.config.YudaoMybatisAutoConfiguration;
import cn.iocoder.yudao.framework.mybatis.core.util.JdbcUtils;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.incrementer.KingbaseKeyGenerator;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外部 PostgreSQL / KingbaseES 回归测试，仅使用会话级临时表，连接关闭后自动清理。
 */
@EnabledIfEnvironmentVariable(named = "DATABASE_TEST_URL", matches = ".+")
class DatabaseCompatibilityTest {

    @Test
    void shouldSupportSequenceBooleanPaginationLogicalAndBoundedPhysicalDelete() throws Exception {
        String url = System.getenv("DATABASE_TEST_URL");
        try (Connection connection = DriverManager.getConnection(url,
                System.getenv().getOrDefault("DATABASE_TEST_USERNAME", "yudao"),
                System.getenv().getOrDefault("DATABASE_TEST_PASSWORD", ""))) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TEMP SEQUENCE yudao_database_compatibility_test_seq START 1");
                statement.execute("CREATE TEMP TABLE yudao_database_compatibility_test ("
                        + "id BIGINT PRIMARY KEY, enabled SMALLINT, native_enabled BOOLEAN, deleted SMALLINT DEFAULT 0, content BYTEA)");
            }
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setEnvironment(new Environment("database-test", new JdbcTransactionFactory(),
                    new SingleConnectionDataSource(connection, true)));
            configuration.setMapUnderscoreToCamelCase(true);
            new YudaoMybatisAutoConfiguration().numericBooleanConfigurationCustomizer().customize(configuration);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT)
                    .setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                    .setKeyGenerators(List.of(url.startsWith("jdbc:kingbase8:")
                            ? new KingbaseKeyGenerator() : new PostgreKeyGenerator())));
            GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor(JdbcUtils.getDbType(url)));
            configuration.addInterceptor(interceptor);
            configuration.addMapper(CompatibilityMapper.class);

            try (SqlSession session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession()) {
                CompatibilityMapper mapper = session.getMapper(CompatibilityMapper.class);
                CompatibilityDO first = new CompatibilityDO();
                first.setEnabled(true);
                first.setNativeEnabled(true);
                first.setDeleted(false);
                first.setContent(new byte[]{0, 1, -1});
                assertEquals(1, mapper.insert(first));
                CompatibilityDO second = new CompatibilityDO();
                second.setEnabled(false);
                second.setNativeEnabled(false);
                assertEquals(1, mapper.insert(second));
                assertTrue(second.getId() > first.getId());
                assertTrue(mapper.selectById(first.getId()).getEnabled());
                assertTrue(mapper.selectById(first.getId()).getNativeEnabled());
                assertEquals(1, mapper.selectCount(new QueryWrapper<CompatibilityDO>().lambda()
                        .eq(CompatibilityDO::getEnabled, true).eq(CompatibilityDO::getNativeEnabled, true)));
                assertArrayEquals(first.getContent(), mapper.selectById(first.getId()).getContent());
                Page<CompatibilityDO> page = mapper.selectPage(new Page<>(1, 1), new QueryWrapper<CompatibilityDO>().orderByAsc("id"));
                assertEquals(2, page.getTotal());
                assertEquals(1, page.getRecords().size());
                first.setEnabled(false);
                first.setNativeEnabled(false);
                assertEquals(1, mapper.updateById(first));
                assertEquals(2, mapper.selectCount(new QueryWrapper<CompatibilityDO>().eq("enabled", false)));
                assertEquals(2, mapper.selectCount(new QueryWrapper<CompatibilityDO>().lambda()
                        .eq(CompatibilityDO::getEnabled, false).eq(CompatibilityDO::getNativeEnabled, false)));
                assertEquals(1, mapper.deleteById(first.getId()));
                assertNull(mapper.selectById(first.getId()));
                assertEquals(1, mapper.selectCount(null));
                assertEquals(1, mapper.deletePhysically(1));
                try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM yudao_database_compatibility_test");
                     ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt(1));
                }
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
            }
        }
    }

    public interface CompatibilityMapper extends BaseMapper<CompatibilityDO> {

        @Delete("DELETE FROM yudao_database_compatibility_test WHERE id IN (SELECT id FROM ("
                + "SELECT id FROM yudao_database_compatibility_test ORDER BY id LIMIT #{limit}) expired_records)")
        int deletePhysically(@Param("limit") int limit);

    }

    @Data
    @TableName(value = "yudao_database_compatibility_test", autoResultMap = true)
    @KeySequence("yudao_database_compatibility_test_seq")
    public static class CompatibilityDO {

        @TableId
        private Long id;
        private Boolean enabled;
        private Boolean nativeEnabled;
        @TableLogic
        private Boolean deleted;
        private byte[] content;

    }

}
