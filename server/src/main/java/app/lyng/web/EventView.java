package app.lyng.web;

import app.lyng.event.SceneEvent;
import app.lyng.markup.Beat;
import java.util.List;
import java.util.UUID;

/** 客户端看到的一件事。kind 是小写英文；resolved 只对 failure 有意义。 */
record EventView(int seq, int round, String kind, UUID speakerId, String speaker, String action, String text,
                 List<Beat> beats, String inner, boolean silent, String step, Integer refSeq, boolean resolved) {

    static EventView of(SceneEvent e, boolean resolved) {
        return new EventView(e.seq(), e.round(), e.kind().name().toLowerCase(), e.speakerId(), e.speaker(), e.action(),
                e.text(), e.beats(), e.inner(), e.silent(), e.step(), e.refSeq(), resolved);
    }
}
