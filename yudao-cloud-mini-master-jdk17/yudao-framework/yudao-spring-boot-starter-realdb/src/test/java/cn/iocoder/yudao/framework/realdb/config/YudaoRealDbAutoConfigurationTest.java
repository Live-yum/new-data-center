package cn.iocoder.yudao.framework.realdb.config;

import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class YudaoRealDbAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(YudaoRealDbAutoConfiguration.class));

    @Test
    void disabledByDefaultWithoutAnyUrlOrCredentials() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(RealDbClient.class));
    }

    @Test
    void enabledCreatesClientWithoutEagerLoginOrNetworkAccess() {
        contextRunner.withPropertyValues("yudao.realdb.enabled=true", "yudao.realdb.base-url=http://127.0.0.1:1")
                .run(context -> assertThat(context).hasSingleBean(RealDbClient.class));
    }

    @Test
    void missingUrlIsRejectedWhenExplicitlyEnabled() {
        contextRunner.withPropertyValues("yudao.realdb.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

}
