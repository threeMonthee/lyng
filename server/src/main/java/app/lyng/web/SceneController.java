package app.lyng.web;

import app.lyng.scene.SceneService;
import app.lyng.store.Scenes;
import app.lyng.store.Scenes.Line;
import app.lyng.store.Worlds;
import app.lyng.store.Worlds.Character;
import app.lyng.web.Shapes.SceneSummary;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 一场戏：建场、看、你的动作（流式）、换身份、结束。 */
@RestController
@RequestMapping("/api/scenes")
public class SceneController {

    public record CreateScene(UUID worldId, List<UUID> cast, UUID possessedId, UUID followId, String tone, String setup) {}

    public record Act(String action, String text) {}

    public record Identity(UUID possessedId, UUID followId) {}

    public record End(boolean closing) {}

    public record ScenePage(SceneSummary scene, List<String> pending, List<Line> lines, String previousClosing) {}

    private final SceneService service;
    private final Scenes scenes;
    private final Worlds worlds;
    private final Shapes shapes;
    private final Streams streams;

    public SceneController(SceneService service, Scenes scenes, Worlds worlds, Shapes shapes, Streams streams) {
        this.service = service;
        this.scenes = scenes;
        this.worlds = worlds;
        this.shapes = shapes;
        this.streams = streams;
    }

    @PostMapping
    public Map<String, UUID> create(@RequestBody CreateScene body) {
        if (body.worldId() == null || body.cast() == null) {
            throw new IllegalArgumentException("选择世界和在场的人物");
        }
        return Map.of("id", service.create(body.worldId(), body.cast(), body.possessedId(), body.followId(), body.tone(),
                body.setup()));
    }

    @GetMapping("/{id}")
    public ScenePage scene(@PathVariable UUID id) {
        Scenes.Scene s = scenes.require(id);
        Map<UUID, Character> all = shapes.everyone();
        String previous = scenes.previous(s.worldId(), s.number()).flatMap(p -> scenes.lines(p.id()).stream()
                .filter(l -> l.kind().equals("closing")).reduce((a, b) -> b)).map(Line::text).orElse(null);
        return new ScenePage(shapes.scene(s, all, shapes.worldNames()), s.pending(), scenes.lines(id), previous);
    }

    @PostMapping("/{id}/act")
    public SseEmitter act(@PathVariable UUID id, @RequestBody Act body) {
        return streams.run(events -> service.act(id, body.action() == null ? "" : body.action(), body.text(), events));
    }

    /** 开场，或从失败的那一步接着做。 */
    @PostMapping("/{id}/resume")
    public SseEmitter resume(@PathVariable UUID id) {
        return streams.run(events -> service.resume(id, events));
    }

    @PostMapping("/{id}/identity")
    public void identity(@PathVariable UUID id, @RequestBody Identity body) {
        service.switchIdentity(id, body.possessedId(), body.followId());
    }

    @PostMapping("/{id}/end")
    public SseEmitter end(@PathVariable UUID id, @RequestBody End body) {
        return streams.run(events -> service.end(id, body.closing(), events));
    }

    /** 一个世界里还能拉进一场新戏的人物（不在别的进行中的戏里）。 */
    @GetMapping("/available/{worldId}")
    public List<Shapes.Person> available(@PathVariable UUID worldId) {
        return worlds.characters(worldId).stream().filter(c -> scenes.openWith(c.id()).isEmpty())
                .map(c -> new Shapes.Person(c.id(), c.name())).toList();
    }
}
