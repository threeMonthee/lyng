package app.lyng.markup;

import java.util.List;

/** 一个 AI 人物的一次反应。silent 表示只有心声：此刻既不说也不做。 */
public record Turn(List<Beat> beats, String inner, boolean silent) {}
