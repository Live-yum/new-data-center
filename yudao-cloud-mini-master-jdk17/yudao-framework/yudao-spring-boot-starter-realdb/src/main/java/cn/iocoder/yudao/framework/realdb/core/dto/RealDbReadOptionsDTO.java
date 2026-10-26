package cn.iocoder.yudao.framework.realdb.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 实时值、历史值的读取选项。 */
@Data
@Accessors(chain = true)
public class RealDbReadOptionsDTO {

    /** 小数位数，0 至 9；null 使用服务端默认值。 */
    private Integer decimal;
    private Boolean valueOnly;
    /** 仅节点实时数据接口使用。 */
    private Boolean recursion;

}
