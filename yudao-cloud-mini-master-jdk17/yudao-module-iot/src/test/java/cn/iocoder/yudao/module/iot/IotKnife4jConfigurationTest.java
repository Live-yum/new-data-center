package cn.iocoder.yudao.module.iot;

import cn.iocoder.yudao.framework.swagger.config.Knife4jOpenApiCustomizer;
import com.github.xiaoymin.knife4j.spring.configuration.Knife4jProperties;
import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import static org.assertj.core.api.Assertions.assertThat;

class IotKnife4jConfigurationTest {
    @Test void absentOrDisabledKnife4jDoesNotRequireItsOptionalPropertiesBean() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(Knife4jOpenApiCustomizer.class);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(Knife4jOpenApiCustomizer.class);
        });
        runner.withPropertyValues("knife4j.enable=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(Knife4jOpenApiCustomizer.class);
        });
    }
    @Test void explicitlyEnabledKnife4jRetainsItsExistingCustomizer() {
        new ApplicationContextRunner().withUserConfiguration(Knife4jOpenApiCustomizer.class, Properties.class)
                .withPropertyValues("knife4j.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Knife4jOpenApiCustomizer.class);
                });
    }
    // Factory methods provide dependencies without interpreting SpringDocConfigProperties itself
    // as a conditional @Configuration (it requires the rest of SpringDoc's auto-configuration).
    @TestConfiguration(proxyBeanMethods = false)
    static class Properties {
        @Bean Knife4jProperties knife4jProperties() { return new Knife4jProperties(); }
        @Bean SpringDocConfigProperties springDocConfigProperties() { return new SpringDocConfigProperties(); }
    }

}
