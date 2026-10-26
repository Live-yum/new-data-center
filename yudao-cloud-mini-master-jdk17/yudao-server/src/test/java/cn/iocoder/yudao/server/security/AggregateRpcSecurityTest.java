package cn.iocoder.yudao.server.security;

import cn.iocoder.yudao.framework.security.config.AuthorizeRequestsCustomizer;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用实际 system/infra 安全配置和 Spring Security FilterChain 验证聚合模式的 RPC 边界。
 * 仅业务处理器使用无副作用的探针，不启动数据库、Redis 或实际令牌/文件写入服务。
 */
class AggregateRpcSecurityTest {

    private static final String BASIC_AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("test-admin:test-password".getBytes(StandardCharsets.UTF_8));

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(SecurityTestConfiguration.class);

    @Test
    void aggregateModeRejectsSystemAndInfraRpcEvenForAuthenticatedUsers() {
        contextRunner.withPropertyValues("yudao.rpc.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context.getSourceApplicationContext());
            for (boolean authenticated : List.of(false, true)) {
                for (MockHttpServletRequestBuilder request : rpcRequests()) {
                    if (authenticated) {
                        request.header(HttpHeaders.AUTHORIZATION, BASIC_AUTH);
                    }
                    mvc.perform(request).andExpect(status().isForbidden());
                }
            }
            assertThat(context.getBean(RpcProbeController.class).rpcInvocations).hasValue(0);

            // 对照：认证有效，普通管理端路由仍可访问；不能把所有请求拒绝当作测试通过。
            mvc.perform(get("/admin-api/protected")).andExpect(status().isForbidden());
            mvc.perform(get("/admin-api/protected").header(HttpHeaders.AUTHORIZATION, BASIC_AUTH))
                    .andExpect(status().isOk()).andExpect(content().string("test-admin"));
        });
    }

    @Test
    void missingRpcFlagPreservesIndependentMicroserviceBehavior() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context.getSourceApplicationContext());
            for (MockHttpServletRequestBuilder request : rpcRequests()) {
                mvc.perform(request).andExpect(status().isOk()).andExpect(content().string("rpc-probe"));
            }
            assertThat(context.getBean(RpcProbeController.class).rpcInvocations).hasValue(4);
            mvc.perform(get("/admin-api/protected")).andExpect(status().isForbidden());
        });
    }

    @Test
    void explicitRpcFlagPreservesIndependentMicroserviceBehavior() {
        contextRunner.withPropertyValues("yudao.rpc.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context.getSourceApplicationContext());
            for (MockHttpServletRequestBuilder request : rpcRequests()) {
                mvc.perform(request).andExpect(status().isOk());
            }
            assertThat(context.getBean(RpcProbeController.class).rpcInvocations).hasValue(4);
        });
    }

    private static List<MockHttpServletRequestBuilder> rpcRequests() {
        return List.of(
                get("/rpc-api/system/tenant/id-list"),
                post("/rpc-api/system/oauth2/token/create"),
                get("/rpc-api/infra/file/presigned-url"),
                post("/rpc-api/infra/file/create"));
    }

    private static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import({
            cn.iocoder.yudao.module.system.framework.security.config.SecurityConfiguration.class,
            cn.iocoder.yudao.module.infra.framework.security.config.SecurityConfiguration.class
    })
    static class SecurityTestConfiguration {

        @Bean
        WebProperties webProperties() {
            return new WebProperties();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return new InMemoryUserDetailsManager(User.withUsername("test-admin")
                    .password("{noop}test-password").roles("ADMIN").build());
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http,
                                               List<AuthorizeRequestsCustomizer> customizers) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(registry -> {
                        customizers.forEach(customizer -> customizer.customize(registry));
                        registry.anyRequest().authenticated();
                    })
                    .httpBasic(Customizer.withDefaults())
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN)));
            return http.build();
        }

        @Bean
        RpcProbeController rpcProbeController() {
            return new RpcProbeController();
        }

    }

    @RestController
    static class RpcProbeController {

        private final AtomicInteger rpcInvocations = new AtomicInteger();

        @RequestMapping({
                "/rpc-api/system/tenant/id-list", "/rpc-api/system/oauth2/token/create",
                "/rpc-api/infra/file/presigned-url", "/rpc-api/infra/file/create"
        })
        String rpc() {
            rpcInvocations.incrementAndGet();
            return "rpc-probe";
        }

        @GetMapping("/admin-api/protected")
        String protectedApi(Authentication authentication) {
            return authentication.getName();
        }

    }

}
