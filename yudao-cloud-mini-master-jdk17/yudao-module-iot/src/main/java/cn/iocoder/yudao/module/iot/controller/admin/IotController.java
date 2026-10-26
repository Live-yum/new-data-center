package cn.iocoder.yudao.module.iot.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.iot.model.IotModels.*;
import cn.iocoder.yudao.module.iot.model.IotModels.Mapping;
import cn.iocoder.yudao.module.iot.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/iot")
@Validated
public class IotController {
 private final IotRepository repo; private final IotQueryService query; private final IotExportService exports;
 public IotController(IotRepository repo,IotQueryService query,IotExportService exports) { this.repo=repo;this.query=query;this.exports=exports; }
 private long tenant() { return TenantContextHolder.getRequiredTenantId(); }
 private long owner() { return java.util.Objects.requireNonNull(SecurityFrameworkUtils.getLoginUserId()); }
 @GetMapping("/devices") @PreAuthorize("@ss.hasPermission('iot:device:query')")
 public CommonResult<List<Device>> devices() { return success(repo.devices(tenant())); }
 @PostMapping("/devices") @PreAuthorize("@ss.hasPermission('iot:device:configure')")
 public CommonResult<Device> createDevice(@Valid @RequestBody DeviceInput body) { return success(repo.createDevice(tenant(),body)); }
 @GetMapping("/devices/{id}/mappings") @PreAuthorize("@ss.hasPermission('iot:device:query')")
 public CommonResult<List<Mapping>> mappings(@PathVariable long id) { return success(repo.mappings(tenant(),id)); }
 @PutMapping("/devices/{id}/mappings") @PreAuthorize("@ss.hasPermission('iot:device:configure')")
 public CommonResult<List<Mapping>> mappings(@PathVariable long id,@RequestBody @Size(max=100) List<@Valid Mapping> body) { return success(repo.replaceMappings(tenant(),id,body)); }
 @GetMapping("/dashboards") @PreAuthorize("@ss.hasPermission('iot:dashboard:query')")
 public CommonResult<List<Dashboard>> dashboards() { return success(repo.dashboards(tenant(),false)); }
 @GetMapping("/dashboards/{id}") @PreAuthorize("@ss.hasPermission('iot:dashboard:query')")
 public CommonResult<Dashboard> dashboard(@PathVariable long id) { return success(repo.dashboard(tenant(),id)); }
 @PostMapping("/dashboards") @PreAuthorize("@ss.hasPermission('iot:dashboard:configure')")
 public CommonResult<Dashboard> createDashboard(@Valid @RequestBody Dashboard body) { return success(repo.saveDashboard(tenant(),null,body,false)); }
 @PutMapping("/dashboards/{id}") @PreAuthorize("@ss.hasPermission('iot:dashboard:configure')")
 public CommonResult<Dashboard> updateDashboard(@PathVariable long id,@Valid @RequestBody Dashboard body) { return success(repo.saveDashboard(tenant(),id,body,false)); }
 @DeleteMapping("/dashboards/{id}") @PreAuthorize("@ss.hasPermission('iot:dashboard:configure')")
 public CommonResult<Boolean> deleteDashboard(@PathVariable long id,@RequestParam long version) { return success(repo.deleteDashboard(tenant(),id,version)); }
 @GetMapping("/templates") @PreAuthorize("@ss.hasPermission('iot:dashboard:query')")
 public CommonResult<List<Dashboard>> templates() { return success(repo.dashboards(tenant(),true)); }
 @PostMapping("/templates") @PreAuthorize("@ss.hasPermission('iot:dashboard:configure')")
 public CommonResult<Dashboard> createTemplate(@Valid @RequestBody Dashboard body) { return success(repo.saveDashboard(tenant(),null,body,true)); }
 @PostMapping("/query/realtime") @PreAuthorize("@ss.hasPermission('iot:data:query')")
 public CommonResult<QueryResult> realtime(@RequestBody Query body) { return success(query.realtime(tenant(),body)); }
 @PostMapping("/query/history") @PreAuthorize("@ss.hasPermission('iot:data:query')")
 public CommonResult<QueryResult> history(@RequestBody Query body) { return success(query.history(tenant(),body)); }
 @PostMapping("/exports") @PreAuthorize("@ss.hasPermission('iot:data:export')")
 public CommonResult<ExportJob> createExport(@RequestBody Query body) { return success(exports.create(tenant(),owner(),body)); }
 @GetMapping("/exports") @PreAuthorize("@ss.hasPermission('iot:data:export')")
 public CommonResult<List<ExportJob>> exports() { return success(exports.list(tenant(),owner())); }
 @GetMapping("/exports/{id}") @PreAuthorize("@ss.hasPermission('iot:data:export')")
 public CommonResult<ExportJob> export(@PathVariable String id) { return success(exports.get(tenant(),owner(),id)); }
 @PostMapping("/exports/{id}/cancel") @PreAuthorize("@ss.hasPermission('iot:data:export')")
 public CommonResult<ExportJob> cancel(@PathVariable String id) { return success(exports.cancel(tenant(),owner(),id)); }
 @GetMapping("/exports/{id}/file") @PreAuthorize("@ss.hasPermission('iot:data:export')")
 public ResponseEntity<byte[]> file(@PathVariable String id) {
  return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
    .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"iot-export.csv\"")
    .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
    .body(exports.file(tenant(),owner(),id));
 }
}
