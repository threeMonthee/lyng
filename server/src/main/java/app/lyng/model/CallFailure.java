package app.lyng.model;

/** 一次模型调用最终失败（重试之后）。reason 可以直接给用户看。 */
public class CallFailure extends RuntimeException {

    public enum Kind {
        /** 空响应、截断、格式不合规、限流、服务器错误：可以重试。 */
        TECHNICAL,
        /** 服务商因内容审核拒绝。 */
        REFUSED,
        /** 密钥无效、余额不足、费用达到上限：重试之前要先处理原因。 */
        STOPPED
    }

    private final Kind kind;

    public CallFailure(Kind kind, String reason) {
        super(reason);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
