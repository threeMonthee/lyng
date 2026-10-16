package app.lyng.model;

/** 模型或服务商侧的失败。TRANSIENT 可重试；REFUSED 是服务商拒绝；FATAL 是密钥、余额、请求本身的问题。 */
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
