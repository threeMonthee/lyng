package app.lyng.prompt;

import java.util.List;

/**
 * 叙事者看到的一切（除了这一场到现在的事件）：两份世界设定、在场人物的档案、开场处境、上一场的公开经过。
 * 看不到任何人的心声与回想。
 *
 * @param possessed 被附身的人物；旁观时为 null（旁观这一刀不做）
 * @param tone      日常相处 | 冒险
 * @param scale     含蓄 | 成人向
 * @param previous  最近一场已结束的戏的公开经过；没有则为 null
 */
public record NarratorContext(String worldPublic, String worldNarrator, List<String> profiles, String possessed,
                              String tone, String scale, String opening, PastScene previous) {}
