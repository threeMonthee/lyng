package app.lyng.mind;

import app.lyng.model.FormatError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析模型输出。不合规一律抛 FormatError，由网关记为技术失败并重试。
 *
 * <p>人物写“陆雪琪（行）：”“陆雪琪（说）：”“陆雪琪（心声）：”；叙事者每轮写“叙事：”（群戏再加“轮到：”），
 * 开场与收尾直接写正文。这与喂给模型的感知材料是同一套写法（盲测验证过的写法，规则沿用验证原型）。
 * 整理内心、创作用【标记】分段。
 */
public final class Markup {

    /** 一行的开头：叙事： / 轮到： / 名字（行|说|心声）： */
    private static final Pattern LABEL =
            Pattern.compile("^(?:(叙事|轮到)|([^（）：\\s]{1,8})（(行|说|心声)）)：(.*)$", Pattern.DOTALL);
    private static final Pattern NAME_SPLIT = Pattern.compile("[、，,\\s]+");
    /** 行首的【标记】，标记后可以直接跟内容。 */
    private static final Pattern MARK = Pattern.compile("^【([^】]{1,20})】(.*)$");
    public static final Pattern CHARACTER_NAME = Pattern.compile("[^（）：·【】\\s]{1,8}");

    private Markup() {}

    public record Beat(String tag, String text) {}

    /** 一个 AI 人物的一次反应。beats 按先后排列（行 / 说）；只有心声时 silent 为真。 */
    public record Turn(List<Beat> beats, String inner, boolean silent) {}

    /** 一行带标签的输出：name 为空表示“叙事”“轮到”这类叙事者的标签。 */
    private record Labeled(String name, String tag, String body) {}

    private static Labeled label(String line) {
        Matcher m = LABEL.matcher(line.strip());
        if (!m.matches()) {
            return null;
        }
        return m.group(1) != null ? new Labeled(null, m.group(1), m.group(4))
                : new Labeled(m.group(2), m.group(3), m.group(4));
    }

    /** 拆成带标签的行。内容可以跨行，直到下一个带标签的行。 */
    private static List<Labeled> split(String text) {
        if (text == null || text.isBlank()) {
            throw new FormatError("空响应");
        }
        List<String[]> out = new ArrayList<>();
        for (String line : text.strip().split("\\R", -1)) {
            Labeled l = label(line);
            if (l != null) {
                out.add(new String[] {l.name(), l.tag(), l.body()});
            } else if (out.isEmpty()) {
                if (!line.isBlank()) {
                    throw new FormatError("标签之外有文字");
                }
            } else {
                out.getLast()[2] += "\n" + line;
            }
        }
        return out.stream().map(a -> new Labeled(a[0], a[1], a[2].strip())).toList();
    }

    /**
     * （行）（说）可有可无，（心声）必写且在最后；只有（心声）就是此刻不说也不做。
     * 私聊里 allowActions 为假：只能（说），没有（行）。
     */
    public static Turn parseTurn(String text, String name, boolean allowActions) {
        List<Labeled> lines = split(text);
        if (lines.stream().anyMatch(l -> l.name() == null)) {
            throw new FormatError("人物只写自己的（行）（说）（心声）");
        }
        List<String> others = lines.stream().map(Labeled::name).filter(n -> !n.equals(name)).distinct().sorted().toList();
        if (!others.isEmpty()) {
            throw new FormatError("写了别人的言行：" + String.join("、", others));
        }
        if (lines.stream().filter(l -> l.tag().equals("心声")).count() != 1 || !lines.getLast().tag().equals("心声")) {
            throw new FormatError("（心声）必须恰好一行且放在最后");
        }
        String inner = lines.getLast().body();
        if (inner.isEmpty()) {
            throw new FormatError("（心声）为空");
        }
        List<Beat> beats = new ArrayList<>();
        for (Labeled l : lines.subList(0, lines.size() - 1)) {
            if (l.body().isEmpty()) {
                throw new FormatError("（行）或（说）为空");
            }
            if (!allowActions && l.tag().equals("行")) {
                throw new FormatError("私聊里只有（说），没有（行）");
            }
            beats.add(new Beat(l.tag(), l.body()));
        }
        return new Turn(List.copyOf(beats), inner, beats.isEmpty());
    }

    /** 开场与收尾：直接是叙事文字，不带标签。 */
    public static String parseNarration(String text) {
        if (text == null || text.isBlank()) {
            throw new FormatError("空响应");
        }
        if (text.lines().anyMatch(line -> label(line) != null)) {
            throw new FormatError("开场与收尾直接写叙事文字，不加标签");
        }
        return text.strip();
    }

    /** 叙事者一轮的决定：叙事（不出声为 null）与轮到的人物。 */
    public record Round(String narration, List<String> reactors) {}

    /** 双人戏的一轮：恰好一行“叙事：”，不出声写“无”。不做调度，由调用方让那个人物接戏。 */
    public static Round parsePairRound(String text) {
        List<Labeled> lines = split(text);
        if (lines.size() != 1 || lines.getFirst().name() != null || !lines.getFirst().tag().equals("叙事")) {
            throw new FormatError("只写一行“叙事：”");
        }
        String body = lines.getFirst().body();
        if (body.isEmpty()) {
            throw new FormatError("“叙事：”不能为空；不出声就写“无”");
        }
        return new Round(isNone(body) ? null : body, List.of());
    }

    /**
     * 群戏的一轮：恰好一行“叙事：”、一行“轮到：”，没有就写“无”。
     * 附身的人物不在可选之列，写了就拒绝；advance 为真（旁观时“继续”）则不能两项都是“无”。
     */
    public static Round parseGroupRound(String text, List<String> candidates, boolean advance, String possessed) {
        List<Labeled> lines = split(text);
        if (lines.stream().anyMatch(l -> l.name() != null)) {
            throw new FormatError("叙事者只写“叙事：”“轮到：”两行，不写人物的言行");
        }
        Map<String, String> body = new LinkedHashMap<>();
        for (Labeled l : lines) {
            if (body.put(l.tag(), l.body()) != null) {
                throw new FormatError("需要恰好一行“叙事：”和一行“轮到：”");
            }
        }
        if (body.size() != 2) {
            throw new FormatError("需要恰好一行“叙事：”和一行“轮到：”");
        }
        if (body.get("叙事").isEmpty() || body.get("轮到").isEmpty()) {
            throw new FormatError("“叙事：”“轮到：”不能为空；没有就写“无”");
        }
        String narration = isNone(body.get("叙事")) ? null : body.get("叙事");
        String raw = body.get("轮到");
        List<String> names = isNone(raw) ? List.of()
                : NAME_SPLIT.splitAsStream(raw.replaceAll("[。.]+$", "")).filter(n -> !n.isEmpty()).toList();
        if (possessed != null && names.contains(possessed)) {
            throw new FormatError(possessed + "是被附身的人物，不在“轮到：”的可选之列");
        }
        if (!candidates.containsAll(names) || names.stream().distinct().count() != names.size()) {
            throw new FormatError("“轮到：”只能是 " + String.join("、", candidates) + " 或“无”，且不重复");
        }
        if (advance && narration == null && names.isEmpty()) {
            throw new FormatError("这一轮要往前走，“叙事：”和“轮到：”不能都是“无”");
        }
        return new Round(narration, names);
    }

    /** “无”，模型常写成“无。”“无！”：算不出声。 */
    static boolean isNone(String body) {
        return body.replaceAll("[。.！!\\s]+$", "").equals("无");
    }

    /** 同意检查：一行“判定：可以”或“判定：拒绝”。 */
    public static boolean parseConsentRefused(String text) {
        String s = text == null ? "" : text.strip();
        if (!s.startsWith("判定：") || s.lines().count() != 1) {
            throw new FormatError("只写一行“判定：可以”或“判定：拒绝”");
        }
        String verdict = s.substring(3).replaceAll("[。.！!\\s]+$", "");
        return switch (verdict) {
            case "拒绝" -> true;
            case "可以" -> false;
            default -> throw new FormatError("判定只能是“可以”或“拒绝”");
        };
    }

    // ---- 【标记】分段 ----

    /** 一段【标记】与它下面的内容（标记行里标记后面的文字也算内容）。 */
    public record Section(String mark, String body) {}

    /** 按行首的【标记】分段；isTop 决定哪些标记开新段，其余的【…】行（如档案里的小标题）算正文。 */
    static List<Section> sections(String text, java.util.function.Predicate<String> isTop) {
        if (text == null || text.isBlank()) {
            throw new FormatError("空响应");
        }
        List<String[]> out = new ArrayList<>();
        for (String line : text.strip().split("\\R", -1)) {
            Matcher m = MARK.matcher(line.strip());
            if (m.matches() && isTop.test(m.group(1))) {
                out.add(new String[] {m.group(1), m.group(2)});
            } else if (out.isEmpty()) {
                if (!line.isBlank()) {
                    throw new FormatError("标记之外有文字");
                }
            } else {
                out.getLast()[1] += "\n" + line;
            }
        }
        return out.stream().map(a -> new Section(a[0], a[1].strip())).toList();
    }

    // ---- 整理内心 ----

    public record NewMemory(String event, String meaning) {}

    public record NewConcern(String about, String text) {}

    /**
     * 一次整理的结果。views 的键是姓名；resolved / background / revised 的键是牵挂在 Prompt 里的编号（从 1 起）。
     */
    public record Consolidation(List<NewMemory> memories, Map<String, String> views, List<NewConcern> concerns,
                                Map<Integer, String> resolved, Set<Integer> background, Map<Integer, String> revised,
                                String self) {

        public boolean empty() {
            return memories.isEmpty() && views.isEmpty() && concerns.isEmpty() && resolved.isEmpty()
                    && background.isEmpty() && revised.isEmpty() && self == null;
        }
    }

    private static final Pattern NUMBERED = Pattern.compile("^(了结|放下|改)·(\\d+)$");

    /**
     * 【记忆】后紧跟【意义】；【看法·姓名】只能写 people 里的人；【牵挂】或【牵挂·姓名】；
     * 【了结·N】【放下·N】【改·N】的 N 在 1..concernCount；【自我】至多一次；什么都没有写【无】。
     */
    public static Consolidation parseConsolidation(String text, List<String> people, int concernCount) {
        List<Section> parts = sections(text, mark -> true);
        if (parts.size() == 1 && parts.getFirst().mark().equals("无")) {
            return new Consolidation(List.of(), Map.of(), List.of(), Map.of(), Set.of(), Map.of(), null);
        }
        List<NewMemory> memories = new ArrayList<>();
        Map<String, String> views = new LinkedHashMap<>();
        List<NewConcern> concerns = new ArrayList<>();
        Map<Integer, String> resolved = new LinkedHashMap<>();
        Set<Integer> background = new java.util.LinkedHashSet<>();
        Map<Integer, String> revised = new LinkedHashMap<>();
        String self = null;
        String pendingEvent = null;
        for (Section s : parts) {
            String mark = s.mark();
            if (pendingEvent != null && !mark.equals("意义")) {
                throw new FormatError("【记忆】后面要紧跟【意义】");
            }
            Matcher numbered = NUMBERED.matcher(mark);
            if (mark.equals("无")) {
                throw new FormatError("【无】只在什么都没有时单独写");
            } else if (mark.equals("记忆")) {
                pendingEvent = nonEmpty(s);
            } else if (mark.equals("意义")) {
                if (pendingEvent == null) {
                    throw new FormatError("【意义】要跟在【记忆】后面");
                }
                memories.add(new NewMemory(pendingEvent, nonEmpty(s)));
                pendingEvent = null;
            } else if (mark.startsWith("看法·")) {
                String who = mark.substring(3);
                if (!people.contains(who)) {
                    throw new FormatError("【看法·" + who + "】不是这段经历里的人");
                }
                if (views.put(who, nonEmpty(s)) != null) {
                    throw new FormatError("【看法·" + who + "】只能写一次");
                }
            } else if (mark.equals("牵挂")) {
                concerns.add(new NewConcern(null, nonEmpty(s)));
            } else if (mark.startsWith("牵挂·")) {
                String who = mark.substring(3);
                if (!people.contains(who)) {
                    throw new FormatError("【牵挂·" + who + "】不是这段经历里的人");
                }
                concerns.add(new NewConcern(who, nonEmpty(s)));
            } else if (numbered.matches()) {
                int n = Integer.parseInt(numbered.group(2));
                if (n < 1 || n > concernCount) {
                    throw new FormatError("没有编号为 " + n + " 的牵挂");
                }
                if (resolved.containsKey(n) || background.contains(n) || revised.containsKey(n)) {
                    throw new FormatError("编号 " + n + " 的牵挂只能处理一次");
                }
                switch (numbered.group(1)) {
                    case "了结" -> resolved.put(n, s.body());
                    case "放下" -> background.add(n);
                    default -> revised.put(n, nonEmpty(s));
                }
            } else if (mark.equals("自我")) {
                if (self != null) {
                    throw new FormatError("【自我】只能写一次");
                }
                self = nonEmpty(s);
            } else {
                throw new FormatError("不认识的标记【" + mark + "】");
            }
        }
        if (pendingEvent != null) {
            throw new FormatError("【记忆】后面要紧跟【意义】");
        }
        return new Consolidation(memories, views, concerns, resolved, background, revised, self);
    }

    private static String nonEmpty(Section s) {
        if (s.body().isEmpty()) {
            throw new FormatError("【" + s.mark() + "】为空");
        }
        return s.body();
    }

    // ---- 一念生 ----

    public record Question(String question, List<String> options, String recommended) {}

    /** 追问：至多 3 组【问题】【选项】【推荐】；不需要追问就写【无】。 */
    public static List<Question> parseQuestions(String text) {
        List<Section> parts = sections(text, mark -> true);
        if (parts.size() == 1 && parts.getFirst().mark().equals("无")) {
            return List.of();
        }
        if (parts.size() % 3 != 0) {
            throw new FormatError("每个问题要有【问题】【选项】【推荐】三行");
        }
        List<Question> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i += 3) {
            if (!parts.get(i).mark().equals("问题") || !parts.get(i + 1).mark().equals("选项")
                    || !parts.get(i + 2).mark().equals("推荐")) {
                throw new FormatError("每个问题按【问题】【选项】【推荐】的顺序写");
            }
            List<String> options = List.of(parts.get(i + 1).body().split("\\s*[｜|]\\s*")).stream()
                    .map(String::strip).filter(o -> !o.isEmpty()).toList();
            String recommended = parts.get(i + 2).body().strip();
            if (parts.get(i).body().isEmpty() || options.size() < 2 || !options.contains(recommended)) {
                throw new FormatError("问题不能为空，至少两个选项，推荐必须是其中之一");
            }
            out.add(new Question(parts.get(i).body(), options, recommended));
        }
        if (out.size() > 3) {
            throw new FormatError("最多问三个问题");
        }
        return out;
    }

    public record DraftWorld(String name, String publicText, String narratorText) {}

    public record DraftView(String name, String text) {}

    public record DraftConcern(String about, String text) {}

    public record DraftCharacter(String name, String profile, List<DraftView> views, List<DraftConcern> concerns) {}

    /** 一念生的草稿。world 为空表示只在已有世界里添人物。 */
    public record Draft(DraftWorld world, List<DraftCharacter> characters) {}

    private static final Set<String> DRAFT_TOP = Set.of("世界名", "世界·公开", "世界·只有叙事者知道", "人物", "牵挂");

    /**
     * 【世界名】【世界·公开】【世界·只有叙事者知道】，然后每个人物：【人物】姓名、档案（带【小标题】）、
     * 若干【看法·某人】与【牵挂】/【牵挂·某人】。knownNames 是已有世界里已经存在的人物，也可以写看法。
     */
    public static Draft parseDraft(String text, boolean newWorld, List<String> knownNames) {
        List<Section> parts = sections(text, mark -> DRAFT_TOP.contains(mark) || mark.startsWith("看法·")
                || mark.startsWith("牵挂·"));
        String worldName = null;
        String publicText = null;
        String narratorText = null;
        List<String> names = new ArrayList<>();
        List<String> profiles = new ArrayList<>();
        List<List<String[]>> extras = new ArrayList<>();
        for (Section s : parts) {
            switch (s.mark()) {
                case "世界名" -> worldName = once(worldName, s);
                case "世界·公开" -> publicText = once(publicText, s);
                case "世界·只有叙事者知道" -> narratorText = once(narratorText, s);
                case "人物" -> {
                    String[] lines = s.body().split("\\R", 2);
                    names.add(lines[0].strip());
                    profiles.add(lines.length > 1 ? lines[1].strip() : "");
                    extras.add(new ArrayList<>());
                }
                default -> {
                    if (names.isEmpty()) {
                        throw new FormatError("【" + s.mark() + "】要写在某个【人物】下面");
                    }
                    extras.getLast().add(new String[] {s.mark(), s.body()});
                }
            }
        }
        DraftWorld world = null;
        if (newWorld) {
            if (worldName == null || worldName.isEmpty() || publicText == null || narratorText == null
                    || publicText.isEmpty() || narratorText.isEmpty()) {
                throw new FormatError("新世界需要【世界名】【世界·公开】【世界·只有叙事者知道】，且不能为空");
            }
            world = new DraftWorld(worldName, publicText, narratorText);
        } else if (worldName != null || publicText != null || narratorText != null) {
            throw new FormatError("在已有世界里创建人物，不应再写世界");
        }
        if (names.isEmpty()) {
            throw new FormatError("没有【人物】");
        }
        if (names.stream().anyMatch(n -> !CHARACTER_NAME.matcher(n).matches())
                || names.stream().distinct().count() != names.size()) {
            throw new FormatError("人物姓名需为 1 到 8 个字、不含空白与（）：·【】，且不重复");
        }
        List<String> everyone = new ArrayList<>(names);
        everyone.addAll(knownNames);
        List<DraftCharacter> characters = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (profiles.get(i).isEmpty()) {
                throw new FormatError("人物“" + names.get(i) + "”没有档案");
            }
            List<DraftView> views = new ArrayList<>();
            List<DraftConcern> concerns = new ArrayList<>();
            for (String[] e : extras.get(i)) {
                if (e[1].isEmpty()) {
                    throw new FormatError("【" + e[0] + "】为空");
                }
                if (e[0].startsWith("看法·")) {
                    String who = e[0].substring(3);
                    if (!everyone.contains(who) || who.equals(names.get(i))) {
                        throw new FormatError(names.get(i) + "的【看法·" + who + "】不是这个世界里的另一个人");
                    }
                    views.add(new DraftView(who, e[1]));
                } else if (e[0].equals("牵挂")) {
                    concerns.add(new DraftConcern(null, e[1]));
                } else {
                    String who = e[0].substring(3);
                    if (!everyone.contains(who) || who.equals(names.get(i))) {
                        throw new FormatError(names.get(i) + "的【牵挂·" + who + "】不是这个世界里的另一个人");
                    }
                    concerns.add(new DraftConcern(who, e[1]));
                }
            }
            characters.add(new DraftCharacter(names.get(i), profiles.get(i), views, concerns));
        }
        return new Draft(world, characters);
    }

    private static String once(String existing, Section s) {
        if (existing != null) {
            throw new FormatError("【" + s.mark() + "】只能写一次");
        }
        return s.body();
    }

    public record Conflict(String problem, String suggestion) {}

    /** 一致性检查：若干组【冲突】【建议】；没有就写【无】。 */
    public static List<Conflict> parseConflicts(String text) {
        List<Section> parts = sections(text, mark -> true);
        if (parts.size() == 1 && parts.getFirst().mark().equals("无")) {
            return List.of();
        }
        if (parts.size() % 2 != 0) {
            throw new FormatError("每个冲突要有【冲突】【建议】两行");
        }
        List<Conflict> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i += 2) {
            if (!parts.get(i).mark().equals("冲突") || !parts.get(i + 1).mark().equals("建议")) {
                throw new FormatError("按【冲突】【建议】的顺序写");
            }
            out.add(new Conflict(nonEmpty(parts.get(i)), nonEmpty(parts.get(i + 1))));
        }
        return out;
    }

    /** 局部修改：直接是改好的那一段，不带说明。 */
    public static String parseRevision(String text) {
        if (text == null || text.isBlank()) {
            throw new FormatError("空响应");
        }
        String s = text.strip();
        if (s.startsWith("```")) {
            throw new FormatError("直接写改好的文字，不加代码块");
        }
        return s;
    }

    /**
     * 边生成边切段：一段（行）或（说）写完（下一个标签出现，或生成结束）就交给 onBeat，心声从不推送。
     * 逐行就能判断的错误（标签之外的文字、写了别人的言行、心声之后还有标签）当场抛 FormatError，
     * 让这次生成提前结束；整体是否合规由 {@link #parseTurn} 最终裁定。
     */
    public static final class TurnStream {

        /** 标签最长的样子是“八个字的名字（心声）：”。 */
        private static final int MAX_LABEL = 14;

        private final String name;
        private final boolean allowActions;
        private final Consumer<Beat> onBeat;
        private final StringBuilder line = new StringBuilder();
        private boolean classified;
        private String tag;
        private StringBuilder body;
        private boolean innerSeen;

        public TurnStream(String name, boolean allowActions, Consumer<Beat> onBeat) {
            this.name = name;
            this.allowActions = allowActions;
            this.onBeat = onBeat;
        }

        public void feed(String delta) {
            for (int i = 0; i < delta.length(); i++) {
                char c = delta.charAt(i);
                if (c == '\r') {
                    continue;
                }
                if (c == '\n') {
                    if (!classified) {
                        classify(true);
                    }
                    if (body != null) {
                        body.append('\n');
                    }
                    classified = false;
                } else if (classified) {
                    if (body != null) {
                        body.append(c);
                    }
                } else {
                    line.append(c);
                    if (line.indexOf("：") >= 0 || line.length() > MAX_LABEL) {
                        classify(false);
                    }
                }
            }
        }

        private void classify(boolean lineEnded) {
            String text = line.toString();
            line.setLength(0);
            Labeled l = text.indexOf('：') >= 0 ? label(text) : null;
            if (l != null) {
                open(l);
            } else if (body != null) {
                body.append(text);
            } else if (!text.isBlank()) {
                throw new FormatError("标签之外有文字");
            }
            classified = !lineEnded;
        }

        private void open(Labeled l) {
            if (l.name() == null) {
                throw new FormatError("人物只写自己的（行）（说）（心声）");
            }
            if (!l.name().equals(name)) {
                throw new FormatError("写了别人的言行：" + l.name());
            }
            if (innerSeen) {
                throw new FormatError("（心声）必须恰好一行且放在最后");
            }
            if (!allowActions && l.tag().equals("行")) {
                throw new FormatError("私聊里只有（说），没有（行）");
            }
            if (body != null && !tag.equals("心声") && !body.toString().isBlank()) {
                onBeat.accept(new Beat(tag, body.toString().strip()));
            }
            tag = l.tag();
            body = new StringBuilder(l.body());
            innerSeen = tag.equals("心声");
        }
    }
}
