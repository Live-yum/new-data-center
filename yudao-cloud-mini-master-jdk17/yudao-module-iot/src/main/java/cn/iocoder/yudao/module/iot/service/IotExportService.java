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
  request.deviceIds().forEach(id->repo.device(tenant,id));
  db.update("INSERT INTO iot_export_owner(tenant_id,owner_id) VALUES(?,?) ON CONFLICT DO NOTHING",tenant,owner);
  db.queryForObject("SELECT owner_id FROM iot_export_owner WHERE tenant_id=? AND owner_id=? FOR UPDATE",Long.class,tenant,owner);
  Integer count=db.queryForObject("SELECT count(*) FROM iot_export WHERE tenant_id=? AND owner_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,tenant,owner);
  if(count!=null && count>=3) throw new ServiceException(1_070_000_429,"同时最多3个下载任务");
  String id=UUID.randomUUID().toString(); OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
  db.update("INSERT INTO iot_export(id,tenant_id,owner_id,status,query_json,created_at,expires_at) VALUES(?,?,?,'QUEUED',?,?,?)",id,tenant,owner,repo.encode(request),now,now.plusHours(24));
  return get(tenant,owner,id);
 }
 public List<ExportJob> list(long tenant,long owner) {
  return db.query("SELECT id,status,created_at,expires_at,error FROM iot_export WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC LIMIT 100",(rs,n)->job(rs),tenant,owner);
 }
 public ExportJob get(long tenant,long owner,String id) {
  return db.query("SELECT id,status,created_at,expires_at,error FROM iot_export WHERE tenant_id=? AND owner_id=? AND id=?",(rs,n)->job(rs),tenant,owner,id)
   .stream().findFirst().orElseThrow(IotRepository::missing);
 }
 private ExportJob job(java.sql.ResultSet rs) throws java.sql.SQLException {
  OffsetDateTime expiry=rs.getObject("expires_at",OffsetDateTime.class);
  String status=expiry.isBefore(OffsetDateTime.now(ZoneOffset.UTC))?"EXPIRED":rs.getString("status");
  return new ExportJob(rs.getString("id"),status,rs.getObject("created_at",OffsetDateTime.class).toString(),expiry.toString(),rs.getString("error"));
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
  String request=db.queryForObject("SELECT query_json FROM iot_export WHERE tenant_id=? AND owner_id=? AND id=?",String.class,tenant,owner,id);
  readQuery(request).deviceIds().forEach(device->repo.device(tenant,device));
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
  List<Map<String,Object>> jobs=db.queryForList("SELECT id,tenant_id,query_json FROM iot_export WHERE status='QUEUED' AND expires_at>? ORDER BY created_at LIMIT 1",now);
  for(Map<String,Object> job:jobs) {
   String id=(String)job.get("id"); long tenant=((Number)job.get("tenant_id")).longValue();
   if(db.update("UPDATE iot_export SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED' AND expires_at>?",now,id,now)!=1) continue;
   try {
    QueryResult result=query.history(tenant,readQuery((String)job.get("query_json")));
    String csv=csv(result);
    db.update("UPDATE iot_export SET status='SUCCEEDED',csv_content=? WHERE id=? AND tenant_id=? AND status='RUNNING' AND expires_at>?",csv,id,tenant,OffsetDateTime.now(ZoneOffset.UTC));
   } catch(Exception failure) {
    // Do not persist upstream response bodies, tokens, raw points or credential-bearing exception messages.
    db.update("UPDATE iot_export SET status='FAILED',error='Query failed; check authorized service logs',csv_content=NULL WHERE id=? AND tenant_id=? AND status='RUNNING'",id,tenant);
   }
  }
 }
 private Query readQuery(String body) {
  try { return json.readValue(body,Query.class); } catch(Exception e) { throw new ServiceException(1_070_000_400,"无效下载查询"); }
 }
 public static String csv(QueryResult result) {
  StringBuilder out=new StringBuilder("\ufeffdeviceId,metricKey,unit,time,value,quality\r\n");
  int rows=0;
  for(Series series:result.series()) for(Point point:series.points()) {
   if(++rows>10000) throw new ServiceException(1_070_000_400,"导出超过10000行");
   out.append(cell(series.deviceId())).append(',').append(cell(series.metricKey())).append(',').append(cell(series.unit())).append(',').append(cell(point.time())).append(',').append(cell(point.value())).append(',').append(cell(point.quality())).append("\r\n");
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
