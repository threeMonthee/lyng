package app.lyng.model;

/** 流式回调。content 可以抛 FormatError，让这次尝试立刻作废。 */
public interface StreamListener {

    StreamListener NONE = delta -> {};

    void content(String delta);

    default void reasoning(String delta) {}
}
