package app.lyng.model;

import java.util.function.Function;

/**
 * 一次生成尝试的调用方视角。每次尝试（含自动重试）都会得到一个新的 Attempt。
 *
 * @param <T> 解析后的结果
 */
public interface Attempt<T> {

    /** 正文片段到达；格式错误能当场判断的，抛 FormatError 中止这次生成。 */
    void onDelta(String delta);

    /** 生成结束，校验整体并返回结果；不合规抛 FormatError。 */
    T finish(String fullText);

    /** 这次尝试失败了（之后可能重试）：收回已经暂时推送出去的东西。 */
    void abandon();

    /** 不需要流式的调用：只在结束时解析。 */
    static <T> Attempt<T> whole(Function<String, T> parse) {
        return new Attempt<>() {
            @Override
            public void onDelta(String delta) {}

            @Override
            public T finish(String fullText) {
                return parse.apply(fullText);
            }

            @Override
            public void abandon() {}
        };
    }
}
