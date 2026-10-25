package cn.iocoder.yudao.framework.dict.config;

import cn.iocoder.yudao.framework.common.biz.system.dict.DictDataCommonApi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 字典用到 Feign 的配置项
 *
 * @author 芋道源码
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "yudao.rpc", name = "enabled", matchIfMissing = true)
@EnableFeignClients(clients = DictDataCommonApi.class) // 主要是引入相关的 API 服务
public class YudaoDictRpcAutoConfiguration {
}
