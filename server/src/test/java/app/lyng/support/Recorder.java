package app.lyng.support;

import app.lyng.store.Store.Line;
import app.lyng.story.Markup.Beat;
import app.lyng.story.SceneRunner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 记下一场戏推给页面的每一件事，形如 "line:say:陆雪琪的id:你来了。"、"draft:…"、"reset:…"、"done"、"failed:原因"。 */
public class Recorder implements SceneRunner.Listener {

    public final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void line(Line line) {
        events.add("line:" + line.kind() + ":" + line.speakerId() + ":" + line.text());
    }

    @Override
    public void draft(String speakerId, Beat beat) {
        events.add("draft:" + speakerId + ":" + beat.text());
    }

    @Override
    public void reset(String speakerId) {
        events.add("reset:" + speakerId);
    }

    @Override
    public void typing(String speakerId) {
        events.add("typing:" + speakerId);
    }

    @Override
    public void done() {
        events.add("done");
    }

    @Override
    public void failed(String error) {
        events.add("failed:" + error);
    }

    public boolean failed() {
        return events.stream().anyMatch(e -> e.startsWith("failed:"));
    }
}
