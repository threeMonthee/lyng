package app.lyng.prompt;

import java.util.List;

/**
 * 人物开口时看到的一切（除了这一场到现在的事件）：档案、世界的公共部分、与在场人物的关系、
 * 更早场景的回想、近期场景的原文。
 *
 * @param scale 内容尺度：含蓄 | 成人向
 */
public record CharacterContext(String name, String worldPublic, String profile, String scale, List<Relation> relations,
                               List<Memory> memories, List<PastScene> recent) {}
