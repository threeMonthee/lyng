package app.lyng.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型接入配置，前缀 lyng.model。密钥只从环境读取（仓库根 .env 或进程环境）。
 *
 * @param backend           openrouter | fake（fake 是确定性替身，不发网络请求）
 * @param defaultModel      四种角色共用的默认模型
 * @param defaultReasoning  默认推理设置；空表示模型默认（开推理）
 * @param preferredProvider 首选服务商；空表示不指定。故障时仍允许回退
 * @param budgetUsd         累计费用硬上限（美元）；空表示不设
 */
@ConfigurationProperties("lyng.model")
public record ModelProperties(
        String backend,
        String apiKey,
        String baseUrl,
        String defaultModel,
        String defaultReasoning,
        String preferredProvider,
        int maxTokens,
        long backoffMs,
        int headerTimeoutSec,
        int idleTimeoutSec,
        Double budgetUsd) {

    public ModelChoice defaultChoice() {
        String reasoning = defaultReasoning == null || defaultReasoning.isBlank() ? null : defaultReasoning;
        return new ModelChoice(defaultModel, reasoning);
    }
}
