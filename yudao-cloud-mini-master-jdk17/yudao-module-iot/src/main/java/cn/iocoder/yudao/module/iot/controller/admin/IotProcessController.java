package cn.iocoder.yudao.module.iot.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.iot.model.IotModels.ProcessScene;
import cn.iocoder.yudao.module.iot.model.IotModels.Source;
import cn.iocoder.yudao.module.iot.service.IotProcessRepository;
import cn.iocoder.yudao.module.iot.service.IotSourceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/iot")
public class IotProcessController {
    private final IotProcessRepository pages;
    private final IotSourceService sources;
    public IotProcessController(IotProcessRepository pages, IotSourceService sources) {
        this.pages = pages; this.sources = sources;
    }
    private long tenant() { return TenantContextHolder.getRequiredTenantId(); }
    @GetMapping("/sources") @PreAuthorize("@ss.hasPermission('iot:device:query')")
    public CommonResult<List<Source>> sources() { return success(sources.sources(tenant())); }
    @GetMapping("/process-pages") @PreAuthorize("@ss.hasPermission('iot:dashboard:query')")
    public CommonResult<List<ProcessScene>> pages() { return success(pages.list(tenant())); }
    @GetMapping("/process-pages/{id}") @PreAuthorize("@ss.hasPermission('iot:dashboard:query')")
    public CommonResult<ProcessScene> page(@PathVariable long id) { return success(pages.get(tenant(), id)); }
    @PostMapping("/process-pages") @PreAuthorize("@ss.hasPermission('iot:dashboard:configure')")
    public CommonResult<ProcessScene> create(@RequestBody ProcessScene body) { return success(pages.save(tenant(), null, body)); }
    @PutMapping("/process-pages/{id}") @PreAuthorize("@ss.hasPermission('iot:dashboard:configure')")
    public CommonResult<ProcessScene> update(@PathVariable long id, @RequestBody ProcessScene body) { return success(pages.save(tenant(), id, body)); }
    @DeleteMapping("/process-pages/{id}") @PreAuthorize("@ss.hasPermission('iot:dashboard:configure')")
    public CommonResult<Boolean> delete(@PathVariable long id, @RequestParam long version) { return success(pages.delete(tenant(), id, version)); }
}
