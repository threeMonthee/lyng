package app.lyng.model;

/** 一个能流式生成的模型。失败抛 ModelException；回调抛出的 FormatError 原样传出。 */
public interface ChatModel {

    Completion complete(ModelRequest request, StreamListener listener);
}
