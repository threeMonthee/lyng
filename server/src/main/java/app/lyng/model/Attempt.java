package app.lyng.model;

import java.util.function.Function;

/** 一次尝试：边生成边收到片段，结束时解析整段；这次尝试作废（失败或要重试）时收到 abandon。 */
public interface Attempt<T> {

    default void onDelta(String delta) {}

    /** 解析整段输出；不合规抛 FormatError。 */
    T finish(String text);

    default void abandon() {}

    static <T> Attempt<T> whole(Function<String, T> parse) {
        return parse::apply;
    }
}
