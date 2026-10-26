package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.security.TenantSecurityWebFilter;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.web.TenantContextWebFilter;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.iot.controller.admin.IotController;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controller + real token and tenant filters; only remote services/repositories are mocked. */
class IotControllerSecurityTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("yudao.security.trust-login-user-header=false", "yudao.security.mock-enable=false");
    private static final String FORGED_USER = "{\"id\":1,\"userType\":2,\"tenantId\":999,\"visitTenantId\":7}";

    @Test
    void spoofedOrMalformedLoginUserDoesNotAuthenticate() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            for (String header : List.of(FORGED_USER, "%bad-encoding")) {
                mvc(context).perform(request().header("login-user", header)).andExpect(status().isUnauthorized());
            }
            verifyNoInteractions(context.getBean(IotRepository.class), context.getBean(OAuth2TokenCommonApi.class));
        });
    }

    @Test
    void authenticatedTokenStillNeedsDevicePermission() {
        runner.run(context -> {
            mvc(context).perform(request().header("Authorization", "Bearer valid-token"))
                    .andExpect(status().isForbidden());
            verify(context.getBean(SecurityFrameworkService.class)).hasPermission("iot:device:query");
            verifyNoInteractions(context.getBean(IotRepository.class));
        });
    }

    @Test
    void validTokenIdentityWinsOverForgedLoginUser() {
        runner.run(context -> {
            when(context.getBean(SecurityFrameworkService.class).hasPermission("iot:device:query")).thenReturn(true);
            mvc(context).perform(request().header("Authorization", "Bearer valid-token").header("login-user", FORGED_USER))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
            verify(context.getBean(IotRepository.class)).devices(7L);
            verifyNoMoreInteractions(context.getBean(IotRepository.class));
            verify(context.getBean(OAuth2TokenCommonApi.class)).checkAccessToken("valid-token");
        });
    }

    @Test
    void tokenCannotSelectAnotherTenant() {
        runner.run(context -> {
            when(context.getBean(SecurityFrameworkService.class).hasPermission("iot:device:query")).thenReturn(true);
            mvc(context).perform(get("/admin-api/iot/devices").servletPath("/admin-api/iot/devices")
                            .header("tenant-id", "999").header("Authorization", "Bearer valid-token"))
                    .andExpect(jsonPath("$.code").value(403));
            verifyNoInteractions(context.getBean(IotRepository.class));
        });
    }

    @Test
    void exportFileNeedsSeparateExportPermission() {
        runner.run(context -> {
            when(context.getBean(SecurityFrameworkService.class).hasPermission("iot:device:query")).thenReturn(true);
            mvc(context).perform(get("/admin-api/iot/exports/job/file").servletPath("/admin-api/iot/exports/job/file")
                            .header("tenant-id", "7").header("Authorization", "Bearer valid-token"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(context.getBean(IotExportService.class));
        });
    }

    private static MockHttpServletRequestBuilder request() {
        return get("/admin-api/iot/devices").servletPath("/admin-api/iot/devices").header("tenant-id", "7");
    }
    private static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new TenantContextWebFilter(), context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableMethodSecurity
    @EnableWebMvc
    @EnableConfigurationProperties(SecurityProperties.class)
    @Import(IotController.class)
    static class TestConfiguration implements WebMvcConfigurer {
        @Override public void configurePathMatch(PathMatchConfigurer configurer) {
            configurer.addPathPrefix("/admin-api", type -> type == IotController.class);
        }
        @Bean IotRepository repository() { return mock(IotRepository.class); }
        @Bean IotQueryService query() { return mock(IotQueryService.class); }
        @Bean IotExportService exports() { return mock(IotExportService.class); }
        @Bean(name = "ss") SecurityFrameworkService permissions() { return mock(SecurityFrameworkService.class); }
        @Bean WebFrameworkUtils webUtils() { return new WebFrameworkUtils(new WebProperties()); }
        @Bean OAuth2TokenCommonApi tokens() {
            OAuth2TokenCommonApi api = mock(OAuth2TokenCommonApi.class);
            when(api.checkAccessToken("valid-token")).thenReturn(CommonResult.success(
                    new OAuth2AccessTokenCheckRespDTO().setUserId(42L).setUserType(2).setTenantId(7L)));
            return api;
        }
        @Bean TokenAuthenticationFilter tokenFilter(SecurityProperties properties, OAuth2TokenCommonApi tokens,
                                                    WebFrameworkUtils webUtils) {
            return new TokenAuthenticationFilter(properties, exceptionHandler(), tokens);
        }
        private static GlobalExceptionHandler exceptionHandler() {
            return new GlobalExceptionHandler("iot-security-test", dto -> CommonResult.success(true));
        }
        @Bean SecurityFilterChain chain(HttpSecurity http, TokenAuthenticationFilter tokenFilter) throws Exception {
            TenantSecurityWebFilter tenantFilter = new TenantSecurityWebFilter(new WebProperties(), new TenantProperties(),
                    Set.of(), exceptionHandler(), mock(TenantFrameworkService.class));
            http.csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(tenantFilter, TokenAuthenticationFilter.class);
            return http.build();
        }
    }
}
