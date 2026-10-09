package app.lyng.event;

import app.lyng.markup.Beat;
import java.util.List;
import java.util.UUID;

/**
 * 一场戏里发生的一件事。只追加，写入后不改。
 *
 * @param seq     场内递增的序号；写入前为 0
 * @param round   0 是开场，用户每提交一次动作加 1，收尾是最后一轮之后的一轮
 * @param speaker 说话人姓名：USER、CHARACTER 事件，以及 FAILURE 里失败的人物
 * @param action  仅 USER：说 / 做 / 静候
 * @param text    NARRATION 与 USER 的文字；FAILURE 的原因
 * @param step    仅 FAILURE：opening / narrator / character / closing
 * @param refSeq  仅 RESOLVED：被解决的失败的 seq
 */
public record SceneEvent(
        int seq,
        int round,
        EventKind kind,
        UUID speakerId,
        String speaker,
        String action,
        String text,
        List<Beat> beats,
        String inner,
        boolean silent,
        String step,
        Integer refSeq) {

    public static final String SAY = "说";
    public static final String DO = "做";
    public static final String WAIT = "静候";

    public static SceneEvent narration(int round, String text) {
        return new SceneEvent(0, round, EventKind.NARRATION, null, null, null, text, List.of(), null, false, null, null);
    }

    public static SceneEvent user(int round, UUID speakerId, String speaker, String action, String text) {
        return new SceneEvent(0, round, EventKind.USER, speakerId, speaker, action, text, List.of(), null, false, null, null);
    }

    public static SceneEvent character(int round, UUID speakerId, String speaker, List<Beat> beats, String inner,
                                       boolean silent) {
        return new SceneEvent(0, round, EventKind.CHARACTER, speakerId, speaker, null, "", beats, inner, silent, null, null);
    }

    public static SceneEvent failure(int round, String step, UUID speakerId, String speaker, String reason) {
        return new SceneEvent(0, round, EventKind.FAILURE, speakerId, speaker, null, reason, List.of(), null, false, step, null);
    }

    public static SceneEvent resolved(int round, int refSeq) {
        return new SceneEvent(0, round, EventKind.RESOLVED, null, null, null, "", List.of(), null, false, null, refSeq);
    }

    public SceneEvent withSeq(int seq) {
        return new SceneEvent(seq, round, kind, speakerId, speaker, action, text, beats, inner, silent, step, refSeq);
    }
}
