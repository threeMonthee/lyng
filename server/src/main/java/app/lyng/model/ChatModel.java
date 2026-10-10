package app.lyng.model;

import java.util.List;
import java.util.function.Consumer;

/** 一次补全。实现一律流式读取：边读边把文字片段交给 onDelta，读完返回整段结果。 */
public interface ChatModel {

    Completion complete(Request request, Consumer<String> onDelta);

    record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }
    }

    /**
     * 这次调用是做什么的。kind：opening / round / character / closing / recall / draft；
     * subject 是人物姓名或动作；names 在群戏一轮里是可选的人物，在回想里是在场的其他人物（替身据此作答）。
     */
    record Purpose(String kind, String subject, String sceneId, List<String> names) {
        public Purpose(String kind, String subject, String sceneId) {
            this(kind, subject, sceneId, List.of());
        }
    }

    record Request(Choice choice, List<Message> messages, int maxTokens, Purpose purpose) {}

    record Completion(String text, String finishReason, Double cost, String provider, Integer promptTokens,
                      Integer completionTokens, Integer reasoningTokens, long firstTokenMs, long latencyMs) {}
}
