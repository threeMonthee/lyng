package app.lyng.scene;

import app.lyng.event.EventKind;
import app.lyng.event.SceneEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 从事件序列推出一场戏当前的状态：谁失败了没解决，开场有没有写出来。 */
public final class SceneStatus {

    private SceneStatus() {}

    /** 还没被重试解决的失败，按先后。 */
    public static List<SceneEvent> unresolvedFailures(List<SceneEvent> events) {
        Set<Integer> resolved = new HashSet<>();
        events.stream().filter(e -> e.kind() == EventKind.RESOLVED).forEach(e -> resolved.add(e.refSeq()));
        return events.stream().filter(e -> e.kind() == EventKind.FAILURE && !resolved.contains(e.seq())).toList();
    }

    public static boolean opened(List<SceneEvent> events) {
        return events.stream().anyMatch(e -> e.kind() == EventKind.NARRATION && e.round() == 0);
    }

    public static int lastRound(List<SceneEvent> events) {
        return events.stream().mapToInt(SceneEvent::round).max().orElse(0);
    }

    /** 最近一轮用户已经出手，但人物与叙事者都还没有结果（也没有失败）：通常是服务中途重启留下的。 */
    public static boolean dangling(List<SceneEvent> events) {
        if (!unresolvedFailures(events).isEmpty()) {
            return false;
        }
        SceneEvent last = events.stream().filter(e -> e.kind().isPublic()).reduce((a, b) -> b).orElse(null);
        return last != null && last.kind() == EventKind.USER;
    }
}
