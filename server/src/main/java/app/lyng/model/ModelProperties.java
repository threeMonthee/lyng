package app.lyng.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 模型配置，全部来自环境（见 application.yml）。各角色没单独指定就用 defaultSpec。 */
@ConfigurationProperties("lyng.model")
public record ModelProperties(
        String backend,
        String apiKey,
        String baseUrl,
        String defaultSpec,
        String character,
        String narrator,
        String narratorRound,
        String creation,
        String recall,
        String provider,
        Double budgetUsd,
        int maxTokens,
        long backoffMs,
        int headerTimeoutSec,
        int idleTimeoutSec,
        long fakeDelayMs) {

    public Choice choice(Role role) {
        String own = switch (role) {
            case CHARACTER -> character;
            case NARRATOR -> narrator;
            case NARRATOR_ROUND -> narratorRound;
            case CREATION -> creation;
            case RECALL -> recall;
        };
        if (own != null && !own.isBlank()) {
            return Choice.parse(own);
        }
        Choice base = Choice.parse(defaultSpec);
        return role == Role.NARRATOR_ROUND ? new Choice(base.model(), "off") : base;
    }
}
