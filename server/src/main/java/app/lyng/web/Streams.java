package app.lyng.web;

import app.lyng.mind.Events;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 会跑模型的动作用 SSE 回应：工作在虚拟线程上进行，事件逐个写给页面。页面断开后工作照常跑完并落库，
 * 之后的事件直接丢弃；页面刷新后从数据库恢复。请求本身不成立（找不到、冲突、参数不对）时发一个 error 事件。
 */
@Component
public class Streams {

    public SseEmitter run(Consumer<Events> work) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(e -> open.set(false));
        Events events = (name, data) -> {
            if (!open.get()) {
                return;
            }
            synchronized (emitter) {
                try {
                    emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    open.set(false);
                }
            }
        };
        Thread.startVirtualThread(() -> {
            try {
                work.accept(events);
            } catch (RuntimeException e) {
                events.emit("error", Errors.streamError(e));
            } finally {
                events.emit("end", Map.of());
                try {
                    emitter.complete();
                } catch (RuntimeException e) {
                    // 页面早已断开，没有可以结束的连接
                }
            }
        });
        return emitter;
    }
}
