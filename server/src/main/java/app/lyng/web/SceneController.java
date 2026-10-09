package app.lyng.web;

import app.lyng.auth.AuthFilter;
import app.lyng.event.EventKind;
import app.lyng.event.SceneEvent;
import app.lyng.scene.RoundListener;
import app.lyng.scene.Scene;
import app.lyng.scene.SceneService;
import app.lyng.scene.SceneStatus;
import app.lyng.world.Character;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/scenes")
class SceneController {

    private static final Logger log = LoggerFactory.getLogger(SceneController.class);

    record Person(UUID id, String name) {}

    record SceneSummaryView(UUID id, int number, String status, String worldName, String possessed,
                            List<String> others, Instant startedAt) {}

    record SceneView(UUID id, int number, String status, String tone, String setup, UUID worldId, String worldName,
                     Person possessed, List<Person> others, List<EventView> events, boolean opened, boolean busy) {}

    record Created(UUID id, int number) {}

    record Act(String action, String text) {}

    private final SceneService scenes;
    private final JsonMapper json;

    SceneController(SceneService scenes, JsonMapper json) {
        this.scenes = scenes;
        this.json = json;
    }

    @GetMapping
    List<SceneSummaryView> list(@RequestAttribute(AuthFilter.USER) UUID userId) {
        return scenes.list(userId).stream().map(s -> new SceneSummaryView(s.scene().id(), s.scene().number(),
                s.scene().status(), s.worldName(), s.possessed(), s.others(), s.scene().startedAt())).toList();
    }

    @GetMapping("/{id}")
    SceneView get(@RequestAttribute(AuthFilter.USER) UUID userId, @PathVariable UUID id) {
        SceneService.Detail d = scenes.detail(userId, id);
        Set<Integer> unresolved = d.unresolved().stream().map(SceneEvent::seq).collect(Collectors.toSet());
        List<EventView> events = d.events().stream()
                .map(e -> EventView.of(e, e.kind() == EventKind.FAILURE && !unresolved.contains(e.seq())))
                .toList();
        Scene s = d.scene();
        return new SceneView(s.id(), s.number(), s.status(), s.tone(), s.setup(), d.cast().world().id(),
                d.cast().world().name(), person(d.cast().possessed()), d.cast().ai().stream().map(SceneController::person).toList(),
                events, SceneStatus.opened(d.events()), d.busy());
    }

    /** 创建一场戏。先返回场景，再由客户端调用 /open 让叙事者开场。 */
    @PostMapping
    Created create(@RequestAttribute(AuthFilter.USER) UUID userId, @RequestBody SceneService.CreateScene body) {
        Scene scene = scenes.create(userId, body);
        return new Created(scene.id(), scene.number());
    }

    @PostMapping(path = "/{id}/open", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter open(@RequestAttribute(AuthFilter.USER) UUID userId, @PathVariable UUID id) {
        return stream(listener -> scenes.open(userId, id, listener));
    }

    @PostMapping(path = "/{id}/act", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter act(@RequestAttribute(AuthFilter.USER) UUID userId, @PathVariable UUID id, @RequestBody Act body) {
        return stream(listener -> scenes.act(userId, id, body.action(), body.text(), listener));
    }

    @PostMapping(path = "/{id}/retry", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter retry(@RequestAttribute(AuthFilter.USER) UUID userId, @PathVariable UUID id) {
        return stream(listener -> scenes.retry(userId, id, listener));
    }

    @PostMapping(path = "/{id}/end", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter end(@RequestAttribute(AuthFilter.USER) UUID userId, @PathVariable UUID id,
                   @RequestParam(defaultValue = "true") boolean closing) {
        return stream(listener -> scenes.end(userId, id, closing, listener));
    }

    /** 校验与占锁同步完成（失败就是普通的 HTTP 错误）；跑模型的部分在虚拟线程里，边跑边推。 */
    private SseEmitter stream(Function<RoundListener, Runnable> start) {
        SseEmitter emitter = new SseEmitter(0L);
        SseListener listener = new SseListener(emitter, json);
        Runnable task = start.apply(listener);
        Thread.startVirtualThread(() -> {
            try {
                task.run();
                listener.done();
            } catch (RuntimeException e) {
                log.error("一轮中途出错", e);
                listener.error("服务出错了");
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    private static Person person(Character c) {
        return new Person(c.id(), c.name());
    }
}
