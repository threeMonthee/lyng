package app.lyng.event;

public enum EventKind {
    /** 叙事者的文字：开场、裁定、环境、收尾。 */
    NARRATION,
    /** 被附身人物的说 / 做 / 静候：用户的原文。 */
    USER,
    /** AI 人物的一次反应：行与说（公开）、心声（只有本人）。 */
    CHARACTER,
    /** 某一步技术失败。不属于世界，任何人物都感知不到。 */
    FAILURE,
    /** 某个失败已被重试解决（refSeq 指向那个失败）。不属于世界。 */
    RESOLVED;

    public boolean isPublic() {
        return this == NARRATION || this == USER || this == CHARACTER;
    }
}
