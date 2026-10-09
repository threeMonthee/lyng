package app.lyng.markup;

import app.lyng.model.FormatError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析模型输出（从验证原型 markup.py 移植）。不合规一律抛 FormatError，由网关记为技术失败。
 *
 * <p>人物写“陆雪琪（行）：”“陆雪琪（说）：”“陆雪琪（心声）：”；叙事者每轮写“叙事：”（群戏再加“轮到：”），
 * 开场与收尾直接写正文。这与喂给模型的感知材料是同一套写法。
 */
public final class Markup {

    private static final Pattern NAME_SPLIT = Pattern.compile("[、，,\\s]+");

    private Markup() {}

    /** （行）（说）可有可无，（心声）必写且在最后；只有（心声）就是此刻不说也不做。 */
    public static Turn parseTurn(String text, String name) {
        List<LabeledLine> lines = LabeledLine.split(text);
        if (lines.stream().anyMatch(l -> l.name() == null)) {
            throw new FormatError("人物只写自己的（行）（说）（心声）");
        }
        List<String> others = lines.stream().map(LabeledLine::name).filter(n -> !n.equals(name)).distinct().sorted().toList();
        if (!others.isEmpty()) {
            throw new FormatError("写了别人的言行：" + String.join("、", others));
        }
        long innerCount = lines.stream().filter(l -> l.tag().equals("心声")).count();
        if (innerCount != 1 || !lines.getLast().tag().equals("心声")) {
            throw new FormatError("（心声）必须恰好一行且放在最后");
        }
        String inner = lines.getLast().body();
        if (inner.isEmpty()) {
            throw new FormatError("（心声）为空");
        }
        List<Beat> beats = new ArrayList<>();
        for (LabeledLine line : lines.subList(0, lines.size() - 1)) {
            if (line.body().isEmpty()) {
                throw new FormatError("（行）或（说）为空");
            }
            beats.add(new Beat(line.tag(), line.body()));
        }
        return new Turn(List.copyOf(beats), inner, beats.isEmpty());
    }

    /** 开场与收尾：直接是叙事文字，不带标签。 */
    public static String parseNarration(String text) {
        if (text.isBlank()) {
            throw new FormatError("空响应");
        }
        for (String line : text.split("\\R")) {
            int colon = line.indexOf('：');
            if (colon >= 0 && LabeledLine.labelOf(line.substring(0, colon + 1)) != null) {
                throw new FormatError("开场与收尾直接写叙事文字，不加标签");
            }
        }
        return text.strip();
    }

    /** 双人戏的每一轮：恰好一行“叙事：”，不出声就写“无”。不出声时返回空字符串。 */
    public static String parsePairRound(String text) {
        List<LabeledLine> lines = LabeledLine.split(text);
        if (lines.size() != 1 || lines.getFirst().name() != null || !lines.getFirst().tag().equals("叙事")) {
            throw new FormatError("只写一行“叙事：”");
        }
        String body = lines.getFirst().body();
        if (body.isEmpty()) {
            throw new FormatError("“叙事：”不能为空；不出声就写“无”");
        }
        return isNone(body) ? "" : body;
    }

    /** “无”，模型常写成“无。”“无！”：算不出声。 */
    private static boolean isNone(String body) {
        return body.replaceAll("[。.！!\\s]+$", "").equals("无");
    }

    /** 群戏一轮的决定：叙事（没有则为 null）与轮到的人物。 */
    public record Round(String narration, List<String> reactors) {}

    /**
     * 群戏的每一轮：恰好一行“叙事：”、一行“轮到：”，没有就写“无”。
     * 附身的人物不在可选之列，写了就拒绝；candidates 本来就不含他，这里再单独给出明确的原因。
     */
    public static Round parseRound(String text, List<String> candidates, boolean requireSomething, String possessed) {
        List<LabeledLine> lines = LabeledLine.split(text);
        if (lines.stream().anyMatch(l -> l.name() != null)) {
            throw new FormatError("叙事者只写“叙事：”“轮到：”两行，不写人物的言行");
        }
        Map<String, String> body = new LinkedHashMap<>();
        for (LabeledLine l : lines) {
            if (body.put(l.tag(), l.body()) != null || !(l.tag().equals("叙事") || l.tag().equals("轮到"))) {
                throw new FormatError("需要恰好一行“叙事：”和一行“轮到：”");
            }
        }
        if (body.size() != 2) {
            throw new FormatError("需要恰好一行“叙事：”和一行“轮到：”");
        }
        if (body.get("叙事").isEmpty() || body.get("轮到").isEmpty()) {
            throw new FormatError("“叙事：”“轮到：”不能为空；没有就写“无”");
        }
        String narration = body.get("叙事").equals("无") ? null : body.get("叙事");
        String raw = body.get("轮到").replaceAll("。+$", "");
        List<String> names = raw.equals("无") ? List.of()
                : NAME_SPLIT.splitAsStream(raw).filter(n -> !n.isEmpty()).toList();
        if (possessed != null && names.contains(possessed)) {
            throw new FormatError(possessed + "是被附身的人物，不在“轮到：”的可选之列");
        }
        if (!candidates.containsAll(names) || names.stream().distinct().count() != names.size()) {
            throw new FormatError("“轮到：”只能是 " + String.join("、", candidates) + " 或“无”，且不重复");
        }
        if (requireSomething && narration == null && names.isEmpty()) {
            throw new FormatError("推进时必须叙事或至少有一人反应");
        }
        return new Round(narration, names);
    }

    /** 回想的结果：这一场的回想，以及对每个在场的其他人物的看法（键为姓名）。 */
    public record Recall(String memory, Map<String, String> views) {}

    /** 散场后的回想：一行“回想：”，外加对每个其他在场人物的一行“看待<姓名>：”，各恰好一次。 */
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

    private static final Pattern DRAFT_MARK = Pattern.compile("^【(世界名|世界·公开|世界·只有叙事者知道|人物)】(.*)$");
    private static final Pattern CHARACTER_NAME = Pattern.compile("[^（）：\\s]{1,8}");

    /** 一句话创作的输出：【世界名】【世界·公开】【世界·只有叙事者知道】若干【人物】姓名 + 叙述式档案。 */
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
            var m = DRAFT_MARK.matcher(line.strip());
            if (!m.matches()) {
                if (current != null) {
                    current.append('\n').append(line);
                } else if (!line.isBlank()) {
                    throw new FormatError("标记之外有文字");
                }
                continue;
            }
            String mark = m.group(1);
            String rest = m.group(2).strip();
            switch (mark) {
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
                    if (sections.put(mark, current) != null) {
                        throw new FormatError("【" + mark + "】只能一段");
                    }
                }
            }
        }
        return buildDraft(newWorld, worldName, sections, names, profiles);
    }

    private static Draft buildDraft(boolean newWorld, String worldName, Map<String, StringBuilder> sections,
                                    List<String> names, List<StringBuilder> profiles) {
        Draft.WorldDraft world = null;
        if (newWorld) {
            if (worldName == null || worldName.isEmpty() || sections.size() != 2) {
                throw new FormatError("新世界需要【世界名】【世界·公开】【世界·只有叙事者知道】");
            }
            world = new Draft.WorldDraft(worldName, sections.get("世界·公开").toString().strip(),
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
        List<Draft.CharacterDraft> characters = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String profile = profiles.get(i).toString().strip();
            if (profile.isEmpty()) {
                throw new FormatError("人物“" + names.get(i) + "”没有档案");
            }
            characters.add(new Draft.CharacterDraft(names.get(i), profile));
        }
        return new Draft(world, characters);
    }
}
