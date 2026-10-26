package cn.iocoder.yudao.module.iot;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.dict.DictDataCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.tenant.TenantCommonApi;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import static org.assertj.core.api.Assertions.assertThat;

/** Real Feign registrar: same service destination must not imply the same client context. */
class IotFeignRegistrationTest {
    @Test void sharedServiceNamesHaveIndependentClientContextsWithoutBeanOverriding() {
        new ApplicationContextRunner().withAllowBeanDefinitionOverriding(false)
                .withUserConfiguration(Clients.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    for (String client : new String[]{"ApiAccessLogCommonApi", "ApiErrorLogCommonApi",
                            "DictDataCommonApi", "OperateLogCommonApi", "OAuth2TokenCommonApi",
                            "PermissionCommonApi", "TenantCommonApi"}) {
                        assertThat(context.containsBean(client + ".FeignClientSpecification")).isTrue();
                    }
                });
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableFeignClients(clients = {ApiAccessLogCommonApi.class, ApiErrorLogCommonApi.class,
            DictDataCommonApi.class, OperateLogCommonApi.class, OAuth2TokenCommonApi.class,
            PermissionCommonApi.class, TenantCommonApi.class})
    static class Clients {}
}
