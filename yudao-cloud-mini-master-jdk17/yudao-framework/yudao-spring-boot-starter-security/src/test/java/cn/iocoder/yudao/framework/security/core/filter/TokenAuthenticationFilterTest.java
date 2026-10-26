package cn.iocoder.yudao.framework.security.core.filter;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实 TokenAuthenticationFilter 和 Spring Security 链验证身份头的信任边界。
 * 仅令牌校验 API 使用替身，不依赖数据库、Redis 或网关；WebSocket 路径使用受保护的握手探针。
 */
class TokenAuthenticationFilterTest {

    private static final String TRUST_HEADER_PROPERTY = "yudao.security.trust-login-user-header";
    private static final List<String> PROTECTED_PATHS = List.of("/admin-api/protected", "/infra/ws");
    private static final List<String> MALFORMED_HEADERS = List.of("%invalid-url-escape", "{");
    private static final String IDENTITY_HEADER = URLEncoder.encode(
            "{\"id\":1,\"userType\":2,\"tenantId\":1,\"visitTenantId\":2}", StandardCharsets.UTF_8);
    private static final String TOKEN_IDENTITY = "42:2:7:null";
    private static final String HEADER_IDENTITY = "1:2:1:2";

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(SecurityTestConfiguration.class);

    @Test
    void untrustedHeaderCannotAuthenticateApiOrWebSocketRequests() {
        contextRunner.withPropertyValues(TRUST_HEADER_PROPERTY + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context.getSourceApplicationContext());
            for (String path : PROTECTED_PATHS) {
                mvc.perform(request(path).header(SecurityFrameworkUtils.LOGIN_USER_HEADER, IDENTITY_HEADER))
                        .andExpect(status().isUnauthorized());
            }
            assertThat(context.getBean(IdentityProbeController.class).invocations).hasValue(0);
            verifyNoInteractions(context.getBean(OAuth2TokenCommonApi.class));
        });
    }

    @Test
    void bearerTokenIdentityOverridesUntrustedHeader() {
        contextRunner.withPropertyValues(TRUST_HEADER_PROPERTY + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context.getSourceApplicationContext());
            for (String path : PROTECTED_PATHS) {
                mvc.perform(request(path).header(SecurityFrameworkUtils.LOGIN_USER_HEADER, IDENTITY_HEADER)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                        .andExpect(status().isOk()).andExpect(content().string(TOKEN_IDENTITY));
            }
            assertThat(context.getBean(IdentityProbeController.class).invocations).hasValue(2);
            OAuth2TokenCommonApi tokenApi = context.getBean(OAuth2TokenCommonApi.class);
            verify(tokenApi, times(2)).checkAccessToken("valid-token");
            verifyNoMoreInteractions(tokenApi);
        });
    }

    @Test
    void invalidBearerCannotBeRescuedByUntrustedHeader() {
        contextRunner.withPropertyValues(TRUST_HEADER_PROPERTY + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context.getSourceApplicationContext());
            for (String path : PROTECTED_PATHS) {
                mvc.perform(request(path).header(SecurityFrameworkUtils.LOGIN_USER_HEADER, IDENTITY_HEADER)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                        .andExpect(status().isUnauthorized());
            }
            assertThat(context.getBean(IdentityProbeController.class).invocations).hasValue(0);
            OAuth2TokenCommonApi tokenApi = context.getBean(OAuth2TokenCommonApi.class);
            verify(tokenApi, times(2)).checkAccessToken("invalid-token");
            verifyNoMoreInteractions(tokenApi);
        });
    }

    @Test
    void malformedUntrustedHeaderIsIgnoredForAnonymousRequests() {
        contextRunner.withPropertyValues(TRUST_HEADER_PROPERTY + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context.getSourceApplicationContext());
            for (String path : PROTECTED_PATHS) {
                for (String malformedHeader : MALFORMED_HEADERS) {
                    mvc.perform(request(path).header(SecurityFrameworkUtils.LOGIN_USER_HEADER, malformedHeader))
                            .andExpect(status().isUnauthorized());
                }
            }
            assertThat(context.getBean(IdentityProbeController.class).invocations).hasValue(0);
            verifyNoInteractions(context.getBean(OAuth2TokenCommonApi.class));
        });
    }

    @Test
    void malformedUntrustedHeaderDoesNotBlockBearerAuthentication() {
        contextRunner.withPropertyValues(TRUST_HEADER_PROPERTY + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context.getSourceApplicationContext());
            for (String path : PROTECTED_PATHS) {
                for (String malformedHeader : MALFORMED_HEADERS) {
                    mvc.perform(request(path).header(SecurityFrameworkUtils.LOGIN_USER_HEADER, malformedHeader)
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                            .andExpect(status().isOk()).andExpect(content().string(TOKEN_IDENTITY));
                }
            }
            assertThat(context.getBean(IdentityProbeController.class).invocations).hasValue(4);
            OAuth2TokenCommonApi tokenApi = context.getBean(OAuth2TokenCommonApi.class);
            verify(tokenApi, times(4)).checkAccessToken("valid-token");
            verifyNoMoreInteractions(tokenApi);
        });
    }

    @Test
    void webSocketTokenQueryAuthenticatesWithoutTrustingHeader() {
        contextRunner.withPropertyValues(TRUST_HEADER_PROPERTY + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(request("/infra/ws").param("token", "valid-token")
                            .header(SecurityFrameworkUtils.LOGIN_USER_HEADER, IDENTITY_HEADER))
                    .andExpect(status().isOk()).andExpect(content().string(TOKEN_IDENTITY));
            assertThat(context.getBean(IdentityProbeController.class).invocations).hasValue(1);
            OAuth2TokenCommonApi tokenApi = context.getBean(OAuth2TokenCommonApi.class);
            verify(tokenApi).checkAccessToken("valid-token");
            verifyNoMoreInteractions(tokenApi);
        });
    }

    @Test
    void missingHeaderTrustFlagPreservesTrustedGatewayIdentity() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SecurityProperties.class).getTrustLoginUserHeader()).isTrue();
            assertTrustedGatewayIdentity(context.getSourceApplicationContext());
        });
    }

    @Test
    void explicitHeaderTrustFlagPreservesTrustedGatewayIdentity() {
        contextRunner.withPropertyValues(TRUST_HEADER_PROPERTY + "=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertTrustedGatewayIdentity(context.getSourceApplicationContext());
        });
    }

    private static void assertTrustedGatewayIdentity(WebApplicationContext context) throws Exception {
        MockMvc mvc = mvc(context);
        for (String path : PROTECTED_PATHS) {
            mvc.perform(request(path).header(SecurityFrameworkUtils.LOGIN_USER_HEADER, IDENTITY_HEADER))
                    .andExpect(status().isOk()).andExpect(content().string(HEADER_IDENTITY));
        }
        assertThat(context.getBean(IdentityProbeController.class).invocations).hasValue(2);
        verifyNoInteractions(context.getBean(OAuth2TokenCommonApi.class));
    }

    private static MockHttpServletRequestBuilder request(String path) {
        // 生产过滤器通过 servletPath 判断管理端用户类型，测试保留这一实际前提。
        return get(path).servletPath(path);
    }

    private static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @EnableConfigurationProperties(SecurityProperties.class)
    static class SecurityTestConfiguration {

        @Bean
        WebFrameworkUtils webFrameworkUtils() {
            return new WebFrameworkUtils(new WebProperties());
        }

        @Bean
        OAuth2TokenCommonApi oauth2TokenCommonApi() {
            OAuth2TokenCommonApi tokenApi = mock(OAuth2TokenCommonApi.class);
            when(tokenApi.checkAccessToken("valid-token")).thenReturn(CommonResult.success(
                    new OAuth2AccessTokenCheckRespDTO().setUserId(42L).setUserType(2).setTenantId(7L)));
            when(tokenApi.checkAccessToken("invalid-token")).thenReturn(CommonResult.error(401, "invalid token"));
            return tokenApi;
        }

        @Bean
        TokenAuthenticationFilter tokenAuthenticationFilter(SecurityProperties properties,
                                                            OAuth2TokenCommonApi tokenApi,
                                                            WebFrameworkUtils webFrameworkUtils) {
            return new TokenAuthenticationFilter(properties,
                    new GlobalExceptionHandler("security-test", dto -> CommonResult.success(true)), tokenApi);
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthenticationFilter filter) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(registry -> registry.anyRequest().authenticated())
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        IdentityProbeController identityProbeController() {
            return new IdentityProbeController();
        }

    }

    @RestController
    static class IdentityProbeController {

        private final AtomicInteger invocations = new AtomicInteger();

        @GetMapping({"/admin-api/protected", "/infra/ws"})
        String identity(Authentication authentication) {
            invocations.incrementAndGet();
            LoginUser user = (LoginUser) authentication.getPrincipal();
            return user.getId() + ":" + user.getUserType() + ":" + user.getTenantId() + ":" + user.getVisitTenantId();
        }

    }

}
