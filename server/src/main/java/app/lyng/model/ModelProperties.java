package app.lyng.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型配置，只从环境读取。各用途的模型写成“模型ID@推理设置”：推理设置是 off / low / medium / high，
 * 省略表示用模型默认；只写“@off”表示默认模型、关推理；留空表示完全跟随默认模型。
 * fakeDelayMs 只给确定性替身用：每段流式内容之间停一下，让界面像真的在生成。
 */
@ConfigurationProperties("lyng.model")
public record ModelProperties(String backend, String apiKey, String baseUrl, String provider, String defaultModel,
                              String character, String narrator, String narratorRound, String check,
                              String consolidate, String create, int maxTokens, long backoffMs,
                              int headerTimeoutSec, int idleTimeoutSec, Double budgetUsd,
                              long fakeDelayMs) {

    public record Choice(String model, String reasoning) {

        public String spec() {
            return reasoning == null ? model : model + "@" + reasoning;
        }
    }

    public Choice choice(Purpose purpose) {
        String spec = switch (purpose.role()) {
            case "character" -> character;
            case "narrator" -> narrator;
            case "narrator-round" -> narratorRound;
            case "check" -> check;
            case "consolidate" -> consolidate;
            case "create" -> create;
            default -> throw new IllegalStateException("未知角色：" + purpose.role());
        };
        if (spec == null || spec.isBlank()) {
            return new Choice(defaultModel, null);
        }
        int at = spec.indexOf('@');
        String model = at < 0 ? spec : spec.substring(0, at);
        String reasoning = at < 0 ? null : spec.substring(at + 1);
        return new Choice(model.isBlank() ? defaultModel : model, reasoning == null || reasoning.isBlank() ? null : reasoning);
    }
}
