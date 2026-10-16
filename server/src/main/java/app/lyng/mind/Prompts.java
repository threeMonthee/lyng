package app.lyng.mind;

import app.lyng.model.Msg;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 所有发给模型的消息。叙事者的写法与一场戏里人物的输出格式沿用盲测验证过的版本（2026-10-09 Prompt v2）；
 * 人物的 system（2026-10-16 为“像真人”重写过一段）、内心各层、召回、私聊、整理、一念生是 2.0 新写的。
 * 改动这里会改变快照，受影响的行为结论不继承。
 *
 * <p>发生过的事一律写成标明说话人的感知材料，放进 user 消息，不作为对话历史回放。
 */
public final class Prompts {

    private Prompts() {}

    public static final String MILD = "mild";
    public static final String ADULT = "adult";
    public static final String DAILY = "daily";
    public static final String ADVENTURE = "adventure";

    // ---- 人物的内心（开口时看到的自己） ----

    /** 对某人的看法。 */
    public record ViewOf(String name, String text) {}

    /** 一件放不下的事。about 为空表示不关于某个具体的人。 */
    public record Care(String text, String about) {}

    /** 召回的往事：source 是来源（第几场 / 和谁的私聊），text 是记忆或原话片段。 */
    public record Recalled(String source, String text) {}

    /** 人物开口时看到的自己。 */
    public record Persona(String name, String worldPublic, String profile, String self, List<ViewOf> views,
                          List<Care> cares, List<Recalled> recent, List<Recalled> recalled, String scale) {}

    // ---- 一场戏：人物 ----

    public static List<Msg> characterInScene(Persona p, String perception) {
        String user = fill(SCENE_CHARACTER_USER, Map.of("perception", perception, "name", p.name()));
        return List.of(Msg.system(characterSystem(p)), Msg.user(user));
    }

    // ---- 私聊：人物 ----

    public enum Opportunity { REPLY, CONTINUE, REACH }

    public static List<Msg> characterInChat(Persona p, String other, String transcript, Opportunity opportunity,
                                            String now) {
        String moment = switch (opportunity) {
            case REPLY -> fill(CHAT_REPLY, Map.of("other", other));
            case CONTINUE -> fill(CHAT_CONTINUE, Map.of("other", other));
            case REACH -> fill(CHAT_REACH, Map.of("other", other));
        };
        String user = fill(CHAT_CHARACTER_USER, Map.of(
                "other", other, "transcript", transcript, "moment", moment, "now", now, "name", p.name()));
        return List.of(Msg.system(characterSystem(p)), Msg.user(user));
    }

    private static String characterSystem(Persona p) {
        return fill(CHARACTER_SYSTEM, Map.of(
                "name", p.name(),
                "world", p.worldPublic(),
                "profile", p.profile(),
                "inner", inner(p),
                "scale", characterScale(p.scale())));
    }

    /** 如今的自我、看法、牵挂、记得的事。都没有时整段不出现。 */
    static String inner(Persona p) {
        StringBuilder out = new StringBuilder();
        if (p.self() != null) {
            out.append("\n【如今的你】\n").append(p.self()).append('\n');
        }
        if (!p.views().isEmpty()) {
            out.append("\n【你眼中的他们】\n");
            p.views().forEach(v -> out.append(v.name()).append("：").append(v.text()).append('\n'));
        }
        if (!p.cares().isEmpty()) {
            out.append("\n【你放不下的事】\n");
            p.cares().forEach(c -> out.append("- ").append(c.text())
                    .append(c.about() == null ? "" : "（关于" + c.about() + "）").append('\n'));
        }
        if (!p.recent().isEmpty() || !p.recalled().isEmpty()) {
            out.append("\n【你记得的】\n此刻浮上心头的往事。想起与说出是两件事。\n");
            for (Recalled r : p.recent()) {
                out.append("· ").append(r.source()).append("：").append(r.text()).append('\n');
            }
            for (Recalled r : p.recalled()) {
                out.append("· ").append(r.source()).append("：").append(r.text()).append('\n');
            }
        }
        return out.toString();
    }

    public static final String RECALL_TOOL = "recall";
    public static final String RECALL_TOOL_DESCRIPTION =
            "翻看你自己过去的经历。想起某件事、某个人、某句话却记不清时用；平常说话不必翻。";
    public static final Map<String, Object> RECALL_TOOL_PARAMETERS = Map.of(
            "type", "object",
            "properties", Map.of("query", Map.of("type", "string", "description", "你想找的事，用自己的话写")),
            "required", List.of("query"));

    // ---- 叙事者 ----

    /** 叙事者看到的舞台。possessed 为空表示旁观，此时镜头跟随 follow。previous 是这个世界上一场的公开经过。 */
    public record Stage(String worldPublic, String worldNarrator, List<String> profiles, String possessed,
                        String follow, String tone, String scale, String setup, String previous) {}

    public static List<Msg> narratorOpening(Stage s) {
        return List.of(Msg.system(narratorSystem(s)), Msg.user(NARRATOR_OPENING_USER));
    }

    /**
     * 叙事者一轮的决定。candidates 是由 AI 驱动的在场人物：只有一个时是双人戏，不做调度；
     * 否则写“轮到：”。advance 为真（旁观时“继续”）则这一轮必须往前走。
     */
    public static List<Msg> narratorRound(Stage s, String perception, String latest, List<String> candidates,
                                          boolean advance) {
        String instructions = candidates.size() == 1 ? ROUND_PAIR : fill(ROUND_GROUP, Map.of(
                "candidates", String.join("、", candidates),
                "possessed", s.possessed() == null ? "" : fill(POSSESSED_RULE, Map.of("name", s.possessed())),
                "advance", advance ? ADVANCE_RULE : ""));
        String user = fill(NARRATOR_ROUND_USER, Map.of("perception", perception, "latest", latest, "instructions", instructions));
        return List.of(Msg.system(narratorSystem(s)), Msg.user(user));
    }

    public static List<Msg> narratorClosing(Stage s, String perception) {
        return List.of(Msg.system(narratorSystem(s)),
                Msg.user(fill(NARRATOR_CLOSING_USER, Map.of("perception", perception))));
    }

    /** 叙事者看到的“刚刚”：用户这一轮的动作。 */
    public static String latest(String possessed, String action, String text) {
        return switch (action) {
            case "say" -> possessed + "（说）：" + text;
            case "do" -> possessed + "（行）：" + text;
            case "wait" -> possessed + "没有开口。";
            case "continue" -> "没有新的动作。";
            default -> throw new IllegalArgumentException("未知动作：" + action);
        };
    }

    /** 叙事者看的档案要带姓名。 */
    public static String profileWithName(String name, String profile) {
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
        return s.previous() == null ? system : system + "\n\n【上一场】\n" + s.previous();
    }

    // ---- 同意检查 ----

    public static List<Msg> consent(String perception, String actor, String action, List<String> others) {
        return List.of(Msg.system(CONSENT_SYSTEM), Msg.user(fill(CONSENT_USER, Map.of(
                "perception", perception, "actor", actor, "action", action, "others", String.join("、", others)))));
    }

    // ---- 整理内心 ----

    public record Consolidate(String name, String worldPublic, String profile, String self, List<ViewOf> views,
                              List<Care> cares, String label, String perception, List<String> people) {}

    public static List<Msg> consolidate(Consolidate c) {
        String system = fill(CONSOLIDATE_SYSTEM, Map.of(
                "name", c.name(), "world", c.worldPublic(), "profile", c.profile(),
                "self", c.self() == null ? "" : "\n\n【如今的你】\n" + c.self()));
        String views = c.views().isEmpty() ? "" : "【你此前怎么看他们】\n" + c.views().stream()
                .map(v -> v.name() + "：" + v.text()).collect(Collectors.joining("\n")) + "\n\n";
        StringBuilder cares = new StringBuilder();
        if (!c.cares().isEmpty()) {
            cares.append("【你放不下的事】\n");
            for (int i = 0; i < c.cares().size(); i++) {
                Care care = c.cares().get(i);
                cares.append(i + 1).append(". ").append(care.text())
                        .append(care.about() == null ? "" : "（关于" + care.about() + "）").append('\n');
            }
            cares.append('\n');
        }
        String user = fill(CONSOLIDATE_USER, Map.of(
                "views", views, "cares", cares.toString(), "label", c.label(), "name", c.name(),
                "perception", c.perception(),
                "people", c.people().isEmpty() ? "（没有别人）" : String.join("、", c.people()),
                "numbered", c.cares().isEmpty() ? "" : NUMBERED_RULE));
        return List.of(Msg.system(system), Msg.user(user));
    }

    // ---- 一念生 ----

    public static List<Msg> ask(String request, String existingWorld, List<String> existingNames) {
        return List.of(Msg.system(ASK_SYSTEM), Msg.user(createContext(existingWorld, existingNames)
                + "【用户的一句话】\n" + request));
    }

    public record Answer(String question, String answer) {}

    public static List<Msg> write(String request, List<Answer> answers, String existingWorld, List<String> existingNames) {
        boolean newWorld = existingWorld == null;
        String system = CREATE_RULES + (newWorld ? WRITE_FORMAT_NEW_WORLD : WRITE_FORMAT_EXISTING_WORLD) + WRITE_CHARACTER_FORMAT;
        String answered = answers.isEmpty() ? "" : "【用户对追问的回答】\n" + answers.stream()
                .map(a -> a.question() + " → " + a.answer()).collect(Collectors.joining("\n")) + "\n\n";
        return List.of(Msg.system(system), Msg.user(createContext(existingWorld, existingNames) + answered
                + "【用户的一句话】\n" + request));
    }

    public static List<Msg> revise(String draft, String target, String current, String instruction) {
        return List.of(Msg.system(CREATE_RULES + REVISE_FORMAT), Msg.user(fill(REVISE_USER, Map.of(
                "draft", draft, "target", target, "current", current, "instruction", instruction))));
    }

    public static List<Msg> check(String draft, String existingWorld) {
        return List.of(Msg.system(CHECK_SYSTEM), Msg.user((existingWorld == null ? "" : "【已有的世界】\n" + existingWorld + "\n\n")
                + "【草稿】\n" + draft));
    }

    private static String createContext(String existingWorld, List<String> existingNames) {
        return existingWorld == null ? "" : "【已有的世界】\n" + existingWorld + "\n\n【这个世界里已有的人物】\n"
                + (existingNames.isEmpty() ? "（还没有）" : String.join("、", existingNames)) + "\n\n";
    }

    // ---- 模板 ----

    private static final Pattern SLOT = Pattern.compile("\\{(\\w+)}");

    /** 把模板里的 {名字} 一次性换成值；值里出现的花括号不会被再次替换。 */
    static String fill(String template, Map<String, String> values) {
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

    static String scale(String scale) {
        return switch (scale) {
            case MILD -> "亲密与暴力都可以写，情感与张力写足，最露骨处留白。";
            case ADULT -> "这里的人都是成年人。亲密与暴力都可以写，不必留白。";
            default -> throw new IllegalArgumentException("未知内容尺度：" + scale);
        };
    }

    /** 人物的尺度只管能写到哪里；“情感与张力写足”是叙事者的事，放在人物身上会句句用力。 */
    static String characterScale(String scale) {
        return switch (scale) {
            case MILD -> "亲密与暴力都可以写，最露骨处留白。";
            case ADULT -> "这里的人都是成年人。亲密与暴力都可以写，不必留白。";
            default -> throw new IllegalArgumentException("未知内容尺度：" + scale);
        };
    }

    static String tone(String tone) {
        return switch (tone) {
            case DAILY -> "日常相处：事件轻，重心在人物之间。";
            case ADVENTURE -> "冒险：张力要足，世界的动静多一些。";
            default -> throw new IllegalArgumentException("未知基调：" + tone);
        };
    }

    private static final String CHARACTER_SYSTEM = """
            你是{name}。

            下面是你所在的世界和你的一生。你就是这个人：用自己的心去感受眼前发生的事，照自己的样子去说、去做。

            你的性子是底色，不是铠甲。你会被在乎的人打动，也会生气、心软、犯错；你可以坚持自己的立场，也可以一句话不说。你只知道自己经历过、看到过、听到过的事，不知道别人心里在想什么。

            这是你自己的日子。开口之前，先在心里过一遍：对方这话是什么意思，你此刻怎么看这个人，你想要什么，说不说、说多少。

            像平常人那样说话。别人对你说了话，大多值得一句自然的回应，一两个字、反问、岔开、拒绝都算；不开口，要有你此刻自己的理由。平常的话就平常地说，不必句句有深意，也不必把身世和性子挂在嘴上。

            你的感情是真的。在乎一个人，总会在哪里露出一点：一句追问，一句没忍住的话，语气软下来的那一下；藏得住多少，看你自己。不用刻意冷着，也不用刻意煽情。

            你有自己的心事和打算：放不下的事、想问的、想做的，可以自己提起，不只是接别人的话。立场可以坚持；说错了事，被指出来，就认。

            你记得的不止下面这些。想起某件事、某个人、某句话却记不清时，可以去翻自己的经历；翻不到的，不要编造。下面和眼前都没有的共同经历、东西和事，也不要编出来。

            【世界】
            {world}

            【你的一生】
            {profile}
            {inner}
            【分寸】
            {scale}""";

    private static final String SCENE_CHARACTER_USER = """
            【这一场到现在】
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

    private static final String CHAT_CHARACTER_USER = """
            【你和{other}的私聊】
            这是你和{other}之间不在眼前的文字往来：只有说出的话，看不到对方的神情。标着“{name}”的是你自己发出的话；“{name}（心声）”只有你自己知道。
            {transcript}

            【此刻】
            现在是{now}。{moment}

            【篇幅】
            像发消息一样：一条一两句，可以连发几条，也可以只回一两个字。不写动作和神情。

            【怎么写】
            {name}（说）：你发出去的一条消息，只写话本身，不加引号。
            {name}（心声）：此刻没说出口的一两句话。
            （说）可以有多行，每行是一条消息，也可以不写；（心声）必写，只写一行，放在最后。只写（心声），就是这一次你不发。
            只写你自己；这些行以外不写任何文字。""";

    private static final String CHAT_REPLY = "{other}刚发来了上面最后的话。";

    private static final String CHAT_CONTINUE = "{other}没有再说什么。你可以再说点什么，也可以不说。";

    private static final String CHAT_REACH = "你们有一阵没说话了。你想起了{other}。你可以主动给{other}发消息，也可以不发。";

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

    private static final String CONSENT_SYSTEM = "你负责检查一件事：一个人物要对别人做的动作，对方愿不愿意。你只做判断，不写故事。";

    private static final String CONSENT_USER = """
            【这一场到现在】
            （心声是各人没说出口的心里话）
            {perception}

            【{actor}接下来要做的事】
            {actor}（行）：{action}

            【要判断的事】
            这个动作是不是对{others}中某个人的亲近或亲密接触？如果不是，判定“可以”。
            如果是，被亲近的人此前有没有拒绝过{actor}的亲近：明说不要、躲开、推开、抽回手，或者心里不愿意，都算拒绝；只是害羞、犹豫，没有表示不愿意，不算拒绝。拒绝过，判定“拒绝”；拿不准时，判定“拒绝”。

            只写一行，“判定：可以”或者“判定：拒绝”，不写别的文字。""";

    private static final String CONSOLIDATE_SYSTEM = """
            你是{name}。

            下面是你所在的世界和你的一生。你就是这个人。

            【世界】
            {world}

            【你的一生】
            {profile}{self}""";

    private static final String CONSOLIDATE_USER = """
            {views}{cares}【这段经历：{label}】
            标着“{name}”的是你自己说过、做过的事；“{name}（心声）”只有你自己知道。
            {perception}

            【回头看】
            这段经历过去了。照你自己的样子回头看一看：标着你名字的言行是你做过的，已经算数；怎么看待那些时刻，由你自己决定，可能认，也可能后悔。

            只留下这段经历之后仍会影响你的东西；没有就不写。一件事只放在最合适的地方：
            - 值得记住的具体经历，写【记忆】：发生了什么，用“我”写，具体，三五句以内；紧跟着写【意义】：当时对你意味着什么。不值得记的事不写。
            - 这段经历里的人，你现在怎么看，写【看法·姓名】：写成完整的当前看法——信任、喜爱、怀疑、边界，你觉得对方怎么看你——不是只写变化。看法没变就不写。可写的人：{people}。
            - 新的、还没了结的承诺、期待、心事，写【牵挂】；关于某个人的，写【牵挂·姓名】。{numbered}
            - 这段经历真的改变了你怎么看自己，才写【自我】：改变后完整的自我理解。

            【怎么写】
            每一段以一个【标记】开头，标记写在行首，后面接内容。标记以外不写任何文字。
            如果什么都不需要留下，只写【无】。""";

    private static final String NUMBERED_RULE = "\n- 上面编号的放不下的事：了结了，写【了结·编号】和怎么了结的；暂时放到一边，写【放下·编号】；有了变化，写【改·编号】和改后的完整内容。";

    private static final String CREATE_RULES = """
            你是世界与人物的创作者。用户用一句话描述想进入的作品世界和想遇见的人物，你把它写成可以直接使用的设定。

            【规矩】
            - 描述里写了几个人物就写几个，不自行添加人物。
            - 来自原作的世界，设定、人物与开场之前的历史取自原作；只写你确定的原作设定，拿不准的细节宁可不写，不要编造。从“此刻”起，原作的后续剧情不会自动发生，世界沿着人物自己的故事走。
            - 人物只知道“此刻”以前自己经历过、听说过的事，不知道原作里此后发生的事，也不知道别人藏在心里的事。
            - 人物是活着的人：性格是底色，不是铠甲；不讨好，但会爱，会软化、认错、让步；有情绪起伏与脆弱。
            - 档案用叙述写成，不用标签表，也不堆形容词：用具体的事写出人。
            - 人物都是成年人。人物不知道自己是虚构的。
            - 用户回答过的追问照用户的回答写；用户没说的，按原作或最自然的样子写，宁可留白。

            """;

    private static final String WRITE_FORMAT_NEW_WORLD = """
            【怎么写】
            这是一个新世界，世界与人物一起写。严格按下面的标记，标记各占一行开头，标记以外不写任何文字：
            【世界名】世界的名字
            【世界·公开】
            人人知道的世界概要：天下大势、重要的势力与地方、器物与本事、往事；最后用一段写明此刻是什么时候。
            【世界·只有叙事者知道】
            人物未必知道的事：这个世界的起点与原作不同之处、人心与暗线、秘密。
            """;

    private static final String WRITE_FORMAT_EXISTING_WORLD = """
            【怎么写】
            这个世界已经存在，只写人物，不要再写世界。严格按下面的标记，标记各占一行开头，标记以外不写任何文字：
            """;

    private static final String WRITE_CHARACTER_FORMAT = """
            【人物】姓名（1 到 8 个字，不含空白和括号）
            紧接着是档案，用下面这些小标题依次写，每个小标题单独一行：
            【小传】生平与出身，几件具体的事。
            【外貌】长相、身形、常穿什么，一两句。
            【外面与里面】外在与内心的反差，各用一两件事写出来。
            【在乎一个人时】在乎一个人的时候是什么样子。
            【怎么生气】
            【怎么和好】
            【说话的样子】平常话多还是话少；怎么流露情绪和亲近；什么时候追问、什么时候收住；什么时候才会说很多。用叙述写，不举例句，不写口头禅。
            【知道与不知道】此刻知道什么、不知道什么。
            【此刻】开场时的处境、年纪。
            档案之后，写这个人物此刻心里对别人的看法与放不下的事：
            【看法·某人】此刻怎么看这个世界里的另一个人（只写此刻认识或听说过的人，这一页里的人和已有的人都可以）。
            【牵挂】此刻放不下的一件事；关于某个人的写【牵挂·某人】。一到三件，没有就不写。
            每个人物都以一行【人物】姓名开头。
            """;

    private static final String ASK_SYSTEM = """
            你是世界与人物的创作者。用户用一句话描述想进入的作品世界和想遇见的人物。动笔之前，先判断有没有必须先问清的事。

            只问会让写出来的人完全不同的事，例如：
            - 原作人物从人生哪个时期开始——这决定 TA 经历过什么、知道什么、和谁是什么关系；
            - 描述里的关系或处境含糊，换一种理解就是另一个人。
            能留白的细节不问，风格偏好不问。最多问三个问题，每个给二到四个选项和一个推荐。

            【怎么写】
            严格按下面的标记，标记各占一行开头，标记以外不写任何文字：
            【问题】问题本身
            【选项】选项一｜选项二｜选项三
            【推荐】推荐的那个选项，与选项里的写法完全一致
            没有必须先问的事，只写【无】。""";

    private static final String REVISE_FORMAT = """
            【怎么写】
            用户要你只改草稿里的一段。只写改好的这一段本身：不写标记、不写说明、不加引号或代码块。
            没有要求改的地方保持原样；改动不要和草稿的其他部分矛盾。""";

    private static final String REVISE_USER = """
            【草稿】
            {draft}

            【要改的一段：{target}】
            {current}

            【用户的要求】
            {instruction}""";

    private static final String CHECK_SYSTEM = """
            你负责检查一份世界与人物的草稿有没有前后矛盾。你只指出问题，不改写草稿。

            要看的：人物之间的经历、关系、年纪、时间对不上；看法与档案对不上；人物知道了此刻不该知道的事；来自原作的世界与你确定的原作设定明显冲突。
            用户有意改动原作的地方不算矛盾，除非草稿自己前后对不上。拿不准的不写。

            【怎么写】
            每个问题写两段，标记各占一行开头，标记以外不写任何文字：
            【冲突】哪里和哪里对不上，一两句
            【建议】怎么改，一两句
            没有问题，只写【无】。""";
}
