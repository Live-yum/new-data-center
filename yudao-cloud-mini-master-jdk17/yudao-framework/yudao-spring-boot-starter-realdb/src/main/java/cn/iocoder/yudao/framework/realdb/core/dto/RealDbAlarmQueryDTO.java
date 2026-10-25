package cn.iocoder.yudao.framework.realdb.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** 实时报警或历史报警查询。开始和结束时间必须同时提供。 */
@Data
@Accessors(chain = true)
public class RealDbAlarmQueryDTO {

    private Integer realAlarmCount;
    private String org;
    private Integer unit;
    private Integer level;
    private List<String> tags;
    private Boolean groupByTag;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

}
