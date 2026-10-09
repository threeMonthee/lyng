package app.lyng.model;

/** 模型配置的粒度：人物和叙事者各自可配置，创作与回想也各有一档。 */
public enum ModelRole {
    CHARACTER,
    /** 叙事者的开场与收尾。 */
    NARRATOR,
    /** 叙事者逐轮的幕后决定（要不要出声）：一行短输出，默认关闭推理，免得它拖住下一轮。 */
    NARRATOR_ROUND("off"),
    CREATION,
    RECALL;

    private final String defaultReasoning;

    ModelRole() {
        this(null);
    }

    ModelRole(String defaultReasoning) {
        this.defaultReasoning = defaultReasoning;
    }

    public String key() {
        return name().toLowerCase();
    }

    /** 这个角色固定的默认推理设置；null 表示跟随全局默认。 */
    public String defaultReasoning() {
        return defaultReasoning;
    }
}
