package cn.iocoder.yudao.module.iot;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.iot.model.IotModels.ProcessBinding;
import cn.iocoder.yudao.module.iot.model.IotModels.ProcessScene;
import cn.iocoder.yudao.module.iot.service.IotProcessValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class IotProcessValidatorTest {
    private List<ProcessBinding> empty() {
        return IntStream.rangeClosed(1,4).mapToObj(i -> new ProcessBinding("slot-"+i,null,List.of(),"")).toList();
    }
    private ProcessScene scene(String template, List<ProcessBinding> bindings, int refresh, int stale) {
        return new ProcessScene(null,"现场页面","",0,1,template,bindings,refresh,stale);
    }
    private void invalid(ProcessScene scene) { assertThrows(ServiceException.class, () -> IotProcessValidator.validate(scene)); }

    @Test void allFiveTemplatesAcceptExplicitUnboundDrafts() {
        for (String template : List.of("separation","gas","storage","utilities","offshore"))
            assertDoesNotThrow(() -> IotProcessValidator.validate(scene(template,empty(),5,120)));
    }
    @Test void rejectsMalformedTemplateVersionTimingAndText() {
        invalid(null);invalid(scene("unknown",empty(),5,120));invalid(scene(null,empty(),5,120));
        invalid(scene("gas",empty(),4,120));invalid(scene("gas",empty(),301,500));
        invalid(scene("gas",empty(),30,29));invalid(scene("gas",empty(),5,3601));
        invalid(new ProcessScene(null," ","",0,1,"gas",empty(),5,120));
        invalid(new ProcessScene(null,"x".repeat(101),"",0,1,"gas",empty(),5,120));
        invalid(new ProcessScene(null,"A","x".repeat(501),0,1,"gas",empty(),5,120));
        invalid(new ProcessScene(null,"A","",-1,1,"gas",empty(),5,120));
        invalid(new ProcessScene(null,"A","",0,2,"gas",empty(),5,120));
    }
    @Test void rejectsMissingDuplicateAndUnknownSlots() {
        invalid(scene("gas",null,5,120));invalid(scene("gas",empty().subList(0,3),5,120));
        for (ProcessBinding bad : Arrays.asList(null,new ProcessBinding("slot-2",null,List.of(),""),
                new ProcessBinding("unsafe",null,List.of(),""),new ProcessBinding(null,null,List.of(),""))) {
            List<ProcessBinding> bindings=new ArrayList<>(empty());bindings.set(0,bad);
            invalid(scene("gas",bindings,5,120));
        }
    }
    @Test void rejectsUnboundMetricsAndMalformedBoundMetrics() {
        for (ProcessBinding bad : List.of(new ProcessBinding("slot-1",null,List.of("t"),"t"),
                new ProcessBinding("slot-1",null,List.of(),"t"),new ProcessBinding("slot-1",0L,List.of("t"),"t"),
                new ProcessBinding("slot-1",1L,List.of(),""),new ProcessBinding("slot-1",1L,List.of("t","t"),"t"),
                new ProcessBinding("slot-1",1L,List.of("t"),"other"),new ProcessBinding("slot-1",1L,List.of("raw,tag"),"raw,tag"),
                new ProcessBinding("slot-1",1L,null,"t"),new ProcessBinding("slot-1",1L,List.of("t"),null),
                new ProcessBinding("slot-1",1L,Arrays.asList((String)null),"t"))) {
            List<ProcessBinding> bindings=new ArrayList<>(empty());bindings.set(0,bad);
            invalid(scene("gas",bindings,5,120));
        }
    }
    @Test void enforcesPerSlotAndOverallSeriesLimits() {
        List<String> keys=IntStream.range(0,20).mapToObj(i -> "m"+i).toList();
        List<ProcessBinding> bindings=new ArrayList<>(empty());
        bindings.set(0,new ProcessBinding("slot-1",1L,keys,"m0"));
        IotProcessValidator.validate(scene("gas",bindings,5,120));
        bindings.set(1,new ProcessBinding("slot-2",1L,keys,"m0"));
        bindings.set(2,new ProcessBinding("slot-3",1L,keys,"m0"));
        invalid(scene("gas",bindings,5,120));
        bindings=new ArrayList<>(empty());
        bindings.set(0,new ProcessBinding("slot-1",1L,IntStream.range(0,21).mapToObj(i -> "m"+i).toList(),"m0"));
        invalid(scene("gas",bindings,5,120));
    }
}
