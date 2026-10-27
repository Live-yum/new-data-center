package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.realdb.config.RealDbProperties;
import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import cn.iocoder.yudao.module.iot.model.IotModels.Source;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.List;

/** Configuration readiness, not a connectivity probe. Reads never invoke any upstream method. */
@Service
public class IotSourceService {
    private final Environment environment;
    private final ObjectProvider<RealDbClient> clients;

    public IotSourceService(Environment environment, ObjectProvider<RealDbClient> clients) {
        this.environment = environment; this.clients = clients;
    }

    public List<Source> sources() {
        RealDbProperties properties = Binder.get(environment).bind("yudao.realdb", RealDbProperties.class).orElseGet(RealDbProperties::new);
        boolean enabled = properties.isEnabled();
        boolean configured = enabled && hasText(properties.getBaseUrl())
                && (!properties.isSecurityEnabled() || hasText(properties.getToken())
                    || hasText(properties.getUsername()) && properties.getPassword() != null)
                && clients.getIfAvailable() != null;
        String status = !enabled ? "DISABLED" : configured ? "CONFIGURED" : "NOT_CONFIGURED";
        String zone = ZoneId.of(environment.getProperty("yudao.iot.realdb-zone", "UTC")).getId();
        String description = !enabled ? "部署尚未启用紫金桥数据源；不会回退模拟数据"
                : configured ? "单一部署数据源已配置；尚不代表连接、点权限或现场数据已验证"
                : "部署连接或认证配置不完整；请由运维在服务端配置，浏览器不接收凭据";
        return List.of(new Source("default", "紫金桥 RealDB", "REALDB", enabled, configured, status, zone, description));
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
