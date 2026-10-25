package cn.iocoder.yudao.framework.realdb.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 紫金桥文档第 8 至 9 页的错误码，HTTP 状态和业务 code 分别处理。 */
@Getter
@AllArgsConstructor
public enum RealDbErrorCodeEnum {

    TOKEN_REQUIRED(-1, "请指定 token"),
    SUCCESS(0, "请求成功"),
    DATABASE_FAILURE(1, "与数据库通信失败/请求项中有不存在的项或数据/其他错误"),
    DUPLICATE(2, "重复"),
    NOT_FOUND(3, "对象未找到"),
    PERMISSION_DENIED(4, "权限不够"),
    FORMAT_ERROR(5, "报文格式错"),
    DIRECTORY_NOT_EMPTY(6, "目录非空"),
    INVALID_NAME(7, "无效名称"),
    INVALID_RANGE(8, "量程上下限错"),
    DUPLICATE_IO_LINK(9, "IO 链接项重复"),
    INVALID_ALARM_LIMIT(10, "无效报警限值"),
    REDUNDANT_DIGITAL_ALARM(11, "DI 报警参数冗余"),
    DUPLICATE_DATABASE_LINK(12, "网络连接项重复"),
    INVALID_TAG_KIND(13, "点类型号错"),
    INVALID_HISTORY_PARAMETER(14, "历史项点参数错"),
    DUPLICATE_TAG_LINK_PARAMETER(15, "链接项点参数错"),
    DUPLICATE_INTERNAL_LINK(16, "内部链接项重复"),
    DUPLICATE_HISTORY(17, "历史项重复"),
    EXPRESSION_ERROR(18, "表达式错误"),
    SCRIPT_ERROR(19, "脚本错误"),
    EMPTY_NAME(20, "名称不能为空"),
    LOGIN_FAILED(21, "登陆失败"),
    DUPLICATE_JSON_TAG(22, "在提供的 Json 中点名重复"),
    SYSTEM_OBJECT_DELETE_DENIED(23, "不能删除系统对象"),
    INVALID_TAG_DIRECTORY(24, "点目录无效"),
    URL_PARAMETER_ERROR(25, "URL 请求参数错，缺少必要的参数"),
    VALUE_COUNT_MISMATCH(26, "变量与数值个数不相同"),
    INVALID_VARIABLE(27, "变量名错"),
    INVALID_JSON(28, "Json 报文格式错"),
    START_AFTER_END(29, "开始时间不能晚于结束时间"),
    TOO_MANY_HISTORY_ITEMS(30, "历史项过多"),
    INVALID_OR_UNRECORDED_VARIABLE(31, "变量错误或变量未存历史"),
    INVALID_TIME(32, "时间格式错误"),
    MISSING_VARIABLE_OR_TIME(33, "缺少变量或时间"),
    INVALID_HISTORY_INSERT(34, "插入历史格式错误"),
    INVALID_PACKET(35, "报文格式非法"),
    INVALID_CREDENTIALS(36, "用户名或密码错"),
    MISSING_TOKEN(37, "缺失 Token"),
    LOGOUT_FAILED(38, "登出失败"),
    UNSUPPORTED_REQUEST(39, "不支持的请求"),
    SQL_QUERY_ERROR(40, "SQL 查询错误"),
    MISSING_SQL_QUERY(41, "缺少查询属性"),
    LOGIN_REQUIRED(403, "必须先登录才可访问"),
    DATA_NOT_FOUND(404, "未找到对应数据错误"),
    FAILED(406, "Failed（未知错误，一般为请求数据或请求数据格式错误）");

    private final int code;
    private final String message;

}
