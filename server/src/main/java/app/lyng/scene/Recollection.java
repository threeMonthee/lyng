package app.lyng.scene;

import java.util.Map;
import java.util.UUID;

/**
 * 一个人物对一场戏的回想：用 TA 自己的口吻。views 是对在场其他人物的看法（键为人物 id）。
 * 回想是整理产物，失败或丢失可以重新生成；原始经历永远在 scene_events 里。
 */
public record Recollection(UUID characterId, UUID sceneId, String status, String memory, Map<UUID, String> views,
                           String error) {

    public static final String DONE = "done";
    public static final String FAILED = "failed";

    public boolean done() {
        return DONE.equals(status);
    }
}
