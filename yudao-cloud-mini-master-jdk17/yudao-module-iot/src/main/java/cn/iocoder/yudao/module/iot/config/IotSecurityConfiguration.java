package cn.iocoder.yudao.module.iot.config;

import cn.iocoder.yudao.framework.security.config.AuthorizeRequestsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/** Standalone IoT has no system/infra URL customizers. Supply its own authenticated-only rules. */
@Configuration(proxyBeanMethods = false)
public class IotSecurityConfiguration {
    @Bean
    public AuthorizeRequestsCustomizer iotAuthorizeRequestsCustomizer() {
        return new AuthorizeRequestsCustomizer() {
            @Override
            public void customize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
                registry.requestMatchers(buildAdminApi("/iot/**")).authenticated();
            }
        };
    }
}
