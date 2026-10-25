package cn.iocoder.yudao.framework.realdb.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** 一段时间内的历史数据查询。 */
@Data
@Accessors(chain = true)
public class RealDbHistoryQueryDTO {

    private List<String> names;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 采样间隔，单位毫秒，必须大于 0。 */
    private Long interval;
    private RealDbReadOptionsDTO options;

}
