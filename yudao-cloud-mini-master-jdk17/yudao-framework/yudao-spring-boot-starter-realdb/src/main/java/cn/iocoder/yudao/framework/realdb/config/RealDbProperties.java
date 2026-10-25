package cn.iocoder.yudao.framework.realdb.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 紫金桥实时数据库配置项。
 */
@ConfigurationProperties("yudao.realdb")
@Data
public class RealDbProperties {

    /** 默认不创建客户端，也不发起登录请求。 */
    private boolean enabled;
    /** REST 服务地址，例如 http://127.0.0.1:8000，不包含 /api。 */
    private String baseUrl;
    /** 数据读取是否需要认证；运行时组态、注册点名始终需要认证。 */
    private boolean securityEnabled = true;
    @ToString.Exclude
    private String username;
    @ToString.Exclude
    private String password;
    /** 可选的现成 token；优先使用。失效后，有账号密码时重新登录。 */
    @ToString.Exclude
    private String token;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);
    /** 同步发布项的服务端最长等待时间为 180 秒。 */
    private Duration syncTimeout = Duration.ofSeconds(190);
    /** 文档 5.11.4 URL 有歧义，允许按实际服务版本覆盖端点（仅相对路径）。 */
    private String updateExportPath = "/api/export";

}
