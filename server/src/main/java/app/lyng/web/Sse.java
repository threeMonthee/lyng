package app.lyng.web;

import app.lyng.store.Store.Line;
import app.lyng.story.Markup.Beat;
import app.lyng.story.SceneRunner;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把一场戏里正在发生的事写成 SSE，在请求线程里同步写出、逐条 flush。
 * 第一条事件才提交响应头，所以开始前的校验错误仍能作为普通的 JSON 错误返回。
 * 页面断开后不再写，但这一步照常跑完并落库，刷新后从数据库恢复。
 */
final class Sse implements SceneRunner.Listener {

    private final HttpServletResponse response;
    private final JsonMapper json;
    private OutputStream out;
    private boolean gone;

    Sse(HttpServletResponse response, JsonMapper json) {
        this.response = response;
        this.json = json;
    }

    @Override
    public void line(Line line) {
        send("line", line);
    }

    @Override
    public void draft(String speakerId, Beat beat) {
        send("draft", Map.of("speakerId", speakerId, "kind", beat.tag().equals("说") ? "say" : "do", "text", beat.text()));
    }

    @Override
    public void reset(String speakerId) {
        send("reset", Map.of("speakerId", speakerId));
    }

    @Override
    public void typing(String speakerId) {
        Map<String, Object> data = new HashMap<>();
        data.put("speakerId", speakerId);
        send("typing", data);
    }

    @Override
    public void done() {
        send("done", Map.of());
    }

    @Override
    public void failed(String error) {
        send("failed", Map.of("error", error));
    }

    private synchronized void send(String event, Object data) {
        if (gone) {
            return;
        }
        try {
            if (out == null) {
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-cache");
                out = response.getOutputStream();
            }
            out.write(("event: " + event + "\ndata: " + json.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            gone = true;
        }
    }
}
