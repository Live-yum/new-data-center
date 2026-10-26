package cn.iocoder.yudao.module.iot;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.config.AuthorizeRequestsCustomizer;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.config.YudaoWebSecurityConfigurerAdapter;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.security.core.handler.AuthenticationEntryPointImpl;
import cn.iocoder.yudao.framework.security.core.handler.AccessDeniedHandlerImpl;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.iot.config.IotSecurityConfiguration;
import cn.iocoder.yudao.module.iot.controller.admin.IotController;
import cn.iocoder.yudao.module.iot.service.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Uses the actual shared filter-chain factory, not a replacement test SecurityFilterChain. */
class IotStandaloneSecurityConfigurationTest {
    @Test void standaloneSharedSecurityStartsWithoutOtherModulesAndRejectsAnonymousIdentity() {
        new WebApplicationContextRunner().withAllowBeanDefinitionOverriding(false)
                .withUserConfiguration(WebSlice.class, IotSecurityConfiguration.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(AuthorizeRequestsCustomizer.class)).hasSize(1);
                    var mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
                    mvc.perform(get("/admin-api/iot/devices").servletPath("/admin-api/iot/devices"))
                            .andExpect(jsonPath("$.code").value(401));
                    mvc.perform(get("/admin-api/iot/devices").servletPath("/admin-api/iot/devices")
                                    .header("login-user", "{\"id\":1,\"userType\":2,\"tenantId\":1}")
                                    .header("tenant-id", "1"))
                            .andExpect(jsonPath("$.code").value(401));
                    mvc.perform(get("/actuator/health").servletPath("/actuator/health"))
                            .andExpect(jsonPath("$.code").value(401));
                    verifyNoInteractions(context.getBean(IotRepository.class), context.getBean(OAuth2TokenCommonApi.class));
                });
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import({YudaoWebSecurityConfigurerAdapter.class, IotController.class})
    static class WebSlice implements WebMvcConfigurer {
        @Override public void configurePathMatch(PathMatchConfigurer configurer) {
            configurer.addPathPrefix("/admin-api", type -> type == IotController.class);
        }
        @Bean WebProperties webProperties() { return new WebProperties(); }
        @Bean WebFrameworkUtils webUtils(WebProperties properties) { return new WebFrameworkUtils(properties); }
        @Bean SecurityProperties securityProperties() {
            SecurityProperties properties = new SecurityProperties();
            properties.setMockEnable(false); properties.setTrustLoginUserHeader(false);
            return properties;
        }
        @Bean AuthenticationEntryPoint authenticationEntryPoint() { return new AuthenticationEntryPointImpl(); }
        @Bean AccessDeniedHandler accessDeniedHandler() { return new AccessDeniedHandlerImpl(); }
        @Bean OAuth2TokenCommonApi tokens() { return mock(OAuth2TokenCommonApi.class); }
        @Bean TokenAuthenticationFilter authenticationTokenFilter(SecurityProperties properties,
                OAuth2TokenCommonApi tokens, WebFrameworkUtils webUtils) {
            return new TokenAuthenticationFilter(properties,
                    new GlobalExceptionHandler("iot-standalone-security-test", dto -> CommonResult.success(true)), tokens);
        }
        @Bean IotRepository repository() { return mock(IotRepository.class); }
        @Bean IotQueryService query() { return mock(IotQueryService.class); }
        @Bean IotExportService exports() { return mock(IotExportService.class); }
        @Bean(name = "ss") SecurityFrameworkService permissions() { return mock(SecurityFrameworkService.class); }
    }
}
