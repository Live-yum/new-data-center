package cn.iocoder.yudao.framework.tenant.config;

import cn.iocoder.yudao.framework.tenant.core.rpc.TenantRequestInterceptor;
import cn.iocoder.yudao.framework.common.biz.system.tenant.TenantCommonApi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "yudao", name = {"tenant.enable", "rpc.enabled"}, matchIfMissing = true)
@EnableFeignClients(clients = TenantCommonApi.class) // 主要是引入相关的 API 服务
public class YudaoTenantRpcAutoConfiguration {

    @Bean
    public TenantRequestInterceptor tenantRequestInterceptor() {
        return new TenantRequestInterceptor();
    }

}
