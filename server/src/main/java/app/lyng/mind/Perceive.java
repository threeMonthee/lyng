package app.lyng.mind;

import app.lyng.store.Chats.Message;
import app.lyng.store.Scenes.Line;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把发生过的事写成感知材料：标明说话人的一行一行，写法与模型的输出相同。
 * viewer 是正在感知的人物，只看得到自己的心声；叙事者传 null，看不到任何人的心声。
 */
public final class Perceive {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final String EMPTY = "（还没有发生任何事）";

    private Perceive() {}

    /** 一行戏里的事；看不到的返回 null。 */
    public static String line(Line l, Map<UUID, String> names, UUID viewer) {
        String who = l.speakerId() == null ? null : names.get(l.speakerId());
        return switch (l.kind()) {
            case "narration", "closing" -> "叙事：" + l.text();
            case "say" -> who + "（说）：" + l.text();
            case "do" -> who + "（行）：" + l.text();
            case "wait" -> who + "没有开口。";
            case "inner" -> l.speakerId().equals(viewer) ? who + "（心声）：" + l.text() : null;
            default -> throw new IllegalStateException("未知的行：" + l.kind());
        };
    }

    public static String scene(List<Line> lines, Map<UUID, String> names, UUID viewer) {
        List<String> out = new ArrayList<>();
        for (Line l : lines) {
            String text = line(l, names, viewer);
            if (text != null) {
                out.add(text);
            }
        }
        return out.isEmpty() ? EMPTY : String.join("\n", out);
    }

    /** 一句私聊里的话；看不到的（别人的心声）返回 null。 */
    public static String message(Message m, Map<UUID, String> names, UUID viewer) {
        String who = names.get(m.speakerId());
        return switch (m.kind()) {
            case "say" -> who + "（说）：" + m.text();
            case "inner" -> m.speakerId().equals(viewer) ? who + "（心声）：" + m.text() : null;
            default -> throw new IllegalStateException("未知的话：" + m.kind());
        };
    }

    /** 私聊记录：隔了半小时以上，插一行时间。 */
    public static String chat(List<Message> messages, Map<UUID, String> names, UUID viewer) {
        List<String> out = new ArrayList<>();
        Instant last = null;
        for (Message m : messages) {
            String text = message(m, names, viewer);
            if (text == null) {
                continue;
            }
            if (last == null || Duration.between(last, m.createdAt()).toMinutes() >= 30) {
                out.add("——" + when(m.createdAt()) + "——");
            }
            last = m.createdAt();
            out.add(text);
        }
        return out.isEmpty() ? "（你们还没有说过话）" : String.join("\n", out);
    }

    /** 10月16日 晚上9:05 */
    public static String when(Instant at) {
        ZonedDateTime t = at.atZone(ZONE);
        int h = t.getHour();
        String part = h < 5 ? "凌晨" : h < 8 ? "清早" : h < 11 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
        int h12 = h > 12 ? h - 12 : h;
        return "%d月%d日 %s%d:%02d".formatted(t.getMonthValue(), t.getDayOfMonth(), part, h12, t.getMinute());
    }

    public static String date(Instant at) {
        ZonedDateTime t = at.atZone(ZONE);
        return "%d月%d日".formatted(t.getMonthValue(), t.getDayOfMonth());
    }
}
