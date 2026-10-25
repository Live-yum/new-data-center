package cn.iocoder.yudao.framework.realdb.config;

import cn.iocoder.yudao.framework.realdb.core.client.DefaultRealDbClient;
import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 紫金桥实时数据库自动配置，不对外暴露运行时组态 HTTP 接口。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "yudao.realdb", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RealDbProperties.class)
public class YudaoRealDbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RealDbClient.class)
    public RealDbClient realDbClient(RealDbProperties properties) {
        // 独立的 Mapper 保留供应商字段原名，避免项目全局命名策略改变协议。
        return new DefaultRealDbClient(properties, new ObjectMapper());
    }

}
