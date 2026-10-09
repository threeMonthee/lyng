package app.lyng.model;

public record Usage(Integer promptTokens, Integer completionTokens, Integer reasoningTokens) {

    public static final Usage NONE = new Usage(null, null, null);
}
