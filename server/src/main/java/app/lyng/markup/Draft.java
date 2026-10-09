package app.lyng.markup;

import java.util.List;

/**
 * 一句话创作的草稿，也是预览页可手改、确认诞生时提交的内容。
 * 新世界时 world 非空；在已有世界里创建人物时 world 为 null。
 */
public record Draft(WorldDraft world, List<CharacterDraft> characters) {

    public record WorldDraft(String name, String publicText, String narratorText) {}

    public record CharacterDraft(String name, String profile) {}
}
