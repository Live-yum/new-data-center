package cn.iocoder.yudao.framework.realdb.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 点属性及其数值，数值保留原始数值或字符串类型。 */
@Data
@Accessors(chain = true)
public class RealDbValueDTO {

    private String name;
    private Object value;

}
