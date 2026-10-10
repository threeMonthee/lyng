package app.lyng.model;

/** 请求没拿到可用的响应。TRANSIENT 可以重试；REFUSED 是服务商因内容拒绝；FATAL 是密钥、余额或请求本身的问题。 */
public class ModelException extends RuntimeException {

    public enum Kind { TRANSIENT, REFUSED, FATAL }

    private final Kind kind;

    public ModelException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
