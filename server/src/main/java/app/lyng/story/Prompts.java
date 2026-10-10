package app.lyng.story;

import app.lyng.model.ChatModel.Message;
import app.lyng.store.Store.Line;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 所有发给模型的消息。人物与叙事者的 Prompt 从验证原型 validation/lab/prompts.py 逐字移植，回想与创作是产品新写的。
 * 改动这里会改变快照，受影响的行为结论不继承。
 *
 * <p>一场戏里发生的事一律写成标明说话人的感知材料，放进同一条 user 消息，不作为对话历史回放。
 */
public final class Prompts {

    private Prompts() {}

    public static final String SCALE_MILD = "含蓄";
    public static final String SCALE_ADULT = "成人向";
    public static final String TONE_DAILY = "日常相处";
    public static final String TONE_ADVENTURE = "冒险";

    /** 对某人的看法。 */
    public record View(String name, String text) {}

    /** 一场已经结束的戏：回想或原文。 */
    public record Past(int number, String text) {}

    /** 人物开口时看到的自己：世界、档案、对在场其他人的看法、更早场景的回想、最近几场的原文。 */
    public record Persona(String name, String worldPublic, String profile, String scale, List<View> views,
                          List<Past> memories, List<Past> recent) {}

    /** 叙事者看到的舞台。possessed 为空表示旁观，此时镜头跟随 follow。previous 是这个世界上一场的公开经过。 */
    public record Stage(String worldPublic, String worldNarrator, List<String> profiles, String possessed, String follow,
                        String tone, String scale, String setup, Past previous) {}

    // ---- 感知材料 ----

    /**
     * 把这一场公开发生的事写成感知材料，写法与模型的输出相同。viewerId 是正在感知的人物，能看到自己的心声；
     * 叙事者传 null，看不到任何人的心声。
     */
    public static String perceive(List<Line> lines, Map<String, String> names, String viewerId) {
        List<String> out = new ArrayList<>();
        for (Line l : lines) {
            String who = l.speakerId() == null ? null : names.get(l.speakerId());
            switch (l.kind()) {
                case "narration" -> out.add("叙事：" + l.text());
                case "say" -> out.add(who + "（说）：" + l.text());
                case "do" -> out.add(who + "（行）：" + l.text());
                case "wait" -> out.add(who + "没有开口。");
                case "inner" -> {
                    if (l.speakerId().equals(viewerId)) {
                        out.add(who + "（心声）：" + l.text());
                    }
                }
                default -> throw new IllegalStateException("未知的行：" + l.kind());
            }
        }
        return out.isEmpty() ? EMPTY : String.join("\n", out);
    }

    // ---- 人物 ----

    public static List<Message> character(Persona p, String perception) {
        String system = fill(CHARACTER_SYSTEM, Map.of(
                "name", p.name(),
                "world", p.worldPublic(),
                "profile", p.profile(),
                "private", characterMemories(p),
                "scale", scale(p.scale())));
        String previous = p.recent().stream()
                .map(s -> "【第" + s.number() + "场】\n" + s.text() + "\n\n")
                .collect(Collectors.joining());
        String user = fill(CHARACTER_USER, Map.of("previous", previous, "perception", perception, "name", p.name()));
        return List.of(Message.system(system), Message.user(user));
    }

    /** 对在场其他人的看法，以及更早场景的回想。都没有时整段不出现。 */
    private static String characterMemories(Persona p) {
        StringBuilder out = new StringBuilder();
        if (!p.views().isEmpty()) {
            out.append("\n【你眼中的他们】\n");
            p.views().forEach(v -> out.append(v.name()).append("：").append(v.text()).append('\n'));
        }
        if (!p.memories().isEmpty()) {
            out.append("\n【你记得的事】\n");
            p.memories().forEach(m -> out.append("第").append(m.number()).append("场：").append(m.text()).append('\n'));
        }
        return out.toString();
    }

    // ---- 叙事者 ----

    public static List<Message> narratorOpening(Stage s) {
        return List.of(Message.system(narratorSystem(s)), Message.user(NARRATOR_OPENING_USER));
    }

    /**
     * 叙事者一轮的决定。candidates 是由 AI 驱动的在场人物：只有一个时是双人戏，不做调度；
     * 否则写“轮到：”。advance 为真（旁观时“继续”）则这一轮必须往前走。
     */
    public static List<Message> narratorRound(Stage s, String perception, String latest, List<String> candidates,
                                              boolean advance) {
        String instructions = candidates.size() == 1 ? ROUND_PAIR : fill(ROUND_GROUP, Map.of(
                "candidates", String.join("、", candidates),
                "possessed", s.possessed() == null ? "" : fill(POSSESSED_RULE, Map.of("name", s.possessed())),
                "advance", advance ? ADVANCE_RULE : ""));
        String user = fill(NARRATOR_ROUND_USER, Map.of("perception", perception, "latest", latest, "instructions", instructions));
        return List.of(Message.system(narratorSystem(s)), Message.user(user));
    }

    public static List<Message> narratorClosing(Stage s, String perception) {
        return List.of(Message.system(narratorSystem(s)),
                Message.user(fill(NARRATOR_CLOSING_USER, Map.of("perception", perception))));
    }

    /** 叙事者看到的“刚刚”：用户这一轮的动作。 */
    public static String latest(String possessed, String action, String text) {
        return switch (action) {
            case "说" -> possessed + "（说）：" + text;
            case "做" -> possessed + "（行）：" + text;
            case "静候" -> possessed + "没有开口。";
            case "继续" -> "没有新的动作。";
            default -> throw new IllegalArgumentException("未知动作：" + action);
        };
    }

    /** 叙事者看的档案要带姓名：盲测集的档案以姓名开头，创作出来的档案以【小传】开头，后者补上姓名。 */
    public static String profileForNarrator(String name, String profile) {
        return profile.startsWith(name) ? profile : name + "\n\n" + profile;
    }

    private static String narratorSystem(Stage s) {
        String control = s.possessed() != null
                ? "\n【" + s.possessed() + "】\n" + s.possessed() + "的言行只以给出的为准：你不替" + s.possessed()
                        + "说话、做动作或做决定，也不写" + s.possessed() + "的感受。\n"
                : "\n【镜头】\n这一场跟随" + s.follow() + "。\n";
        String system = fill(NARRATOR_SYSTEM, Map.of(
                "world", s.worldPublic(),
                "world_narrator", s.worldNarrator(),
                "profiles", String.join("\n\n", s.profiles()),
                "control", control,
                "tone", tone(s.tone()),
                "scale", scale(s.scale()),
                "opening", s.setup()));
        return s.previous() == null ? system : system + "\n\n【上一场】\n" + s.previous().text();
    }

    // ---- 回想 ----

    /**
     * 散场后，为一个在场人物写回想。before 是此前对在场其他人的看法，others 是要写“看待”的其他在场人物。
     */
    public static List<Message> recall(String name, String worldPublic, String profile, List<View> before,
                                       List<String> others, String perception) {
        String system = fill(RECALL_SYSTEM, Map.of("name", name, "world", worldPublic, "profile", profile));
        String previous = before.isEmpty() ? "" : "【你此前怎么看他们】\n"
                + before.stream().map(v -> v.name() + "：" + v.text()).collect(Collectors.joining("\n")) + "\n\n";
        String user = fill(RECALL_USER, Map.of(
                "before", previous,
                "name", name,
                "perception", perception,
                "format", others.stream().map(o -> "看待" + o + "：一两句话，你现在怎么看" + o
                        + "，和这一场以前相比有什么不同。").collect(Collectors.joining("\n"))));
        return List.of(Message.system(system), Message.user(user));
    }

    // ---- 创作 ----

    /** 一句话创作。existingWorld 为空表示新世界：世界与人物一起写；否则只在这个世界里写人物。 */
    public static List<Message> draft(String request, String existingWorld, List<String> existingNames) {
        boolean newWorld = existingWorld == null;
        String system = DRAFT_RULES + (newWorld ? DRAFT_FORMAT_NEW_WORLD : DRAFT_FORMAT_EXISTING_WORLD) + DRAFT_PROFILE_FORMAT;
        String user = (newWorld ? "" : "【已有的世界】\n" + existingWorld + "\n\n【这个世界里已有的人物】\n"
                + (existingNames.isEmpty() ? "（还没有）" : String.join("、", existingNames)) + "\n\n")
                + "【用户的一句话】\n" + request;
        return List.of(Message.system(system), Message.user(user));
    }

    // ---- 模板 ----

    private static final Pattern SLOT = Pattern.compile("\\{(\\w+)}");

    /** 把模板里的 {名字} 一次性换成值；值里出现的花括号不会被再次替换。 */
    private static String fill(String template, Map<String, String> values) {
        Matcher m = SLOT.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("模板缺少值：" + m.group(1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String scale(String scale) {
        return switch (scale) {
            case SCALE_MILD -> "亲密与暴力都可以写，情感与张力写足，最露骨处留白。";
            case SCALE_ADULT -> "这里的人都是成年人。亲密与暴力都可以写，不必留白。";
            default -> throw new IllegalArgumentException("未知内容尺度：" + scale);
        };
    }

    private static String tone(String tone) {
        return switch (tone) {
            case TONE_DAILY -> "日常相处：事件轻，重心在人物之间。";
            case TONE_ADVENTURE -> "冒险：张力要足，世界的动静多一些。";
            default -> throw new IllegalArgumentException("未知基调：" + tone);
        };
    }

    static final String EMPTY = "（还没有发生任何事）";

    private static final String CHARACTER_SYSTEM = """
            你是{name}。

            下面是你所在的世界和你的一生。你就是这个人：用自己的心去感受眼前发生的事，照自己的样子去说、去做。

            你的性子是底色，不是铠甲。你会被在乎的人打动，也会生气、心软、犯错；你可以坚持自己的立场，也可以一句话不说。你只知道自己经历过、看到过、听到过的事，不知道别人心里在想什么。

            【世界】
            {world}

            【你的一生】
            {profile}
            {private}
            【分寸】
            {scale}""";

    private static final String CHARACTER_USER = """
            {previous}【这一场到现在】
            标着“{name}”的是你自己说过、做过的事；“{name}（心声）”只有你自己知道。
            {perception}

            【轮到你】
            你可以说，可以做，也可以什么都不做。

            【篇幅】
            篇幅跟着这一刻走。你们在说话的时候，以说为主，动作一两笔带过，不铺陈景物，也不写大段心理。亲密或打斗的时候，话少，动作与细节写足。

            【怎么写】
            和上面的材料用同一种写法，每一行以你的名字和括号开头：
            {name}（行）：你的动作、神情，用第三人称写。
            {name}（说）：你说出口的话，只写话本身，不加引号。
            {name}（心声）：此刻没说出口的一两句话。
            （行）和（说）可以有多行，按先后排列，也可以都不写；（心声）必写，只写一行，放在最后。只写（心声），就是此刻你既不说也不做。
            只写你自己，不写别人的言行；这些行以外不写任何文字。""";

    private static final String NARRATOR_SYSTEM = """
            你是这个世界的叙事者。你不是故事里的人物，不和任何人物建立关系。

            你掌管世界：描写环境，代演路人，推进世界里的事件，裁定行动的结果。人物掌管自己：你不替任何人物说话、做决定，也不写他们心里的感受。人物可以无视世界里发生的事，无视的后果由你裁定。

            你只在世界需要时出声：开场、环境或时间有了变化、世界里发生了事、行动的后果需要裁定、收尾。人物之间你来我往的时候，你保持沉默。你每次出声只写一两句，点到为止，不铺陈景物，不写成小说段落。

            你要会讲故事：让世界有自己的动静——暗流、伏笔、意外、有分量的选择——而不是只回应眼前的人；按这一场的基调拿捏节奏，不为热闹打断人物之间的时刻。

            这一场里不要裁定任何人物死去或永久失去；需要时换一个不致命的结果。

            【世界】里的事人人知道。【只有你知道的事】人物未必知道：不要借叙事替人物说破，也不要让人物凭空知道。

            【世界】
            {world}

            【只有你知道的事】
            {world_narrator}

            【在场的人物】
            {profiles}
            {control}
            【基调】
            {tone}

            【分寸】
            {scale}

            【开场处境】
            {opening}""";

    private static final String NARRATOR_OPENING_USER = """
            写这一场的开场：一两句，点到为止。

            只使用【开场处境】里给的内容；没有给的共同经历、地点和对白保持未指定，不替人物补写过去。不替任何人物说话，不写人物心里的感受。

            直接写开场的文字，不加“叙事：”这类标签，也不写标题或说明。""";

    private static final String NARRATOR_ROUND_USER = """
            【这一场到现在】
            {perception}

            【刚刚】
            {latest}

            {instructions}""";

    private static final String NARRATION_RULE = "“叙事：”写世界此刻要不要你出声。大多数时候不要，就写“叙事：无”。只在这些时候出声：环境或时间有了变化，世界里发生了事，或者刚才的动作结果不确定、需要裁定。出声只写一两句，点到为止。递茶、放下东西、牵手这类结果不言自明的动作，以及人物之间说话、沉默的时候，都写“叙事：无”。";

    /** 双人戏（只有一个 AI 人物）不做调度：TA 每一轮都能接戏，叙事者只决定要不要出声。 */
    private static final String ROUND_PAIR = """
            只写下面一行，不写理由，不写别的文字：
            叙事：……

            """ + NARRATION_RULE;

    /** 群戏由叙事者决定轮到谁。 */
    private static final String ROUND_GROUP = """
            只写下面两行，不写理由，不写别的文字：
            叙事：……
            轮到：……

            """ + NARRATION_RULE + """

            “轮到：”写在场的人物里轮到谁对刚刚发生的事作出反应，按先后写名字，用“、”隔开；没有人就写“轮到：无”。已经离开这里、看不到也听不到的人物，不会轮到。你只写名字，他们自己决定说什么、做什么，也可以什么都不做。可选的人物：{candidates}。{possessed}{advance}""";

    private static final String POSSESSED_RULE = "{name}是被附身的人物，不在可选之列，不要写进“轮到：”；他什么时候开口，由他自己决定。";

    private static final String ADVANCE_RULE = "\n这一轮要让这一场往前走一段：“叙事：”和“轮到：”不能都是“无”。";

    private static final String NARRATOR_CLOSING_USER = """
            【这一场到现在】
            {perception}

            这一场到这里收尾。用一两句话收住这一场，点到为止，可以留下一个未了的悬念或一件没说完的事，让人想继续。不替任何人物说话，不写人物心里的感受。

            直接写收尾的文字，不加“叙事：”这类标签，也不写标题或说明。""";

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

    private static final String DRAFT_RULES = """
            你是世界与人物的创作者。用户用一句话描述想进入的作品世界和想遇见的人物，你把它写成可以直接使用的设定。

            【规矩】
            - 描述里写了几个人物就写几个，不自行添加人物。
            - 来自原作的世界，设定、人物与开场之前的历史取自原作；只写你确定的原作设定，拿不准的细节宁可不写，不要编造。从“此刻”起，原作的后续剧情不会自动发生，世界沿着人物自己的故事走。
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
