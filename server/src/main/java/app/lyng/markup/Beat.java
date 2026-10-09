package app.lyng.markup;

/** 人物的一个动作或一句话。tag 是“行”或“说”。 */
public record Beat(String tag, String text) {

    public static final String ACT = "行";
    public static final String SAY = "说";
}
