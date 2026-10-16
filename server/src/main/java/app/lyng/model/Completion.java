package app.lyng.model;

import java.util.List;

/** 一次请求的结果：正文、接口给出的思考、工具调用，以及记账需要的数字。 */
public record Completion(String text, String reasoning, List<ToolCall> toolCalls, String finishReason, Double cost,
                         String provider, Integer promptTokens, Integer completionTokens, Integer reasoningTokens,
                         long firstTokenMs, long latencyMs) {}
