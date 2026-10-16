package app.lyng.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性替身：不调用任何服务，按用途与请求写出合规的输出，用于测试与 --fake 模式。
 * 人物、叙事者与整理靠 meta 知道名字、可选的人与牵挂数；一念生从消息里读出用户的话。
 * 同样的输入总得到同样的输出；输出逐段流式交出，fakeDelayMs 控制段与段之间的停顿。
 */
public class FakeModel implements ChatModel {

    private static final String[] DO = {
        "抬眼看了看对方，没有立刻接话。", "把手里的东西放下，往前走了一步。", "低头笑了一下。",
        "转过身去，望着窗外。", "替对方把茶续上。", "指尖在桌沿轻轻敲了两下。"
    };
    private static final String[] SAY = {
        "你来了。", "这件事，我想了很久。", "嗯，你说吧，我听着。", "先别急，慢慢说。", "我没事。",
        "你还记得那天吗？", "外面风大，进来坐。", "我只是没想到会是你。"
    };
    private static final String[] INNER = {
        "看样子是有心事。", "刚才那句是不是说重了。", "再等等看吧。", "原来也在意这个。", "不能让人看出来。",
        "这样就好。"
    };
    private static final String[] NARRATION = {
        "窗外起了风，檐下的灯晃了一晃。", "远处传来几声钟响，天色暗了下来。", "雨点落在青石上，渐渐密了。",
        "门外有脚步声经过，又远了。"
    };
    private static final String[] NAMES = {"沈知微", "顾长川", "林照", "苏晚", "陆行舟", "温言"};
    private static final Pattern REQUEST = Pattern.compile("【用户的一句话】\\n(.+)", Pattern.DOTALL);
    private static final Pattern KNOWN = Pattern.compile("【这个世界里已有的人物】\\n(.+?)\\n");
    private static final Pattern CURRENT = Pattern.compile("【要改的一段：[^】]*】\\n(.*?)\\n\\n【用户的要求】\\n(.+)",
            Pattern.DOTALL);

    private final long delayMs;

    public FakeModel(long delayMs) {
        this.delayMs = delayMs;
    }

    @Override
    public Completion complete(ModelRequest request, StreamListener listener) {
        long started = System.currentTimeMillis();
        String last = request.messages().getLast().content();
        int seed = Math.abs((last == null ? "" : last).hashCode() + request.messages().size());
        Map<String, Object> meta = request.meta() == null ? Map.of() : request.meta();
        if ((request.purpose() == Purpose.CHARACTER || request.purpose() == Purpose.MIND) && !request.tools().isEmpty()
                && !"tool".equals(request.messages().getLast().role()) && seed % 4 == 0) {
            String args = "{\"query\":\"上一次见面\"}";
            String tool = request.purpose() == Purpose.MIND ? "search_experiences" : "recall";
            return new Completion("", null, List.of(new ToolCall("fake-" + seed, tool, args)), "tool_calls",
                    0.0, "fake", tokens(request), 8, 0, 0, System.currentTimeMillis() - started);
        }
        String text = switch (request.purpose()) {
            case OPENING, CLOSING -> pick(NARRATION, seed);
            case NARRATOR -> narrator(meta, seed);
            case CHARACTER -> character(meta, seed);
            case MIND -> mind(meta, seed);
            case EXPRESS -> express(meta);
            case CONSENT -> "判定：可以";
            case CONSOLIDATE -> consolidate(meta, seed);
            case ASK -> ask(request);
            case WRITE -> write(request);
            case REVISE -> revise(request);
            case CHECK -> "【无】";
        };
        long first = 0;
        for (int i = 0; i < text.length(); i += 6) {
            pause();
            if (i == 0) {
                first = System.currentTimeMillis() - started;
            }
            listener.content(text.substring(i, Math.min(text.length(), i + 6)));
        }
        return new Completion(text, request.reasoning() == null || !"off".equals(request.reasoning())
                ? "（替身没有思考过程）" : null, List.of(), "stop", 0.0, "fake", tokens(request),
                text.length(), 0, first, System.currentTimeMillis() - started);
    }

    private static String narrator(Map<String, Object> meta, int seed) {
        List<?> candidates = (List<?>) meta.getOrDefault("candidates", List.of());
        boolean advance = Boolean.TRUE.equals(meta.get("advance"));
        String narration = seed % 3 == 0 || advance && candidates.isEmpty() ? pick(NARRATION, seed) : "无";
        if (candidates.size() <= 1) {
            return "叙事：" + narration;
        }
        List<String> next = new ArrayList<>();
        next.add(String.valueOf(candidates.get(seed % candidates.size())));
        if (seed % 2 == 0) {
            String second = String.valueOf(candidates.get((seed + 1) % candidates.size()));
            if (!next.contains(second)) {
                next.add(second);
            }
        }
        return "叙事：" + narration + "\n轮到：" + String.join("、", next);
    }

    private static String character(Map<String, Object> meta, int seed) {
        String name = String.valueOf(meta.getOrDefault("name", "人物"));
        boolean actions = Boolean.TRUE.equals(meta.get("allowActions"));
        StringBuilder out = new StringBuilder();
        if (seed % 7 != 3) {
            if (actions && seed % 2 == 0) {
                out.append(name).append("（行）：").append(pick(DO, seed)).append('\n');
            }
            out.append(name).append("（说）：").append(pick(SAY, seed / 3)).append('\n');
            if (!actions && seed % 3 == 0) {
                out.append(name).append("（说）：").append(pick(SAY, seed / 3 + 1)).append('\n');
            }
        }
        return out.append(name).append("（心声）：").append(pick(INNER, seed / 7)).toString();
    }

    /** 心里过一遍的私密交接：有时不说，有时改看法、添牵挂、记下一件事（引用最近的一条材料）。 */
    private static String mind(Map<String, Object> meta, int seed) {
        String person = String.valueOf(meta.getOrDefault("personId", ""));
        List<?> traces = (List<?>) meta.getOrDefault("traces", List.of());
        List<?> concerns = (List<?>) meta.getOrDefault("concerns", List.of());
        String inner = quote(pick(INNER, seed / 7));
        if (seed % 7 == 3) {
            return "{\"silenceReason\":\"现在不想回。\",\"innerVoice\":" + inner + "}";
        }
        List<String> changes = new ArrayList<>();
        if (seed % 3 == 0 && !person.isEmpty()) {
            changes.add("\"views\":[{\"personId\":" + quote(person) + ",\"understanding\":\"比初见时好懂一些，我愿意再多信几分。\"}]");
        }
        if (seed % 5 == 1 && concerns.size() < 8 && !person.isEmpty()) {
            changes.add("\"concerns\":[{\"personId\":" + quote(person)
                    + ",\"cue\":\"没说完的话\",\"content\":\"下次要把没说完的话说完。\",\"active\":true,\"resolved\":false}]");
        }
        if (seed % 5 == 0 && !traces.isEmpty()) {
            changes.add("\"memories\":[{\"cue\":\"那一晚的消息\",\"content\":\"那一晚我们说了一会儿话，我比自己以为的更在意。\","
                    + "\"permanent\":false,\"sources\":[" + traces.getLast() + "]}]");
        }
        String intent = pick(SAY, seed / 3) + (seed % 3 == 0 ? pick(SAY, seed / 3 + 1) : "");
        return "{\"publicIntent\":" + quote(intent) + ",\"innerVoice\":" + inner
                + (changes.isEmpty() ? "" : ",\"changes\":{" + String.join(",", changes) + "}") + "}";
    }

    /** 说出口：选定的内容一句一行。 */
    private static String express(Map<String, Object> meta) {
        String intent = String.valueOf(meta.getOrDefault("intent", "嗯。"));
        return String.join("\n", intent.split("(?<=[。？！])"));
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String consolidate(Map<String, Object> meta, int seed) {
        List<?> people = (List<?>) meta.getOrDefault("people", List.of());
        int concerns = ((Number) meta.getOrDefault("concerns", 0)).intValue();
        if (seed % 5 == 4) {
            return "【无】";
        }
        String who = people.isEmpty() ? null : String.valueOf(people.getFirst());
        StringBuilder out = new StringBuilder();
        out.append("【记忆】").append(who == null ? "我一个人想了很久。" : "我和" + who + "说了一会儿话，" + pick(SAY, seed))
                .append("\n【意义】").append(who == null ? "有些事只能自己想明白。" : "我比自己以为的更在意" + who + "。");
        if (who != null) {
            out.append("\n【看法·").append(who).append("】").append(who).append("比初见时更好懂一些，我愿意再多信几分。");
            if (seed % 3 == 0) {
                out.append("\n【牵挂·").append(who).append("】下次见面，要把没说完的话说完。");
            }
        }
        if (concerns > 4) {
            out.append("\n【放下·").append(concerns).append("】");
        }
        return out.toString();
    }

    private static String ask(ModelRequest request) {
        if (request.messages().getLast().content().contains("【已有的世界】")) {
            return "【无】";
        }
        return "【问题】人物从什么时候开始？\n【选项】故事开始之前｜故事中段｜故事结束之后\n【推荐】故事开始之前";
    }

    private static String write(ModelRequest request) {
        String user = request.messages().getLast().content();
        Matcher m = REQUEST.matcher(user);
        String said = m.find() ? m.group(1).strip() : "一个安静的地方";
        boolean newWorld = request.messages().getFirst().content().contains("这是一个新世界");
        List<String> known = new ArrayList<>();
        Matcher k = KNOWN.matcher(user);
        if (k.find() && !k.group(1).equals("（还没有）")) {
            known.addAll(List.of(k.group(1).split("、")));
        }
        List<String> names = new ArrayList<>();
        int seed = Math.abs(said.hashCode());
        for (int i = 0; names.size() < 2 && i < NAMES.length; i++) {
            String name = NAMES[(seed + i) % NAMES.length];
            if (!known.contains(name)) {
                names.add(name);
            }
        }
        StringBuilder out = new StringBuilder();
        if (newWorld) {
            String title = said.length() > 6 ? said.substring(0, 6) : said;
            out.append("【世界名】").append(title.replaceAll("[\\s，。、]", "")).append("\n【世界·公开】\n")
                    .append("一座临水的小城，城里人靠渡口与茶馆过日子。城东有一座旧书院，城西是集市。\n此刻是初秋，渡口刚换了新的船家。\n")
                    .append("【世界·只有叙事者知道】\n书院的旧主人并没有离开，只是换了名字住在城里。\n");
        }
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String other = names.get(1 - i);
            out.append("【人物】").append(name).append('\n')
                    .append("【小传】在城里长大，十六岁那年替家里撑起了茶馆。见过许多来来去去的人。\n")
                    .append("【外貌】个子不高，常穿一身洗得发白的青衫。\n")
                    .append("【外面与里面】看着随和，心里有一道不让人进的门。\n")
                    .append("【在乎一个人时】嘴上不说，会默默记住对方的喜好。\n")
                    .append("【怎么生气】不吵，只是不再说话。\n")
                    .append("【怎么和好】递一杯茶，算是先低头。\n")
                    .append("【说话的样子】话不多，常用“嗯”和“随你”。\n")
                    .append("【知道与不知道】知道城里的大小事，不知道书院的秘密。\n")
                    .append("【此刻】二十四岁，刚送走一位远行的朋友。\n")
                    .append("【看法·").append(other).append("】认识很多年了，信得过，只是猜不透。\n")
                    .append("【牵挂·").append(other).append("】上次分开时没把话说完。\n");
        }
        return out.toString().strip();
    }

    private static String revise(ModelRequest request) {
        Matcher m = CURRENT.matcher(request.messages().getLast().content());
        if (!m.find()) {
            return "（替身没有找到要改的一段）";
        }
        return m.group(1).strip() + "\n（按要求改过：" + m.group(2).strip() + "）";
    }

    private static int tokens(ModelRequest request) {
        return request.messages().stream().mapToInt(msg -> msg.content() == null ? 0 : msg.content().length()).sum();
    }

    private static String pick(String[] pool, int seed) {
        return pool[Math.abs(seed) % pool.length];
    }

    private void pause() {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
