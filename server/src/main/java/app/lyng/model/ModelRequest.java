package app.lyng.model;

import java.util.List;
import java.util.Map;

/**
 * 一次请求。meta 不发给服务商，只给确定性替身看（例如人物的名字、可选的人），让替身写出合规的输出。
 */
public record ModelRequest(Purpose purpose, String model, String reasoning, List<Msg> messages, List<Tool> tools,
                           int maxTokens, Map<String, Object> meta) {}
