package cn.iocoder.yudao.module.iot.service;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.iot.model.IotModels.*;
import java.util.*;
public final class IotDashboardValidator {
 private IotDashboardValidator() {}
 private static void check(boolean valid) { if(!valid) throw new ServiceException(1_070_000_400,"无效看板配置"); }
 public static void validate(Dashboard d) {
  check(d!=null && d.schemaVersion()==1 && d.version()>=0 && d.name()!=null && !d.name().isBlank() && d.name().length()<=100 && d.widgets()!=null && d.widgets().size()<=50);
  Set<String> ids=new HashSet<>();
  for(Widget w:d.widgets()) {
   check(w!=null && w.id()!=null && w.id().length()<=64 && ids.add(w.id()) && w.title()!=null && w.title().length()<=100);
   check(w.type()!=null && Set.of("line","gauge","stat","bar","table","timeline","image").contains(w.type()));
   Binding b=w.binding(); check(b!=null && b.mode()!=null && b.deviceIds()!=null && b.deviceIds().size()<=10 && b.deviceIds().stream().allMatch(id->id!=null && id>0));
   check(b.metricKey()!=null && b.metricKey().matches("[A-Za-z0-9_.-]{1,64}"));
   check(switch(b.mode()) { case "currentDevice" -> b.deviceIds().isEmpty(); case "fixed" -> b.deviceIds().size()==1; case "multiple" -> !b.deviceIds().isEmpty(); default -> false; });
   check(!"image".equals(w.type()) || !"multiple".equals(b.mode()));
   Grid g=w.grid(); check(g!=null && g.x()>=0 && g.y()>=0 && g.y()<=1000 && g.w()>0 && g.h()>0 && g.h()<=50 && g.x()+g.w()<=12);
   check(w.timeWindowSeconds()>=1 && w.timeWindowSeconds()<=604800);
   check(w.aggregation()!=null && Set.of("RAW","AVG","MIN","MAX","LAST").contains(w.aggregation()));
   check(w.unit()==null || w.unit().length()<=32);
   check(w.min()==null || Double.isFinite(w.min())); check(w.max()==null || Double.isFinite(w.max()));
   check(w.min()==null || w.max()==null || w.min()<w.max());
   check(w.imageUrl()==null || (w.imageUrl().length()<=512 && w.imageUrl().startsWith("/") && !w.imageUrl().startsWith("//") && !w.imageUrl().contains("\\") && !w.imageUrl().contains("..")));
   check(w.hotspots()==null || w.hotspots().size()<=50);
   if(w.hotspots()!=null) for(Hotspot h:w.hotspots()) check(h!=null && Double.isFinite(h.x()) && Double.isFinite(h.y()) && h.x()>=0 && h.x()<=100 && h.y()>=0 && h.y()<=100 && h.label()!=null && h.label().length()<=100 && (h.metricKey()==null || h.metricKey().matches("[A-Za-z0-9_.-]{1,64}")));
  }
 }
}
