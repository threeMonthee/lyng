package app.lyng.markup;

import app.lyng.model.FormatError;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出与感知材料共用的“标签：内容”写法的一行：叙事： / 轮到： / 判定： / 名字（行|说|心声）：。
 *
 * @param name 人物姓名；叙事者的标签为 null
 * @param tag  叙事、轮到、判定，或人物的 行、说、心声
 */
record LabeledLine(String name, String tag, String body) {

    private static final Pattern LABEL = Pattern.compile("^(?:(叙事|轮到|判定)|([^（）：\\s]{1,8})（(行|说|心声)）)：$");

    /** text 是以第一个“：”结尾的行首；是标签返回 label 部分，否则 null。 */
    static LabeledLine labelOf(String text) {
        Matcher m = LABEL.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        return m.group(1) != null ? new LabeledLine(null, m.group(1), "") : new LabeledLine(m.group(2), m.group(3), "");
    }

    /** 整段文字拆成带标签的行；内容可以跨行，直到下一个带标签的行。标签之外的文字、空响应抛 FormatError。 */
    static List<LabeledLine> split(String text) {
        if (text.isBlank()) {
            throw new FormatError("空响应");
        }
        List<LabeledLine> out = new ArrayList<>();
        List<StringBuilder> bodies = new ArrayList<>();
        for (String line : text.strip().split("\\R", -1)) {
            int colon = line.indexOf('：');
            LabeledLine label = colon < 0 ? null : labelOf(line.substring(0, colon + 1));
            if (label != null) {
                out.add(label);
                bodies.add(new StringBuilder(line.substring(colon + 1)));
            } else if (out.isEmpty()) {
                if (!line.isBlank()) {
                    throw new FormatError("标签之外有文字");
                }
            } else {
                bodies.getLast().append('\n').append(line);
            }
        }
        List<LabeledLine> result = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            result.add(new LabeledLine(out.get(i).name, out.get(i).tag, bodies.get(i).toString().strip()));
        }
        return result;
    }
}
