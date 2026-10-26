package cn.iocoder.yudao.module.iot;
import cn.iocoder.yudao.module.iot.model.IotModels.*;
import cn.iocoder.yudao.module.iot.service.*;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class IotDashboardValidatorTest {
 Widget widget(String mode,List<Long> ids,String image) { return new Widget("w","W",image==null?"line":"image",new Binding(mode,ids,"temperature"),new Grid(0,0,6,4),"C",3600,"AVG",0d,100d,image,List.of()); }
 Dashboard dashboard(Widget widget) { return new Dashboard(null,"Dashboard",0,1,List.of(widget)); }
 @Test void acceptsCurrentFixedAndMultiple() {
  for(Widget w:List.of(widget("currentDevice",List.of(),"/images/pump.svg"),widget("fixed",List.of(1L),null),widget("multiple",List.of(1L,2L),null))) assertDoesNotThrow(()->IotDashboardValidator.validate(dashboard(w)));
 }
 @Test void rejectsInvalidBindingsAndExternalImages() {
  for(Widget w:List.of(widget("fixed",List.of(),null),widget("currentDevice",List.of(1L),null),widget("multiple",List.of(),null),widget("fixed",List.of(1L),"https://evil.test/x"),widget("fixed",List.of(1L),"//evil.test/x"))) assertThrows(ServiceException.class,()->IotDashboardValidator.validate(dashboard(w)));
 }
 @Test void rejectsUnknownSchemaAndDuplicateWidgetIds() {
  assertThrows(ServiceException.class,()->IotDashboardValidator.validate(new Dashboard(null,"D",0,2,List.of())));
  Widget w=widget("currentDevice",List.of(),null);
  assertThrows(ServiceException.class,()->IotDashboardValidator.validate(new Dashboard(null,"D",0,1,List.of(w,w))));
 }
 @Test void csvEscapesQuotesAndSpreadsheetFormulas() {
  String csv=IotExportService.csv(new QueryResult("REALDB",List.of(new Series(1L,"t","C",List.of(new Point("time","  =HYPERLINK(\"evil\")","UNKNOWN"))))));
  assertTrue(csv.contains("'  =HYPERLINK"));assertTrue(csv.contains("\"\"evil\"\""));
 }
}
