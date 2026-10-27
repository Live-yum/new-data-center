package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.iot.model.IotModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Durable queue with compare-and-set claims. No public file URLs; tenant AND owner required on every download. */
@Service
public class IotExportService {
 private final JdbcTemplate db; private final ObjectMapper json; private final IotQueryService query; private final IotRepository repo;
 public IotExportService(JdbcTemplate db,ObjectMapper json,IotQueryService query,IotRepository repo) { this.db=db;this.json=json;this.query=query;this.repo=repo; }
 @Transactional
 public ExportJob create(long tenant,long owner,Query request) {
  IotQueryService.validate(request,true);
  ExportSnapshot snapshot;
  try { snapshot=query.snapshot(tenant,request); }
  catch(IotQueryService.SourceFailureException failure) { throw failure.asServiceException(); }
  db.update("INSERT INTO iot_export_owner(tenant_id,owner_id) VALUES(?,?) ON CONFLICT DO NOTHING",tenant,owner);
  db.queryForObject("SELECT owner_id FROM iot_export_owner WHERE tenant_id=? AND owner_id=? FOR UPDATE",Long.class,tenant,owner);
  Integer count=db.queryForObject("SELECT count(*) FROM iot_export WHERE tenant_id=? AND owner_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,tenant,owner);
  if(count!=null && count>=3) throw new ServiceException(1_070_000_429,"同时最多3个下载任务");
  String id=UUID.randomUUID().toString(); OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
  // Old workers do not understand snapshots. Leave an intentionally non-executable marker in
  // their legacy field, so mixed-version instances fail closed instead of re-resolving mappings.
  db.update("INSERT INTO iot_export(id,tenant_id,owner_id,status,query_json,snapshot_json,created_at,expires_at) VALUES(?,?,?,'QUEUED',?,?,?,?)",id,tenant,owner,repo.encode(Map.of("snapshotSchemaVersion",1)),repo.encode(snapshot),now,now.plusHours(24));
  return get(tenant,owner,id);
 }
 public List<ExportJob> list(long tenant,long owner) {
  return db.query("SELECT id,status,created_at,expires_at,error,snapshot_json FROM iot_export WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC LIMIT 100",(rs,n)->job(rs),tenant,owner);
 }
 public ExportJob get(long tenant,long owner,String id) {
  return db.query("SELECT id,status,created_at,expires_at,error,snapshot_json FROM iot_export WHERE tenant_id=? AND owner_id=? AND id=?",(rs,n)->job(rs),tenant,owner,id)
   .stream().findFirst().orElseThrow(IotRepository::missing);
 }
 private ExportJob job(java.sql.ResultSet rs) throws java.sql.SQLException {
  OffsetDateTime expiry=rs.getObject("expires_at",OffsetDateTime.class);
  String status=expiry.isBefore(OffsetDateTime.now(ZoneOffset.UTC))?"EXPIRED":rs.getString("status");
  return new ExportJob(rs.getString("id"),status,rs.getObject("created_at",OffsetDateTime.class).toString(),expiry.toString(),rs.getString("error"),sourceIds(rs.getString("snapshot_json")));
 }
 public ExportJob cancel(long tenant,long owner,String id) {
  get(tenant,owner,id);
  db.update("UPDATE iot_export SET status='CANCELLED',csv_content=NULL WHERE tenant_id=? AND owner_id=? AND id=? AND status IN ('QUEUED','RUNNING')",tenant,owner,id);
  return get(tenant,owner,id);
 }
 public byte[] file(long tenant,long owner,String id) {
  ExportJob job=get(tenant,owner,id);
  if(!"SUCCEEDED".equals(job.status())) throw new ServiceException(1_070_000_409,"任务未完成或文件已过期");
  // Revalidate mapped device access on download; no cross-tenant raw-point or file identifier escape hatch.
  Map<String,Object> stored=db.queryForMap("SELECT query_json,snapshot_json FROM iot_export WHERE tenant_id=? AND owner_id=? AND id=?",tenant,owner,id);
  String snapshot=(String)stored.get("snapshot_json");
  try {
   if(snapshot==null) query.authorizeLegacyExport(tenant,readQuery((String)stored.get("query_json")));
   else query.authorizeSnapshot(tenant,readSnapshot(snapshot));
  } catch(IotQueryService.SourceFailureException failure) { throw failure.asServiceException(); }
  String content=db.query("SELECT csv_content FROM iot_export WHERE tenant_id=? AND owner_id=? AND id=? AND status='SUCCEEDED' AND expires_at>?",(rs,n)->rs.getString(1),tenant,owner,id,OffsetDateTime.now(ZoneOffset.UTC)).stream().filter(Objects::nonNull).findFirst().orElse(null);
  if(content==null) throw IotRepository.missing();
  return content.getBytes(StandardCharsets.UTF_8);
 }
 @Scheduled(fixedDelayString="${yudao.iot.export-poll-ms:2000}")
 public void work() {
  OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
  db.update("UPDATE iot_export SET status='EXPIRED',csv_content=NULL WHERE expires_at<=? AND status<>'EXPIRED'",now);
  // A crashed or hung worker is never silently replayed; user may submit a fresh read-only task.
  db.update("UPDATE iot_export SET status='FAILED',error='Worker timeout or interrupted',csv_content=NULL WHERE status='RUNNING' AND started_at<?",now.minusMinutes(10));
  List<Map<String,Object>> jobs=db.queryForList("SELECT id,tenant_id,query_json,snapshot_json FROM iot_export WHERE status='QUEUED' AND expires_at>? ORDER BY created_at LIMIT 1",now);
  for(Map<String,Object> job:jobs) {
   String id=(String)job.get("id"); long tenant=((Number)job.get("tenant_id")).longValue();
   if(db.update("UPDATE iot_export SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED' AND expires_at>?",now,id,now)!=1) continue;
   try {
    String snapshot=(String)job.get("snapshot_json");
    if(snapshot==null) {
     db.update("UPDATE iot_export SET status='FAILED',error='Legacy query has no binding snapshot; create a new export',csv_content=NULL WHERE id=? AND tenant_id=? AND status='RUNNING'",id,tenant);
     continue;
    }
    QueryResult result=query.historySnapshot(tenant,readSnapshot(snapshot));
    if(result.partial() || !result.errors().isEmpty()) throw new IotQueryService.SourceFailureException(result.errors());
    String csv=csv(result);
    db.update("UPDATE iot_export SET status='SUCCEEDED',csv_content=? WHERE id=? AND tenant_id=? AND status='RUNNING' AND expires_at>?",csv,id,tenant,OffsetDateTime.now(ZoneOffset.UTC));
   } catch(Exception failure) {
    // Do not persist upstream response bodies, tokens, raw points or credential-bearing exception messages.
    String error="Query failed; check source configuration and authorized service diagnostics";
    if(failure instanceof IotQueryService.SourceFailureException sourceFailure) {
     error="Source query failed: "+sourceFailure.errors().stream().map(e->e.sourceId()+"="+e.code()).collect(java.util.stream.Collectors.joining(", "));
     if(error.length()>200) error=error.substring(0,197)+"...";
    }
    db.update("UPDATE iot_export SET status='FAILED',error=?,csv_content=NULL WHERE id=? AND tenant_id=? AND status='RUNNING'",error,id,tenant);
   }
  }
 }
 private ExportSnapshot readSnapshot(String body) {
  try { return json.readValue(body,ExportSnapshot.class); } catch(Exception e) { throw new ServiceException(1_070_000_400,"无效下载快照，请重新创建任务"); }
 }
 private List<String> sourceIds(String body) {
  if(body==null) return List.of("default");
  ExportSnapshot snapshot=readSnapshot(body);
  if(snapshot==null || snapshot.bindings()==null) throw new ServiceException(1_070_000_400,"无效下载快照");
  return snapshot.bindings().stream().map(ResolvedBinding::sourceId).distinct().sorted().toList();
 }
 private Query readQuery(String body) {
  try { return json.readValue(body,Query.class); } catch(Exception e) { throw new ServiceException(1_070_000_400,"无效下载查询"); }
 }
 public static String csv(QueryResult result) {
  if(result.partial() || !result.errors().isEmpty()) throw new IotQueryService.SourceFailureException(result.errors());
  StringBuilder out=new StringBuilder("\ufeffsourceId,deviceId,metricKey,unit,time,value,quality\r\n");
  int rows=0;
  for(Series series:result.series()) for(Point point:series.points()) {
   if(++rows>10000) throw new ServiceException(1_070_000_400,"导出超过10000行");
   out.append(cell(series.sourceId())).append(',').append(cell(series.deviceId())).append(',').append(cell(series.metricKey())).append(',').append(cell(series.unit())).append(',').append(cell(point.time())).append(',').append(cell(point.value())).append(',').append(cell(point.quality())).append("\r\n");
   if(out.length()>2_000_000) throw new ServiceException(1_070_000_400,"导出内容过大");
  }
  String csv=out.toString();
  if(csv.getBytes(StandardCharsets.UTF_8).length>5_000_000) throw new ServiceException(1_070_000_400,"导出超过5MB");
  return csv;
 }
 static String cell(Object value) {
  String s=value==null?"":String.valueOf(value);
  // Prevent spreadsheet formula execution, including leading whitespace/control variants.
  String stripped=s.stripLeading();
  if(!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0 && !(value instanceof Number)) s="'"+s;
  return "\""+s.replace("\"","\"\"")+"\"";
 }
}
