package cn.iocoder.yudao.framework.realdb.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** 一个点属性的多时刻历史值。 */
@Data
@Accessors(chain = true)
public class RealDbHistoryValueDTO {

    private String name;
    private List<Value> values;

    @Data
    @Accessors(chain = true)
    public static class Value {
        /** 紫金桥服务器本地时间，毫秒精度，不做时区转换。 */
        private LocalDateTime time;
        private Object value;
    }

}
