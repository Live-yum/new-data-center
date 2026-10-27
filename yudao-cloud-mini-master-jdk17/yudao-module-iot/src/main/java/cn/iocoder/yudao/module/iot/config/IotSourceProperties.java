package cn.iocoder.yudao.module.iot.config;

import cn.iocoder.yudao.framework.realdb.config.RealDbProperties;

import java.util.ArrayList;
import java.util.List;

/** Deployment-only configuration. Never bind this class to a browser request or return it as JSON. */
public class IotSourceProperties {
    private String name;
    private String timeZone = "UTC";
    /** Change when the installation behind the same endpoint changes. Not a password or access grant. */
    private String revision;
    /** Empty/omitted is deny-all, never an implicit grant to every tenant. */
    private List<Long> allowedTenants = new ArrayList<>();
    private RealDbProperties connection = new RealDbProperties();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public String getRevision() { return revision; }
    public void setRevision(String revision) { this.revision = revision; }
    public List<Long> getAllowedTenants() { return allowedTenants; }
    public void setAllowedTenants(List<Long> allowedTenants) { this.allowedTenants = allowedTenants; }
    public RealDbProperties getConnection() { return connection; }
    public void setConnection(RealDbProperties connection) { this.connection = connection; }
}
