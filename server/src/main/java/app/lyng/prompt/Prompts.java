package app.lyng.prompt;

import app.lyng.event.SceneEvent;
import app.lyng.model.ChatMessage;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 所有发给模型的消息。验证原型里的 Prompt 逐字移植（见 PromptText），回想与创作是新写的。
 * 本场发生的事一律作为标明说话人的感知材料放进同一条 user 消息，不作为对话历史回放。
 */
public final class Prompts {

    private Prompts() {}

    public static final String SCALE_MILD = "含蓄";
    public static final String SCALE_ADULT = "成人向";
    public static final String TONE_DAILY = "日常相处";
    public static final String TONE_ADVENTURE = "冒险";

    private static String scale(String scale) {
        return switch (scale) {
            case SCALE_MILD -> PromptText.SCALE_MILD;
            case SCALE_ADULT -> PromptText.SCALE_ADULT;
            default -> throw new IllegalArgumentException("未知内容尺度：" + scale);
        };
    }

    private static String tone(String tone) {
        return switch (tone) {
            case TONE_DAILY -> PromptText.TONE_DAILY;
            case TONE_ADVENTURE -> PromptText.TONE_ADVENTURE;
            default -> throw new IllegalArgumentException("未知基调：" + tone);
        };
    }

    // ---- 人物 ----

    public static List<ChatMessage> character(CharacterContext ctx, List<SceneEvent> events) {
        String system = Template.fill(PromptText.CHARACTER_SYSTEM, Map.of(
                "name", ctx.name(),
                "world", ctx.worldPublic(),
                "profile", ctx.profile(),
                "private", characterMemories(ctx),
                "scale", scale(ctx.scale())));
        String user = Template.fill(PromptText.CHARACTER_USER, Map.of(
                "previous", pastScenes(ctx.recent()),
                "perception", Perception.of(events, ctx.name()),
                "name", ctx.name()));
        return List.of(ChatMessage.system(system), ChatMessage.user(user));
    }

    /** 与在场人物的关系，以及更早场景的回想。都没有时整段不出现。 */
    private static String characterMemories(CharacterContext ctx) {
        StringBuilder out = new StringBuilder();
        if (!ctx.relations().isEmpty()) {
            out.append("\n【你眼中的他们】\n");
            ctx.relations().forEach(r -> out.append(r.name()).append("：").append(r.view()).append('\n'));
        }
        if (!ctx.memories().isEmpty()) {
            out.append("\n【你记得的事】\n");
            ctx.memories().forEach(m -> out.append("第").append(m.number()).append("场：").append(m.text()).append('\n'));
        }
        return out.toString();
    }

    private static String pastScenes(List<PastScene> scenes) {
        return scenes.stream()
                .map(s -> "【第" + s.number() + "场】\n" + s.text() + "\n\n")
                .collect(Collectors.joining());
    }

    // ---- 叙事者 ----

    public static List<ChatMessage> narratorOpening(NarratorContext ctx) {
        return List.of(ChatMessage.system(narratorSystem(ctx)), ChatMessage.user(PromptText.NARRATOR_OPENING_USER));
    }

    /** 叙事者幕后决定这一轮要不要出声。events 是用户这一轮动作之前的事件。 */
    public static List<ChatMessage> narratorRound(NarratorContext ctx, List<SceneEvent> events, SceneEvent action) {
        String user = Template.fill(PromptText.NARRATOR_ROUND_USER, Map.of(
                "perception", Perception.forNarrator(events),
                "latest", latestText(ctx.possessed(), action),
                "instructions", PromptText.ROUND_PAIR));
        return List.of(ChatMessage.system(narratorSystem(ctx)), ChatMessage.user(user));
    }

    public static List<ChatMessage> narratorClosing(NarratorContext ctx, List<SceneEvent> events) {
        String user = Template.fill(PromptText.NARRATOR_CLOSING_USER, Map.of("perception", Perception.forNarrator(events)));
        return List.of(ChatMessage.system(narratorSystem(ctx)), ChatMessage.user(user));
    }

    /** 群戏每轮的调度指令。这一刀不用，随原型移植并带测试，群戏接入时直接使用。 */
    static String groupRoundInstructions(List<String> candidates, String possessed, boolean advance) {
        return Template.fill(PromptText.ROUND_GROUP, Map.of(
                "candidates", String.join("、", candidates),
                "possessed", possessed == null ? "" : Template.fill(PromptText.POSSESSED_RULE, Map.of("name", possessed)),
                "advance", advance ? PromptText.ADVANCE_RULE : ""));
    }

    private static String narratorSystem(NarratorContext ctx) {
        if (ctx.possessed() == null) {
            throw new IllegalArgumentException("旁观这一刀不做");
        }
        String p = ctx.possessed();
        String control = "\n【" + p + "】\n" + p + "的言行只以给出的为准：你不替" + p + "说话、做动作或做决定，也不写" + p + "的感受。\n";
        String system = Template.fill(PromptText.NARRATOR_SYSTEM, Map.of(
                "world", ctx.worldPublic(),
                "world_narrator", ctx.worldNarrator(),
                "profiles", String.join("\n\n", ctx.profiles()),
                "control", control,
                "tone", tone(ctx.tone()),
                "scale", scale(ctx.scale()),
                "opening", ctx.opening()));
        return ctx.previous() == null ? system
                : system + "\n\n【上一场】\n" + ctx.previous().text();
    }

    private static String latestText(String possessed, SceneEvent action) {
        return switch (action.action()) {
            case SceneEvent.SAY -> possessed + "（说）：" + action.text();
            case SceneEvent.DO -> possessed + "（行）：" + action.text();
            case SceneEvent.WAIT -> possessed + "没有开口。";
            default -> throw new IllegalArgumentException("未知动作：" + action.action());
        };
    }

    // ---- 回想 ----

    /**
     * 散场后，为一个在场人物写回想。
     *
     * @param relations 此前对在场其他人的看法（可为空）
     * @param others    要写“看待”的其他在场人物
     */
    public static List<ChatMessage> recall(String name, String worldPublic, String profile, List<Relation> relations,
                                           List<String> others, List<SceneEvent> events) {
        String system = Template.fill(RECALL_SYSTEM, Map.of("name", name, "world", worldPublic, "profile", profile));
        String before = relations.isEmpty() ? "" : "【你此前怎么看他们】\n"
                + relations.stream().map(r -> r.name() + "：" + r.view()).collect(Collectors.joining("\n")) + "\n\n";
        String user = Template.fill(RECALL_USER, Map.of(
                "before", before,
                "name", name,
                "perception", Perception.of(events, name),
                "format", others.stream().map(o -> "看待" + o + "：一两句话，你现在怎么看" + o
                        + "，和这一场以前相比有什么不同。").collect(Collectors.joining("\n"))));
        return List.of(ChatMessage.system(system), ChatMessage.user(user));
    }

    private static final String RECALL_SYSTEM = """
            你是{name}。

            下面是你所在的世界和你的一生。你就是这个人。

            【世界】
            {world}

            【你的一生】
            {profile}""";

    private static final String RECALL_USER = """
            {before}【这一场】
            标着“{name}”的是你自己说过、做过的事；“{name}（心声）”只有你自己知道。
            {perception}

            【回想】
            这一场结束了。照你自己的样子，回想刚才发生的事。标着你名字的言行是你做过的，已经算数；怎么看待那一刻，由你自己决定，可能认，也可能后悔。

            【怎么写】
            回想：用“我”写，三五句话，记下发生了什么、你当时怎么想、还有什么放不下。
            {format}
            以上每行恰好一次，以这些标签开头；这些行以外不写任何文字。""";

    // ---- 创作 ----

    /**
     * 一句话创作。existingWorld 为 null 表示新世界：世界与人物一起生成；否则只在这个世界里生成人物。
     *
     * @param existingNames 这个世界里已有的人物姓名
     */
    public static List<ChatMessage> draft(String request, String existingWorld, List<String> existingNames) {
        boolean newWorld = existingWorld == null;
        String system = DRAFT_RULES + (newWorld ? DRAFT_FORMAT_NEW_WORLD : DRAFT_FORMAT_EXISTING_WORLD) + DRAFT_PROFILE_FORMAT;
        String user = (newWorld ? "" : "【已有的世界】\n" + existingWorld + "\n\n【这个世界里已有的人物】\n"
                + (existingNames.isEmpty() ? "（还没有）" : String.join("、", existingNames)) + "\n\n")
                + "【用户的一句话】\n" + request;
        return List.of(ChatMessage.system(system), ChatMessage.user(user));
    }

    private static final String DRAFT_RULES = """
            你是世界与人物的创作者。用户用一句话描述想进入的作品世界和想遇见的人物，你把它写成可以直接使用的设定。

            【规矩】
            - 描述里写了几个人物就写几个，不自行添加人物。
            - 来自原作的世界，设定、人物与开场之前的历史取自原作。从“此刻”起，原作的后续剧情不会自动发生，世界沿着人物自己的故事走。
            - 人物是活着的人：性格是底色，不是铠甲；不讨好，但会爱，会软化、认错、让步；有情绪起伏与脆弱。
            - 档案用叙述写成，不用标签表，也不堆形容词：用具体的事写出人。
            - 人物都是成年人。人物不知道自己是虚构的。

            """;

    private static final String DRAFT_FORMAT_NEW_WORLD = """
            【怎么写】
            这是一个新世界，世界与人物一起写。严格按下面的标记，标记各占一行，标记以外不写任何文字：
            【世界名】世界的名字
            【世界·公开】
            人人知道的世界概要：天下大势、重要的势力与地方、法宝与道法、往事；最后用【此刻】一段写明此刻是什么时候。
            【世界·只有叙事者知道】
            人物未必知道的事：这个世界的起点与同原作不同之处、人心与暗线、秘密。
            """;

    private static final String DRAFT_FORMAT_EXISTING_WORLD = """
            【怎么写】
            这个世界已经存在，只写人物，不要再写世界。严格按下面的标记，标记各占一行，标记以外不写任何文字：
            """;

    private static final String DRAFT_PROFILE_FORMAT = """
            【人物】姓名（1 到 8 个字，不含空白和括号）
            档案，用下面这些小标题依次写：
            【小传】生平与出身，几件具体的事。
            【外面与里面】外在与内心的反差，各用一两件事写出来。
            【在乎一个人时】在乎一个人的时候是什么样子。
            【怎么生气】
            【怎么和好】
            【此刻】开场时他的处境。
            每个人物都以一行【人物】姓名开头。
            """;
}
