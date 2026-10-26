package cn.iocoder.yudao.server.database;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在实际服务的依赖范围内验证 JDBC 驱动的传递依赖和自动注册，不建立数据库连接。
 * 不能放在直接声明驱动依赖的 MyBatis starter 内，否则无法发现 optional 导致的驱动缺失。
 */
class JdbcDriverAvailabilityTest {

    @ParameterizedTest
    @CsvSource({
            "jdbc:mysql://localhost:3306/codegen, com.mysql.cj.jdbc.Driver",
            "jdbc:postgresql://localhost:5432/test, org.postgresql.Driver",
            "jdbc:kingbase8://localhost:54321/test, com.kingbase8.Driver"
    })
    void shouldDiscoverTransitiveJdbcDrivers(String url, String expectedDriver) throws SQLException {
        Driver driver = DriverManager.getDriver(url);
        assertEquals(expectedDriver, driver.getClass().getName());
        assertTrue(driver.acceptsURL(url));
    }

}
