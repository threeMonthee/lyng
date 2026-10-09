package app.lyng.model;

/** 请求没有拿到正常响应。kind 决定网关怎么处理。 */
public class ModelException extends RuntimeException {

    public enum Kind {
        /** 429、5xx、网络抖动、超时：退避后重试一次。 */
        TRANSIENT,
        /** 服务商因内容审核拒绝：不重试。 */
        REFUSED,
        /** 401、402、其他拿不到响应的错误：不重试。 */
        FATAL
    }

    private final Kind kind;
    private final Integer status;

    public ModelException(Kind kind, String message, Integer status) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    public Integer status() {
        return status;
    }
}
