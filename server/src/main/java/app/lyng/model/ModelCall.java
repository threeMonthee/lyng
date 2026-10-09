package app.lyng.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** model_calls 的一行：每次尝试一行，含失败的。 */
public record ModelCall(
        Long id,
        UUID userId,
        UUID sceneId,
        String kind,
        String subject,
        String model,
        String reasoning,
        String provider,
        String outcome,
        String error,
        BigDecimal cost,
        Long latencyMs,
        Long firstTokenMs,
        Integer promptTokens,
        Integer completionTokens,
        Integer reasoningTokens,
        String finishReason,
        Instant createdAt) {}
