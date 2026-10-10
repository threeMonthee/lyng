package app.lyng.model;

/** 模型配置的粒度。 */
public enum Role {
    CHARACTER,
    /** 叙事者的开场与收尾。 */
    NARRATOR,
    /** 叙事者逐轮的决定：一两行短输出，默认关闭推理，免得拖住人物开口。 */
    NARRATOR_ROUND,
    CREATION,
    RECALL
}
