package cn.iocoder.yudao.module.infra.framework.rpc.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "infraRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients()
@ConditionalOnProperty(prefix = "yudao.rpc", name = "enabled", matchIfMissing = true)
public class RpcConfiguration {
}
