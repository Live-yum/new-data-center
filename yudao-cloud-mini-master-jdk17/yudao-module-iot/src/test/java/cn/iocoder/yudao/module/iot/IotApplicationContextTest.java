package cn.iocoder.yudao.module.iot;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.tenant.TenantCommonApi;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.swagger.config.Knife4jOpenApiCustomizer;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Full standalone application/autoconfiguration, H2 storage and real security filters.
 * Only external RPC/Redis transports are mocked; no replacement chain, controller or repository. */
@SpringBootTest(classes = IotServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.dynamic.datasource.master.driver-class-name=org.h2.Driver",
                "spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:iot_full_context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.dynamic.datasource.master.username=sa",
                "spring.datasource.dynamic.datasource.master.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=file:../sql/postgresql/upgrade/20261026_iot.sql,file:../sql/postgresql/upgrade/20261027_iot_process.sql"
        })
@ActiveProfiles("iot")
@AutoConfigureMockMvc
class IotApplicationContextTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @MockitoBean OAuth2TokenCommonApi tokens;
    @MockitoBean PermissionCommonApi permissions;
    @MockitoBean TenantCommonApi tenants;
    @MockitoBean ApiAccessLogCommonApi accessLogs;
    @MockitoBean ApiErrorLogCommonApi errorLogs;
    @MockitoBean OperateLogCommonApi operationLogs;
    @MockitoBean(extraInterfaces = ReactiveRedisConnectionFactory.class) RedisConnectionFactory redis;
    @MockitoBean RedissonClient redisson;

    @Test void fullContextRespectsDisabledKnife4jAndAuthentication() throws Exception {
        assertThat(context.getBeansOfType(Knife4jOpenApiCustomizer.class)).isEmpty();
        assertThat(context.containsBean("springSecurityFilterChain")).isTrue();
        mvc.perform(get("/admin-api/iot/devices")).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/admin-api/iot/devices").header("tenant-id", "7")
                        .header("login-user", "{\"id\":42,\"userType\":2,\"tenantId\":7}"))
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(tokens);
        when(tokens.checkAccessToken("context-test-token")).thenReturn(CommonResult.success(
                new OAuth2AccessTokenCheckRespDTO().setUserId(42L).setUserType(2).setTenantId(7L)));
        when(tenants.validTenant(7L)).thenReturn(CommonResult.success(true));
        when(permissions.hasAnyPermissions(42L,"iot:device:query")).thenReturn(CommonResult.success(true));
        mvc.perform(get("/admin-api/iot/devices").header("Authorization", "Bearer context-test-token")
                        .header("tenant-id", "7"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").isArray());
        mvc.perform(get("/admin-api/iot/devices").header("Authorization", "Bearer context-test-token")
                        .header("tenant-id", "8"))
                .andExpect(jsonPath("$.code").value(403));
    }
}
