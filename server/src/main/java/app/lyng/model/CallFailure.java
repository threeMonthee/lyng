package app.lyng.model;

/** 一次调用最终失败（已按规则重试过）。调用方把它呈现成技术失败，绝不当成人物的沉默。 */
public class CallFailure extends RuntimeException {

    public CallFailure(String message) {
        super(message);
    }
}
