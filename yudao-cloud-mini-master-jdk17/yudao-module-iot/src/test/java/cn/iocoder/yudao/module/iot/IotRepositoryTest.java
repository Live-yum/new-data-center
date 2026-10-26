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
 protected DriverManagerDataSource source; protected JdbcTemplate db; protected IotRepository repo; protected IotExportService exports; protected IotQueryService query;
 protected String url() { return "jdbc:h2:mem:iot"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"; }
 protected String username() { return "sa"; }
 protected String password() { return ""; }
 @BeforeEach void setup() throws Exception {
  source=new DriverManagerDataSource(url(),username(),password());
  db=new JdbcTemplate(source);
  String ddl=Files.readString(Path.of("../sql/postgresql/upgrade/20261026_iot.sql"));
  for(String statement:ddl.split(";")) if(!statement.isBlank()) db.execute(statement);
  // Dedicated test database only. Never run against a real business DB.
  db.update("DELETE FROM iot_export");db.update("DELETE FROM iot_export_owner"); db.update("DELETE FROM iot_mapping");db.update("DELETE FROM iot_dashboard");db.update("DELETE FROM iot_device");
  ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  repo=new IotRepository(db,json);query=mock(IotQueryService.class);exports=new IotExportService(db,json,query,repo);
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
}
