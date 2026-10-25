package cn.iocoder.yudao.framework.realdb.core.exception;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

/**
 * 紫金桥协议/传输异常。异常消息不包含 token、密码、请求 URL 或响应正文。
 * 响应正文可能含有组态密码，只能按业务需要访问，不应直接记录到日志。
 */
@Getter
public class RealDbException extends RuntimeException {

    private final Integer httpStatus;
    private final Integer code;
    private final JsonNode response;
    private final String rawResponse;
    /** 超时或传输中断时，写操作可能已经完成，不得据此自动重试。 */
    private final boolean outcomeUnknown;

    public RealDbException(String message, Integer httpStatus, Integer code, JsonNode response,
                           String rawResponse, boolean outcomeUnknown) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.response = response;
        this.rawResponse = rawResponse;
        this.outcomeUnknown = outcomeUnknown;
    }

}
