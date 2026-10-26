package cn.iocoder.yudao.module.iot;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
/** Runs inherited repository/queue contract against a dedicated real PostgreSQL database in CI. */
@EnabledIfEnvironmentVariable(named="DATABASE_TEST_URL",matches=".+")
public class IotPostgresqlTest extends IotRepositoryTest {
 @Override protected String url() { return System.getenv("DATABASE_TEST_URL"); }
 @Override protected String username() { return System.getenv().getOrDefault("DATABASE_TEST_USERNAME","yudao_test"); }
 @Override protected String password() { return System.getenv().getOrDefault("DATABASE_TEST_PASSWORD",""); }
}
