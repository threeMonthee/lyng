package app.lyng.web;

import app.lyng.event.SceneEvent;
import app.lyng.markup.Beat;
import app.lyng.scene.RoundListener;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把一轮里发生的事写成 SSE：user / narration / commit（人物落库）/ failure / resolved / bubble（暂定）/ retract / done / error。
 * 客户端断开后发送会失败；之后静默丢弃，这一轮在服务端照常跑完并落库。
 */
class SseListener implements RoundListener {

    private final SseEmitter emitter;
    private final JsonMapper json;
    private boolean closed;

    SseListener(SseEmitter emitter, JsonMapper json) {
        this.emitter = emitter;
        this.json = json;
    }

    @Override
    public void event(SceneEvent event) {
        String name = switch (event.kind()) {
            case USER -> "user";
            case NARRATION -> "narration";
            case CHARACTER -> "commit";
            case FAILURE -> "failure";
            case RESOLVED -> "resolved";
        };
        send(name, EventView.of(event, false));
    }

    @Override
    public void bubble(String speaker, Beat beat) {
        send("bubble", Map.of("speaker", speaker, "tag", beat.tag(), "text", beat.text()));
    }

    @Override
    public void retract(String speaker) {
        send("retract", Map.of("speaker", speaker));
    }

    void done() {
        send("done", Map.of("ok", true));
    }

    void error(String message) {
        send("error", Map.of("error", message));
    }

    private synchronized void send(String name, Object data) {
        if (closed) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(json.writeValueAsString(data)));
        } catch (IOException | IllegalStateException e) {
            closed = true;
        }
    }
}
