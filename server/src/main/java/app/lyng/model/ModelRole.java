package app.lyng.model;

/** 模型配置的粒度：人物和叙事者各自可配置，创作与回想也各有一档。 */
public enum ModelRole {
    CHARACTER,
    NARRATOR,
    CREATION,
    RECALL;

    public String key() {
        return name().toLowerCase();
    }
}
