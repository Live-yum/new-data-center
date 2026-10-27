package cn.iocoder.yudao.module.iot;

import cn.iocoder.yudao.module.iot.model.IotModels.*;
import cn.iocoder.yudao.module.iot.service.*;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class IotRepositoryTest {
 protected DriverManagerDataSource source; protected JdbcTemplate db; protected IotRepository repo; protected IotExportService exports; protected IotQueryService query; protected IotProcessRepository pages;
 protected String url() { return "jdbc:h2:mem:iot"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"; }
 protected String username() { return "sa"; }
 protected String password() { return ""; }
 @BeforeEach void setup() throws Exception {
  source=new DriverManagerDataSource(url(),username(),password());
  db=new JdbcTemplate(source);
  String ddl=Files.readString(Path.of("../sql/postgresql/upgrade/20261026_iot.sql"));
  for(String statement:ddl.split(";")) if(!statement.isBlank()) db.execute(statement);
  String processDdl=Files.readString(Path.of("../sql/postgresql/upgrade/20261027_iot_process.sql"));
  for(String statement:processDdl.split(";")) if(!statement.isBlank()) db.execute(statement);
  // Dedicated test database only. Never run against a real business DB.
  db.update("DELETE FROM iot_process_page");db.update("DELETE FROM iot_process_tenant");db.update("DELETE FROM iot_export");db.update("DELETE FROM iot_export_owner"); db.update("DELETE FROM iot_mapping");db.update("DELETE FROM iot_dashboard");db.update("DELETE FROM iot_device");
  ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  repo=new IotRepository(db,json);query=mock(IotQueryService.class);exports=new IotExportService(db,json,query,repo);
  pages=new IotProcessRepository(db,json,repo);
 }
 protected Dashboard dashboard(long version) { return new Dashboard(null,"Pump",version,1,List.of()); }
 protected Device device() { return repo.createDevice(11,new DeviceInput("Pump","P1")); }
 protected Query request(long id) { return new Query(List.of(id),List.of("temperature"),OffsetDateTime.parse("2026-01-01T00:00:00Z"),OffsetDateTime.parse("2026-01-01T00:01:00Z"),1000L,"RAW"); }
 @Test void deviceAndMappingAreTenantScoped() {
  Device d=device();repo.replaceMappings(11,d.id(),List.of(new Mapping("temperature","Temperature","P1.PV","°C")));
  assertEquals(1,repo.devices(11).size());assertTrue(repo.devices(12).isEmpty());
  assertThrows(ServiceException.class,()->repo.mappings(12,d.id()));
  assertEquals("P1.PV",repo.mappings(11,d.id()).get(0).pointName());
 }
 @Test void mappingReplacementRejectsDuplicatesBeforeDeleting() {
  Device d=device();Mapping m=new Mapping("t","T","P.PV","C");repo.replaceMappings(11,d.id(),List.of(m));
  assertThrows(ServiceException.class,()->repo.replaceMappings(11,d.id(),List.of(m,m)));
  assertEquals(1,repo.mappings(11,d.id()).size());
 }
 @Test void optimisticLockAndTenantIsolation() {
  Dashboard saved=repo.saveDashboard(11,null,dashboard(0),false);
  assertEquals(1,repo.saveDashboard(11,saved.id(),dashboard(0),false).version());
  assertThrows(ServiceException.class,()->repo.saveDashboard(11,saved.id(),dashboard(0),false));
  assertThrows(ServiceException.class,()->repo.dashboard(12,saved.id()));
  assertThrows(ServiceException.class,()->repo.deleteDashboard(11,saved.id(),0));
  assertTrue(repo.deleteDashboard(11,saved.id(),1));
 }
 @Test void templatesArePersistedAndTenantScoped() {
  repo.saveDashboard(11,null,dashboard(0),true);
  assertEquals(1,repo.dashboards(11,true).size());assertTrue(repo.dashboards(11,false).isEmpty());assertTrue(repo.dashboards(12,true).isEmpty());
 }
 @Test void privateExportCompletedAndOwnerProtected() {
  Device d=device();when(query.history(eq(11L),any())).thenReturn(new QueryResult("REALDB",List.of(new Series(d.id(),"t","C",List.of(new Point("2026-01-01T00:00:00Z",21,"GOOD"))))));
  ExportJob job=exports.create(11,7,request(d.id()));assertEquals("QUEUED",job.status());
  assertThrows(ServiceException.class,()->exports.get(12,7,job.id()));assertThrows(ServiceException.class,()->exports.file(11,8,job.id()));
  exports.work();assertEquals("SUCCEEDED",exports.get(11,7,job.id()).status());assertTrue(new String(exports.file(11,7,job.id()),java.nio.charset.StandardCharsets.UTF_8).contains("GOOD"));
 }
 @Test void cancelledFailedAndExpiredJobsCannotDownload() {
  Device d=device();ExportJob cancelled=exports.create(11,7,request(d.id()));exports.cancel(11,7,cancelled.id());exports.work();
  assertEquals("CANCELLED",exports.get(11,7,cancelled.id()).status());verifyNoInteractions(query);
  assertThrows(ServiceException.class,()->exports.file(11,7,cancelled.id()));
  when(query.history(anyLong(),any())).thenThrow(new IllegalStateException("secret-token"));
  ExportJob failed=exports.create(11,7,request(d.id()));exports.work();assertEquals("FAILED",exports.get(11,7,failed.id()).status());assertFalse(exports.get(11,7,failed.id()).error().contains("secret"));
  db.update("UPDATE iot_export SET expires_at=? WHERE id=?",OffsetDateTime.now().minusHours(1),failed.id());
  assertEquals("EXPIRED",exports.get(11,7,failed.id()).status());assertThrows(ServiceException.class,()->exports.file(11,7,failed.id()));
 }
 @Test void crashedWorkerIsFailedWithoutReplay() {
  Device d=device();ExportJob job=exports.create(11,7,request(d.id()));
  db.update("UPDATE iot_export SET status='RUNNING',started_at=? WHERE id=?",OffsetDateTime.now().minusMinutes(11),job.id());
  exports.work();assertEquals("FAILED",exports.get(11,7,job.id()).status());verifyNoInteractions(query);
 }
 @Test void concurrentInstancesCannotExceedOwnerQuota() throws Exception {
  Device d=device();
  IotExportService other=new IotExportService(db,new ObjectMapper().findAndRegisterModules(),query,repo);
  var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
  var pool=java.util.concurrent.Executors.newFixedThreadPool(6);
  try {
   List<java.util.concurrent.Callable<Boolean>> tasks=new ArrayList<>();
   for(int i=0;i<6;i++) {
    IotExportService service=i%2==0?exports:other;
    tasks.add(()->{try {tx.execute(status->service.create(11,7,request(d.id())));return true;}
      catch(ServiceException expected) {assertEquals(1_070_000_429,expected.getCode());return false;}});
   }
   long accepted=0;for(var result:pool.invokeAll(tasks)) if(result.get()) accepted++;
   assertEquals(3,accepted);assertEquals(3,exports.list(11,7).size());
  } finally {pool.shutdownNow();}
 }
 @Test void cancellationDuringExecutionWinsOverCompletionAndNoReplay() {
  Device d=device();ExportJob job=exports.create(11,7,request(d.id()));
  when(query.history(eq(11L),any())).thenAnswer(invocation->{
   exports.cancel(11,7,job.id()); return new QueryResult("REALDB",List.of());
  });
  exports.work();exports.work();
  assertEquals("CANCELLED",exports.get(11,7,job.id()).status());
  verify(query,times(1)).history(eq(11L),any());
  assertThrows(ServiceException.class,()->exports.file(11,7,job.id()));
 }
 @Test void exportQueueLimitAndForeignDeviceAreRejected() {
  Device d=device();assertThrows(ServiceException.class,()->exports.create(12,7,request(d.id())));
  for(int i=0;i<3;i++) exports.create(11,7,request(d.id()));
  assertThrows(ServiceException.class,()->exports.create(11,7,request(d.id())));
 }
 protected ProcessScene process(long version, Long deviceId, List<String> metrics, String primary) {
  return new ProcessScene(null,"现场工艺",null,version,1,"separation",List.of(
    new ProcessBinding("slot-1",deviceId,metrics,primary),
    new ProcessBinding("slot-2",null,List.of(),""),new ProcessBinding("slot-3",null,List.of(),""),
    new ProcessBinding("slot-4",null,List.of(),"")),5,120);
 }
 @Test void processDraftPersistsWithoutManufacturingDeviceData() {
  ProcessScene saved=pages.save(11,null,process(0,null,List.of(),""));
  assertNotNull(saved.id());assertEquals("",saved.description());assertEquals(0,saved.version());
  assertEquals(saved,pages.get(11,saved.id()));assertEquals(1,pages.list(11).size());
  assertTrue(repo.devices(11).isEmpty());assertTrue(repo.dashboards(11,false).isEmpty());
  verifyNoInteractions(query);
 }
 @Test void processVersionConflictAndTenantIsolation() {
  ProcessScene saved=pages.save(11,null,process(0,null,List.of(),""));
  assertTrue(pages.list(12).isEmpty());assertThrows(ServiceException.class,()->pages.get(12,saved.id()));
  assertThrows(ServiceException.class,()->pages.save(12,saved.id(),process(0,null,List.of(),"")));
  assertThrows(ServiceException.class,()->pages.delete(12,saved.id(),0));
  assertEquals(1,pages.save(11,saved.id(),process(0,null,List.of(),"")).version());
  ServiceException conflict=assertThrows(ServiceException.class,()->pages.save(11,saved.id(),process(0,null,List.of(),"")));
  assertEquals(1_070_000_409,conflict.getCode());
  assertThrows(ServiceException.class,()->pages.delete(11,saved.id(),0));
  assertTrue(pages.delete(11,saved.id(),1));assertTrue(pages.list(11).isEmpty());
  assertThrows(ServiceException.class,()->pages.list(0));
 }
 @Test void processBindingsMustBeTenantDevicesWithExistingMappedMetrics() {
  Device d=device();repo.replaceMappings(11,d.id(),List.of(new Mapping("temperature","温度","P1.PV","C")));
  assertThrows(ServiceException.class,()->pages.save(12,null,process(0,d.id(),List.of("temperature"),"temperature")));
  assertThrows(ServiceException.class,()->pages.save(11,null,process(0,d.id(),List.of("pressure"),"pressure")));
  assertThrows(ServiceException.class,()->pages.save(11,null,process(0,d.id()+1000,List.of("temperature"),"temperature")));
  ProcessScene saved=pages.save(11,null,process(0,d.id(),List.of("temperature"),"temperature"));
  assertEquals(d.id(),saved.bindings().get(0).deviceId());
  assertThrows(ServiceException.class,()->pages.save(11,saved.id(),process(0,d.id(),List.of("missing"),"missing")));
  assertEquals(saved,pages.get(11,saved.id()));
 }
 @Test void processConcurrentUpdatesDoNotOverwriteEachOther() throws Exception {
  ProcessScene saved=pages.save(11,null,process(0,null,List.of(),""));
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try {
   java.util.concurrent.Callable<Boolean> update=()->{
    try {pages.save(11,saved.id(),process(0,null,List.of(),""));return true;}
    catch(ServiceException conflict) {assertEquals(1_070_000_409,conflict.getCode());return false;}
   };
   long updated=0;for(var result:pool.invokeAll(List.of(update,update))) if(result.get()) updated++;
   assertEquals(1,updated);assertEquals(1,pages.get(11,saved.id()).version());
  } finally {pool.shutdownNow();}
 }
 @Test void processRequestIdentityCannotOverridePathOrTenant() {
  ProcessScene saved=pages.save(11,null,process(0,null,List.of(),""));
  ProcessScene wrong=new ProcessScene(saved.id()+1,saved.name(),saved.description(),0,1,saved.template(),saved.bindings(),5,120);
  assertThrows(ServiceException.class,()->pages.save(11,saved.id(),wrong));
  assertThrows(ServiceException.class,()->pages.save(11,null,wrong));
  assertThrows(ServiceException.class,()->pages.save(11,null,process(1,null,List.of(),"")));
  assertEquals(saved,pages.get(11,saved.id()));
 }
 @Test void mappingRemovedAfterProcessSaveFailsClosedBeforeUpstreamRead() {
  Device d=device();repo.replaceMappings(11,d.id(),List.of(new Mapping("temperature","温度","P1.PV","C")));
  ProcessScene saved=pages.save(11,null,process(0,d.id(),List.of("temperature"),"temperature"));
  repo.replaceMappings(11,d.id(),List.of());
  var client=mock(cn.iocoder.yudao.framework.realdb.core.client.RealDbClient.class);
  var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("realDb",client);
  IotQueryService realQuery=new IotQueryService(repo,beans.getBeanProvider(cn.iocoder.yudao.framework.realdb.core.client.RealDbClient.class),"UTC");
  assertThrows(ServiceException.class,()->realQuery.realtime(11,request(d.id())));
  assertThrows(ServiceException.class,()->pages.save(11,saved.id(),process(0,d.id(),List.of("temperature"),"temperature")));
  assertEquals(saved,pages.get(11,saved.id()));verifyNoInteractions(client);
 }
 @Test void processPageCapNeverHidesSuccessfullySavedPagesAcrossInstances() throws Exception {
  for(int i=0;i<99;i++) pages.save(11,null,process(0,null,List.of(),""));
  var other=new IotProcessRepository(db,new ObjectMapper(),repo);
  var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try {
   List<java.util.concurrent.Callable<Boolean>> tasks=new ArrayList<>();
   for(var service:List.of(pages,other)) tasks.add(()->{
    try {tx.execute(status->service.save(11,null,process(0,null,List.of(),"")));return true;}
    catch(ServiceException limit) {assertTrue(limit.getMessage().contains("100"));return false;}
   });
   long accepted=0;for(var result:pool.invokeAll(tasks)) if(result.get()) accepted++;
   assertEquals(1,accepted);assertEquals(100,pages.list(11).size());
   assertThrows(ServiceException.class,()->pages.save(11,null,process(0,null,List.of(),"")));
   ProcessScene first=pages.list(11).get(0);pages.delete(11,first.id(),first.version());
   assertNotNull(pages.save(11,null,process(0,null,List.of(),"")).id());assertEquals(100,pages.list(11).size());
   assertNotNull(pages.save(12,null,process(0,null,List.of(),"")).id());
  } finally {pool.shutdownNow();}
 }
}
