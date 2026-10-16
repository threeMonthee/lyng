package app.lyng.mind;

import app.lyng.store.Chats;
import app.lyng.store.Chats.Conversation;
import app.lyng.store.Scenes;
import app.lyng.store.Scenes.Line;
import app.lyng.store.Scenes.Scene;
import app.lyng.store.Traces;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 写下发生的事，同时按可见性记进每个在场人物自己的亲历痕迹。心声只进本人的痕迹。
 * 被附身的人物也一样：做过的事就是 TA 的亲历。
 */
@Service
public class Witness {

    private final Scenes scenes;
    private final Chats chats;
    private final Traces traces;

    public Witness(Scenes scenes, Chats chats, Traces traces) {
        this.scenes = scenes;
        this.chats = chats;
        this.traces = traces;
    }

    public long line(Scene scene, List<UUID> cast, Map<UUID, String> names, int round, String kind, UUID speakerId,
                     String text, boolean byUser, UUID runId) {
        long id = scenes.addLine(scene.id(), round, kind, speakerId, text, byUser, runId);
        Line line = new Line(id, round, kind, speakerId, text, byUser, runId, Instant.now());
        String label = "第" + scene.number() + "场";
        for (UUID viewer : cast) {
            String perceived = Perceive.line(line, names, viewer);
            if (perceived != null) {
                traces.add(viewer, "scene", scene.id(), label, id, perceived);
            }
        }
        return id;
    }

    public long message(Conversation c, Map<UUID, String> names, UUID speakerId, String kind, String text,
                        boolean byUser, String opportunity, boolean unread, UUID runId) {
        long id = chats.add(c.id(), speakerId, kind, text, byUser, opportunity, unread, runId);
        Chats.Message m = new Chats.Message(id, speakerId, kind, text, byUser, opportunity, unread, runId, Instant.now());
        for (UUID viewer : List.of(c.aId(), c.bId())) {
            String perceived = Perceive.message(m, names, viewer);
            if (perceived != null) {
                traces.add(viewer, "chat", c.id(), "和" + names.get(c.other(viewer)) + "的私聊", id, perceived);
            }
        }
        return id;
    }
}
