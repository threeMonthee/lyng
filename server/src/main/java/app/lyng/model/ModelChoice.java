package app.lyng.model;

import java.util.Set;

/**
 * 选用的模型与推理设置。文本写法 {@code 模型ID} 或 {@code 模型ID@推理设置}，
 * 与验证原型的参赛者写法一致；没写推理设置就用模型默认（开推理）。
 */
public record ModelChoice(String model, String reasoning) {

    private static final Set<String> REASONING = Set.of("off", "minimal", "low", "medium", "high");

    public ModelChoice {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }
        if (reasoning != null && !REASONING.contains(reasoning)) {
            throw new IllegalArgumentException("推理设置只能是 " + String.join("、", REASONING) + "：" + reasoning);
        }
    }

    public static ModelChoice parse(String spec) {
        int at = spec.indexOf('@');
        if (at < 0) {
            return new ModelChoice(spec.trim(), null);
        }
        return new ModelChoice(spec.substring(0, at).trim(), spec.substring(at + 1).trim());
    }

    public String spec() {
        return reasoning == null ? model : model + "@" + reasoning;
    }
}
