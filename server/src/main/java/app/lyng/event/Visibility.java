package app.lyng.event;

import java.util.List;

/**
 * 人物看到什么，由这条规则从一场戏的事件里筛出（从验证原型移植）。
 *
 * <ul>
 *   <li>叙事、用户的说 / 做、人物的行与说是公开的，在场的人都能感知；
 *   <li>心声只有说话的人自己能感知；
 *   <li>技术失败和失败已解决的标记不属于世界，任何人物都感知不到。
 * </ul>
 *
 * <p>双人戏里在场者不变，所以这里不需要“当时谁在场”；群戏里人物会离开时，再加这一条。
 */
public final class Visibility {

    private Visibility() {}

    public static List<SceneEvent> publicEvents(List<SceneEvent> events) {
        return events.stream().filter(e -> e.kind().isPublic()).toList();
    }

    /** viewer 能否看到这个事件里的心声。 */
    public static boolean seesInner(SceneEvent event, String viewer) {
        return event.kind() == EventKind.CHARACTER && event.speaker().equals(viewer);
    }
}
