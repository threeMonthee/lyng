package app.lyng.story;

import app.lyng.model.FormatError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析模型输出（从验证原型 markup.py 移植）。不合规一律抛 FormatError，由网关记为技术失败。
 *
 * <p>人物写“陆雪琪（行）：”“陆雪琪（说）：”“陆雪琪（心声）：”；叙事者每轮写“叙事：”（群戏再加“轮到：”），
 * 开场与收尾直接写正文。这与喂给模型的感知材料是同一套写法。
 */
public final class Markup {

    /** 一行的开头：叙事： / 轮到： / 名字（行|说|心声）： */
    private static final Pattern LABEL = Pattern.compile("^(?:(叙事|轮到)|([^（）：\\s]{1,8})（(行|说|心声)）)：(.*)$", Pattern.DOTALL);
    private static final Pattern NAME_SPLIT = Pattern.compile("[、，,\\s]+");
    private static final Pattern DRAFT_MARK = Pattern.compile("^【(世界名|世界·公开|世界·只有叙事者知道|人物)】(.*)$");
    public static final Pattern CHARACTER_NAME = Pattern.compile("[^（）：\\s]{1,8}");

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
        return m.group(1) != null ? new Labeled(null, m.group(1), m.group(4)) : new Labeled(m.group(2), m.group(3), m.group(4));
    }

    /** 拆成带标签的行。内容可以跨行，直到下一个带标签的行。 */
    private static List<Labeled> split(String text) {
        if (text.isBlank()) {
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

    /** （行）（说）可有可无，（心声）必写且在最后；只有（心声）就是此刻不说也不做。 */
    public static Turn parseTurn(String text, String name) {
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
            beats.add(new Beat(l.tag(), l.body()));
        }
        return new Turn(List.copyOf(beats), inner, beats.isEmpty());
    }

    /** 开场与收尾：直接是叙事文字，不带标签。 */
    public static String parseNarration(String text) {
        if (text.isBlank()) {
            throw new FormatError("空响应");
        }
        if (text.lines().anyMatch(line -> label(line) != null)) {
            throw new FormatError("开场与收尾直接写叙事文字，不加标签");
        }
        return text.strip();
    }

    /** 叙事者一轮的决定：叙事（不出声为 null）与轮到的人物。 */
    public record Round(String narration, List<String> reactors) {}

    /** 双人戏的一轮：恰好一行“叙事：”，不出声写“无”。不做调度，reactors 为空，由调用方让那个人物接戏。 */
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
    private static boolean isNone(String body) {
        return body.replaceAll("[。.！!\\s]+$", "").equals("无");
    }

    /** 回想：这一场的回想，以及对每个在场的其他人物的看法（键为姓名）。 */
    public record Recall(String memory, Map<String, String> views) {}

    /** 一行“回想：”，外加对每个其他在场人物的一行“看待<姓名>：”，各恰好一次。 */
    public static Recall parseRecall(String text, List<String> others) {
        if (text.isBlank()) {
            throw new FormatError("空响应");
        }
        StringBuilder memory = null;
        Map<String, StringBuilder> views = new LinkedHashMap<>();
        StringBuilder current = null;
        for (String line : text.strip().split("\\R", -1)) {
            String s = line.strip();
            if (s.startsWith("回想：")) {
                if (memory != null) {
                    throw new FormatError("“回想：”只能一行");
                }
                memory = current = new StringBuilder(s.substring(3));
            } else if (s.startsWith("看待") && s.contains("：")) {
                String who = s.substring(2, s.indexOf('：'));
                if (!others.contains(who)) {
                    throw new FormatError("“看待" + who + "：”不是在场的其他人物");
                }
                current = new StringBuilder(s.substring(s.indexOf('：') + 1));
                if (views.put(who, current) != null) {
                    throw new FormatError("“看待" + who + "：”只能一行");
                }
            } else if (current != null) {
                current.append('\n').append(line);
            } else if (!s.isEmpty()) {
                throw new FormatError("标签之外有文字");
            }
        }
        if (memory == null) {
            throw new FormatError("缺少“回想：”");
        }
        List<String> missing = others.stream().filter(o -> !views.containsKey(o)).toList();
        if (!missing.isEmpty()) {
            throw new FormatError("缺少“看待”：" + String.join("、", missing));
        }
        Map<String, String> result = new LinkedHashMap<>();
        views.forEach((who, body) -> result.put(who, body.toString().strip()));
        if (memory.toString().isBlank() || result.values().stream().anyMatch(String::isEmpty)) {
            throw new FormatError("“回想：”或“看待：”为空");
        }
        return new Recall(memory.toString().strip(), result);
    }

    /** 一句话创作的草稿。world 为空表示只在已有世界里添人物。 */
    public record Draft(DraftWorld world, List<DraftCharacter> characters) {}

    public record DraftWorld(String name, String publicText, String narratorText) {}

    public record DraftCharacter(String name, String profile) {}

    /** 【世界名】【世界·公开】【世界·只有叙事者知道】若干【人物】姓名 + 叙述式档案。 */
    public static Draft parseDraft(String text, boolean newWorld) {
        if (text.isBlank()) {
            throw new FormatError("空响应");
        }
        String worldName = null;
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        List<StringBuilder> profiles = new ArrayList<>();
        StringBuilder current = null;
        for (String line : text.strip().split("\\R", -1)) {
            Matcher m = DRAFT_MARK.matcher(line.strip());
            if (!m.matches()) {
                if (current != null) {
                    current.append('\n').append(line);
                } else if (!line.isBlank()) {
                    throw new FormatError("标记之外有文字");
                }
                continue;
            }
            String rest = m.group(2).strip();
            switch (m.group(1)) {
                case "世界名" -> {
                    if (worldName != null) {
                        throw new FormatError("【世界名】只能一个");
                    }
                    worldName = rest;
                    current = null;
                }
                case "人物" -> {
                    names.add(rest);
                    current = new StringBuilder();
                    profiles.add(current);
                }
                default -> {
                    current = new StringBuilder();
                    if (sections.put(m.group(1), current) != null) {
                        throw new FormatError("【" + m.group(1) + "】只能一段");
                    }
                }
            }
        }
        DraftWorld world = null;
        if (newWorld) {
            if (worldName == null || worldName.isEmpty() || sections.size() != 2) {
                throw new FormatError("新世界需要【世界名】【世界·公开】【世界·只有叙事者知道】");
            }
            world = new DraftWorld(worldName, sections.get("世界·公开").toString().strip(),
                    sections.get("世界·只有叙事者知道").toString().strip());
            if (world.publicText().isEmpty() || world.narratorText().isEmpty()) {
                throw new FormatError("世界设定不能为空");
            }
        } else if (worldName != null || !sections.isEmpty()) {
            throw new FormatError("在已有世界里创建人物，不应再写世界");
        }
        if (names.isEmpty()) {
            throw new FormatError("没有【人物】");
        }
        if (names.stream().anyMatch(n -> !CHARACTER_NAME.matcher(n).matches()) || names.stream().distinct().count() != names.size()) {
            throw new FormatError("人物姓名需为 1 到 8 个字、不含空白与（）：，且不重复");
        }
        List<DraftCharacter> characters = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String profile = profiles.get(i).toString().strip();
            if (profile.isEmpty()) {
                throw new FormatError("人物“" + names.get(i) + "”没有档案");
            }
            characters.add(new DraftCharacter(names.get(i), profile));
        }
        return new Draft(world, characters);
    }

    /**
     * 边生成边切段：一段（行）或（说）写完（下一个标签出现）就交给 onBeat，心声从不推送。
     * 逐行就能判断的错误（标签之外的文字、写了别人的言行、心声之后还有标签）当场抛 FormatError，
     * 让这次生成提前结束；整体是否合规由 {@link #parseTurn} 最终裁定。
     */
    public static final class TurnStream {

        /** 标签最长的样子是“八个字的名字（心声）：”。 */
        private static final int MAX_LABEL = 14;

        private final String name;
        private final Consumer<Beat> onBeat;
        private final StringBuilder line = new StringBuilder();
        private boolean classified;
        private String tag;
        private StringBuilder body;
        private boolean innerSeen;

        public TurnStream(String name, Consumer<Beat> onBeat) {
            this.name = name;
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
            if (body != null && !tag.equals("心声") && !body.toString().isBlank()) {
                onBeat.accept(new Beat(tag, body.toString().strip()));
            }
            tag = l.tag();
            body = new StringBuilder(l.body());
            innerSeen = tag.equals("心声");
        }
    }
}
