package app.lyng.prompt;

import app.lyng.event.SceneEvent;
import app.lyng.event.Visibility;
import app.lyng.markup.Beat;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一场戏里公开发生的事写成标明说话人的感知材料（从验证原型移植）。
 * 写法与人物、叙事者的输出相同；viewer 是正在感知的人物，能看到自己的心声。
 */
public final class Perception {

    private Perception() {}

    public static String of(List<SceneEvent> events, String viewer) {
        List<String> lines = new ArrayList<>();
        for (SceneEvent e : Visibility.publicEvents(events)) {
            switch (e.kind()) {
                case NARRATION -> lines.add("叙事：" + e.text());
                case USER -> lines.add(userLine(e));
                case CHARACTER -> characterLines(e, viewer, lines);
                default -> throw new IllegalStateException("不属于世界的事件: " + e.kind());
            }
        }
        return lines.isEmpty() ? PromptText.EMPTY : String.join("\n", lines);
    }

    private static String userLine(SceneEvent e) {
        return switch (e.action()) {
            case SceneEvent.WAIT -> e.speaker() + "没有开口。";
            case SceneEvent.SAY -> e.speaker() + "（说）：" + e.text();
            default -> e.speaker() + "（行）：" + e.text();
        };
    }

    private static void characterLines(SceneEvent e, String viewer, List<String> lines) {
        if (e.silent()) {
            lines.add(e.speaker() + "没有开口。");
        }
        for (Beat beat : e.beats()) {
            lines.add(e.speaker() + "（" + beat.tag() + "）：" + beat.text());
        }
        if (Visibility.seesInner(e, viewer)) {
            lines.add(e.speaker() + "（心声）：" + e.inner());
        }
    }

    /** 叙事者的感知：公开的事，没有任何人的心声。 */
    public static String forNarrator(List<SceneEvent> events) {
        return of(events, null);
    }
}
