package app.lyng.model;

import java.util.Set;

/** 选用的模型与推理设置，写法 {@code 模型ID} 或 {@code 模型ID@推理设置}；没写推理设置就用模型默认。 */
public record Choice(String model, String reasoning) {

    private static final Set<String> REASONING = Set.of("off", "minimal", "low", "medium", "high");

    public Choice {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }
        if (reasoning != null && !REASONING.contains(reasoning)) {
            throw new IllegalArgumentException("推理设置只能是 " + String.join("、", REASONING) + "：" + reasoning);
        }
    }

    public static Choice parse(String spec) {
        int at = spec.indexOf('@');
        return at < 0 ? new Choice(spec.strip(), null)
                : new Choice(spec.substring(0, at).strip(), spec.substring(at + 1).strip());
    }

    public String spec() {
        return reasoning == null ? model : model + "@" + reasoning;
    }
}
