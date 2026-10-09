package app.lyng.model;

/**
 * @param cost            美元；接口没返回费用时为 null
 * @param provider        OpenRouter 实际路由到的服务商；同一个模型 ID 背后的量化与实现可能不同
 * @param firstTokenMs    首个正文片段到达的耗时
 */
public record Completion(String text, String finishReason, Double cost, Usage usage, String provider,
                         long firstTokenMs, long latencyMs) {}
