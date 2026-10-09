package app.lyng.model;

import java.util.function.Consumer;

/** 一次补全。正文片段到达就交给 onDelta；onDelta 抛出的异常会中止生成并原样抛给调用方。 */
public interface ChatModel {

    Completion complete(ChatRequest request, Consumer<String> onDelta);
}
