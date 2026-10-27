package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.module.iot.model.IotModels.Source;
import org.springframework.stereotype.Service;

import java.util.List;

/** Tenant-visible configuration readiness, not a connectivity probe. Never returns private registry entries. */
@Service
public class IotSourceService {
    private final IotSourceRegistry registry;
    public IotSourceService(IotSourceRegistry registry) { this.registry = registry; }
    public List<Source> sources(long tenant) { return registry.sources(tenant); }
}
