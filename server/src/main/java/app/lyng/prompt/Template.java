package app.lyng.prompt;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把模板里的 {名字} 一次性换成值；值里出现的花括号不会被再次替换。 */
final class Template {

    private static final Pattern SLOT = Pattern.compile("\\{(\\w+)}");

    private Template() {}

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
}
