package app.lyng.markup;

import app.lyng.model.FormatError;
import java.util.function.Consumer;

/**
 * 边生成边解析人物的一次反应：一个（行）或（说）的内容一旦写完（下一个标签出现），就作为一个气泡交给 onBeat。
 * 逐行可判的错误（写了别人的言行、标签之外的文字、心声之后还有内容）当场抛 FormatError，让网关中止这次生成；
 * 整体是否合规（心声恰好一行、不为空等）由 finish 用 {@link Markup#parseTurn} 最终裁定。
 */
public final class TurnStreamParser {

    private static final int MAX_LABEL_CHARS = 14;

    private final String name;
    private final Consumer<Beat> onBeat;
    private final StringBuilder pending = new StringBuilder();
    private String currentTag;
    private StringBuilder currentBody;
    private boolean lineClassified;
    private boolean innerSeen;

    public TurnStreamParser(String name, Consumer<Beat> onBeat) {
        this.name = name;
        this.onBeat = onBeat;
    }

    public void feed(String delta) {
        for (int i = 0; i < delta.length(); i++) {
            char c = delta.charAt(i);
            if (c == '\n' || c == '\r') {
                endLine(c);
            } else if (lineClassified) {
                append(c);
            } else {
                pending.append(c);
                classifyIfDecidable();
            }
        }
    }

    /** 生成结束：处理最后一行，校验整体，返回这一次反应。 */
    public Turn finish(String fullText) {
        endLine('\n');
        flushCurrent();
        return Markup.parseTurn(fullText, name);
    }

    private void endLine(char c) {
        if (c == '\r') {
            return;
        }
        if (!lineClassified) {
            classify(true);
        }
        if (currentBody != null) {
            currentBody.append('\n');
        }
        lineClassified = false;
    }

    private void classifyIfDecidable() {
        if (pending.indexOf("：") >= 0 || pending.length() > MAX_LABEL_CHARS) {
            classify(false);
        }
    }

    /** 判断这一行是不是标签行；lineEnded 表示整行已经到齐。 */
    private void classify(boolean lineEnded) {
        String line = pending.toString();
        pending.setLength(0);
        int colon = line.indexOf('：');
        LabeledLine label = colon < 0 ? null : LabeledLine.labelOf(line.substring(0, colon + 1));
        if (label == null) {
            if (currentBody == null && !line.isBlank()) {
                throw new FormatError("标签之外有文字");
            }
            if (currentBody != null) {
                currentBody.append(line);
            }
        } else {
            open(label, line.substring(colon + 1));
        }
        lineClassified = !lineEnded;
    }

    private void open(LabeledLine label, String firstChunk) {
        if (label.name() == null) {
            throw new FormatError("人物只写自己的（行）（说）（心声）");
        }
        if (!label.name().equals(name)) {
            throw new FormatError("写了别人的言行：" + label.name());
        }
        if (innerSeen) {
            throw new FormatError("（心声）必须恰好一行且放在最后");
        }
        flushCurrent();
        currentTag = label.tag();
        currentBody = new StringBuilder(firstChunk);
        innerSeen = label.tag().equals("心声");
    }

    private void append(char c) {
        if (currentBody != null) {
            currentBody.append(c);
        }
    }

    private void flushCurrent() {
        if (currentBody == null) {
            return;
        }
        String text = currentBody.toString().strip();
        if (!currentTag.equals("心声") && !text.isEmpty()) {
            onBeat.accept(new Beat(currentTag, text));
        }
        currentBody = null;
        currentTag = null;
    }
}
