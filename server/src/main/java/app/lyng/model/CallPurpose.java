package app.lyng.model;

import java.util.List;
import java.util.UUID;

/**
 * 一次调用的用途。写进 model_calls；真实模型看不到，确定性替身靠它决定输出。
 *
 * @param kind    opening | round | character | closing | recall | draft
 * @param subject 人物调用与回想是人物姓名，叙事者的 round 是用户这一轮的动作（说 / 做 / 静候），其余为 null
 * @param sceneId 属于哪一场戏；创作为 null
 * @param others  回想里要写“看待”的其他在场人物；其余调用为空
 */
public record CallPurpose(String kind, String subject, UUID sceneId, List<String> others) {

    public CallPurpose(String kind, String subject, UUID sceneId) {
        this(kind, subject, sceneId, List.of());
    }
}
