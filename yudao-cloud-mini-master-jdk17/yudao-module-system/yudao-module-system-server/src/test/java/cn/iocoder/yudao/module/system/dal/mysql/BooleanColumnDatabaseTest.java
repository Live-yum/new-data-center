package cn.iocoder.yudao.module.system.dal.mysql;

import cn.iocoder.yudao.framework.mybatis.config.YudaoMybatisAutoConfiguration;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.system.dal.dataobject.notify.NotifyMessageDO;
import cn.iocoder.yudao.module.system.dal.dataobject.sms.SmsCodeDO;
import cn.iocoder.yudao.module.system.dal.mysql.notify.NotifyMessageMapper;
import cn.iocoder.yudao.module.system.dal.mysql.sms.SmsCodeMapper;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.KingbaseKeyGenerator;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实业务 Mapper 和会话临时表，回归 BOOL 与 SMALLINT 布尔字段混用。 */
@EnabledIfEnvironmentVariable(named = "DATABASE_TEST_URL", matches = ".+")
class BooleanColumnDatabaseTest {

    @Test
    void shouldQueryAndUpdateNativeAndNumericBooleanFieldsUsingActualMappers() throws Exception {
        String url = System.getenv("DATABASE_TEST_URL");
        try (Connection connection = DriverManager.getConnection(url,
                System.getenv().getOrDefault("DATABASE_TEST_USERNAME", "yudao"),
                System.getenv().getOrDefault("DATABASE_TEST_PASSWORD", ""))) {
            connection.setAutoCommit(false);
            String schema = connection.getSchema();
            if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalStateException("测试要求 JDBC currentSchema 为单一普通 schema 名");
            }
            try (Statement statement = connection.createStatement()) {
                for (String table : List.of("system_notify_message", "system_sms_code")) {
                    statement.execute("CREATE TEMP TABLE " + table + " (LIKE " + schema + "." + table + " INCLUDING DEFAULTS)");
                    statement.execute("CREATE TEMP SEQUENCE " + table + "_seq START 1");
                }
            }
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setEnvironment(new Environment("boolean-test", new JdbcTransactionFactory(),
                    new SingleConnectionDataSource(connection, true)));
            configuration.setMapUnderscoreToCamelCase(true);
            new YudaoMybatisAutoConfiguration().numericBooleanConfigurationCustomizer().customize(configuration);
            GlobalConfig config = new GlobalConfig();
            config.setMetaObjectHandler(new DefaultDBFieldHandler());
            config.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT)
                    .setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                    .setKeyGenerators(List.of(url.startsWith("jdbc:kingbase8:")
                            ? new KingbaseKeyGenerator() : new PostgreKeyGenerator())));
            GlobalConfigUtils.setGlobalConfig(configuration, config);
            configuration.addMapper(NotifyMessageMapper.class);
            configuration.addMapper(SmsCodeMapper.class);
            try (SqlSession session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession()) {
                NotifyMessageMapper notifyMapper = session.getMapper(NotifyMessageMapper.class);
                NotifyMessageDO message = new NotifyMessageDO().setUserId(1L).setUserType(2)
                        .setTemplateId(1L).setTemplateCode("database-test").setTemplateType(1)
                        .setTemplateNickname("test").setTemplateContent("test").setTemplateParams(Map.of())
                        .setReadStatus(false);
                message.setCreateTime(LocalDateTime.now());
                message.setUpdateTime(LocalDateTime.now());
                message.setDeleted(false);
                assertEquals(1, notifyMapper.insert(message));
                assertEquals(1L, notifyMapper.selectUnreadCountByUserIdAndUserType(1L, 2));
                assertEquals(0L, notifyMapper.selectUnreadCountByUserIdAndUserType(2L, 2));
                assertEquals(1, notifyMapper.updateListRead(List.of(message.getId()), 1L, 2));
                assertTrue(notifyMapper.selectById(message.getId()).getReadStatus());
                assertEquals(0L, notifyMapper.selectUnreadCountByUserIdAndUserType(1L, 2));

                SmsCodeMapper smsMapper = session.getMapper(SmsCodeMapper.class);
                SmsCodeDO code = SmsCodeDO.builder().mobile("13800000000").code("123456")
                        .scene(1).todayIndex(1).createIp("127.0.0.1").used(false).build();
                code.setCreateTime(LocalDateTime.now());
                code.setUpdateTime(LocalDateTime.now());
                code.setDeleted(false);
                assertEquals(1, smsMapper.insert(code));
                assertEquals(1L, smsMapper.selectCount(new LambdaQueryWrapperX<SmsCodeDO>()
                        .eq(SmsCodeDO::getUsed, false).eq(SmsCodeDO::getDeleted, false)));
                code.setUsed(true);
                assertEquals(1, smsMapper.updateById(code));
                assertTrue(smsMapper.selectById(code.getId()).getUsed());
                assertEquals(1L, smsMapper.selectCount(new LambdaQueryWrapperX<SmsCodeDO>().eq(SmsCodeDO::getUsed, true)));
                assertEquals(0L, smsMapper.selectCount(new LambdaQueryWrapperX<SmsCodeDO>().eq(SmsCodeDO::getUsed, false)));
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
            }
        }
    }

}
