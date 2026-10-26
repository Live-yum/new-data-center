package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.iot.model.IotModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Every business query explicitly scopes tenant; JdbcTemplate does not inherit MyBatis interceptors. */
@Repository
public class IotRepository {
 private final JdbcTemplate db;
 private final ObjectMapper json;
 public IotRepository(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }
 static ServiceException missing() { return new ServiceException(1_070_000_404,"记录不存在或无权访问"); }
 static ServiceException conflict() { return new ServiceException(1_070_000_409,"版本已变化，请重新加载"); }
 public List<Device> devices(long tenant) {
  return db.query("SELECT id,name,code FROM iot_device WHERE tenant_id=? ORDER BY id LIMIT 500",
    (rs,n)->new Device(rs.getLong("id"),rs.getString("name"),rs.getString("code")),tenant);
 }
 public Device device(long tenant,long id) {
  return db.query("SELECT id,name,code FROM iot_device WHERE tenant_id=? AND id=?",
    (rs,n)->new Device(rs.getLong("id"),rs.getString("name"),rs.getString("code")),tenant,id)
    .stream().findFirst().orElseThrow(IotRepository::missing);
 }
 public Device createDevice(long tenant,DeviceInput body) {
  Long id=db.queryForObject("SELECT nextval('iot_device_seq')",Long.class);
  db.update("INSERT INTO iot_device(id,tenant_id,name,code) VALUES(?,?,?,?)",id,tenant,body.name(),body.code());
  return device(tenant,id);
 }
 public List<Mapping> mappings(long tenant,long id) {
  device(tenant,id);
  return db.query("SELECT metric_key,name,point_name,unit FROM iot_mapping WHERE tenant_id=? AND device_id=? ORDER BY metric_key",
    (rs,n)->new Mapping(rs.getString("metric_key"),rs.getString("name"),rs.getString("point_name"),rs.getString("unit")),tenant,id);
 }
 @Transactional
 public List<Mapping> replaceMappings(long tenant,long id,List<Mapping> mappings) {
  device(tenant,id);
  // Serialize replacements, including the initially empty mapping set.
  db.queryForObject("SELECT id FROM iot_device WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,tenant,id);
  if(mappings==null || mappings.size()>100 || mappings.stream().map(Mapping::metricKey).distinct().count()!=mappings.size())
    throw new ServiceException(1_070_000_400,"测点映射数量或指标键重复");
  for(Mapping m:mappings) if(m.pointName()==null || m.pointName().isBlank() || m.pointName().length()>256 || m.pointName().chars().anyMatch(c->Character.isISOControl(c)||c==','))
    throw new ServiceException(1_070_000_400,"无效测点名");
  db.update("DELETE FROM iot_mapping WHERE tenant_id=? AND device_id=?",tenant,id);
  mappings.forEach(m->db.update("INSERT INTO iot_mapping(tenant_id,device_id,metric_key,name,point_name,unit) VALUES(?,?,?,?,?,?)",tenant,id,m.metricKey(),m.name(),m.pointName(),m.unit()));
  return mappings(tenant,id);
 }
 public List<Dashboard> dashboards(long tenant,boolean template) {
  return db.query("SELECT id,name,version,layout FROM iot_dashboard WHERE tenant_id=? AND is_template=? ORDER BY id LIMIT 100",
    (rs,n)->dashboard(rs.getLong("id"),rs.getString("name"),rs.getLong("version"),rs.getString("layout")),tenant,template);
 }
 public Dashboard dashboard(long tenant,long id) {
  return db.query("SELECT id,name,version,layout FROM iot_dashboard WHERE tenant_id=? AND id=?",
    (rs,n)->dashboard(rs.getLong("id"),rs.getString("name"),rs.getLong("version"),rs.getString("layout")),tenant,id)
    .stream().findFirst().orElseThrow(IotRepository::missing);
 }
 @Transactional
 public Dashboard saveDashboard(long tenant,Long id,Dashboard body,boolean template) {
  IotDashboardValidator.validate(body);
  if(id!=null) {
   dashboard(tenant,id);
   template=Boolean.TRUE.equals(db.queryForObject("SELECT is_template FROM iot_dashboard WHERE tenant_id=? AND id=?",Boolean.class,tenant,id));
  }
  if(template && body.widgets().stream().anyMatch(w->!"currentDevice".equals(w.binding().mode())))
   throw new ServiceException(1_070_000_400,"模板组件必须绑定当前设备");
  if(id==null && body.version()!=0) throw conflict();
  for(Widget widget:body.widgets()) for(Long deviceId:widget.binding().deviceIds()) device(tenant,deviceId);
  if(id==null) {
   id=db.queryForObject("SELECT nextval('iot_dashboard_seq')",Long.class);
   db.update("INSERT INTO iot_dashboard(id,tenant_id,name,version,layout,is_template) VALUES(?,?,?,0,?,?)",id,tenant,body.name(),encode(body.widgets()),template);
  } else {
   dashboard(tenant,id);
   if(db.update("UPDATE iot_dashboard SET name=?,layout=?,version=version+1 WHERE tenant_id=? AND id=? AND version=?",
     body.name(),encode(body.widgets()),tenant,id,body.version())!=1) throw conflict();
  }
  return dashboard(tenant,id);
 }
 public boolean deleteDashboard(long tenant,long id,long version) {
  dashboard(tenant,id);
  if(db.update("DELETE FROM iot_dashboard WHERE tenant_id=? AND id=? AND version=?",tenant,id,version)!=1) throw conflict();
  return true;
 }
 private Dashboard dashboard(Long id,String name,long version,String layout) {
  try { return new Dashboard(id,name,version,1,json.readValue(layout,new TypeReference<List<Widget>>(){})); }
  catch(Exception e) { throw new IllegalStateException("Invalid stored dashboard layout",e); }
 }
 public String encode(Object value) {
  try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid JSON",e); }
 }
}
