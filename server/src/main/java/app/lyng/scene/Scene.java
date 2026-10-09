package app.lyng.scene;

import java.time.Instant;
import java.util.UUID;

/**
 * 一场戏的元信息。
 *
 * @param number       该用户的第几场
 * @param possessedId  被附身的人物；旁观时为 null（旁观这一刀不做）
 * @param tone         日常相处 | 冒险
 * @param scale        含蓄 | 成人向
 * @param setup        用户写的开场处境，只给叙事者看
 */
public record Scene(UUID id, int number, UUID worldId, UUID possessedId, String tone, String scale, String setup,
                    String status, Instant startedAt, Instant endedAt) {

    public static final String OPEN = "open";
    public static final String ENDED = "ended";

    public boolean ended() {
        return ENDED.equals(status);
    }
}
