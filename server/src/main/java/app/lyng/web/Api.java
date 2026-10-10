package app.lyng.web;

import app.lyng.model.Gateway;
import app.lyng.model.ModelProperties;
import app.lyng.store.Store;
import app.lyng.store.Store.Character;
import app.lyng.store.Store.Line;
import app.lyng.store.Store.Memory;
import app.lyng.store.Store.Scene;
import app.lyng.store.Store.World;
import app.lyng.story.Creation;
import app.lyng.story.Markup.Draft;
import app.lyng.story.Prompts.View;
import app.lyng.story.Recall;
import app.lyng.story.SceneRunner;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** 全部接口（tech-design §10）。会跑模型的场景动作返回 SSE，其余是 JSON。 */
@RestController
@RequestMapping("/api")
class Api {

    private final Store store;
    private final SceneRunner scenes;
    private final Creation creation;
    private final Recall recall;
    private final Gateway gateway;
    private final ModelProperties models;
    private final JsonMapper json;

    Api(Store store, SceneRunner scenes, Creation creation, Recall recall, Gateway gateway, ModelProperties models,
        JsonMapper json) {
        this.store = store;
        this.scenes = scenes;
        this.creation = creation;
        this.recall = recall;
        this.gateway = gateway;
        this.models = models;
        this.json = json;
    }

    // ---- 总览 ----

    record WorldItem(String id, String name) {}

    record CharacterItem(String id, String worldId, String name) {}

    record SceneItem(String id, int number, String worldId, List<String> cast, String possessedId, String status,
                     boolean failed, String preview, String startedAt) {}

    record Overview(List<WorldItem> worlds, List<CharacterItem> characters, List<SceneItem> scenes, double spent,
                    Double budget, boolean fake) {}

    @GetMapping("/overview")
    Overview overview() {
        Map<String, String> previews = store.previews();
        return new Overview(
                store.worlds().stream().map(w -> new WorldItem(w.id(), w.name())).toList(),
                store.characters().stream().map(c -> new CharacterItem(c.id(), c.worldId(), c.name())).toList(),
                store.scenes().stream().map(s -> new SceneItem(s.id(), s.number(), s.worldId(), s.cast(), s.possessedId(),
                        s.status(), s.error() != null, previews.get(s.id()), s.startedAt())).toList(),
                gateway.spent(), gateway.budget(), models.backend().equals("fake"));
    }

    // ---- 世界与人物 ----

    record WorldPage(World world, List<CharacterItem> characters) {}

    @GetMapping("/worlds/{id}")
    WorldPage world(@PathVariable String id) {
        World world = store.world(id);
        return new WorldPage(world, store.charactersOf(id).stream()
                .map(c -> new CharacterItem(c.id(), c.worldId(), c.name())).toList());
    }

    record SceneRef(String id, int number, String status, String startedAt, List<String> cast) {}

    record CharacterPage(Character character, String worldName, List<Memory> memories, List<View> views,
                         List<SceneRef> scenes) {}

    @GetMapping("/characters/{id}")
    CharacterPage character(@PathVariable String id) {
        Character c = store.character(id);
        Recall.Page page = recall.page(id);
        return new CharacterPage(c, store.world(c.worldId()).name(), page.memories(), page.views(),
                store.scenesOf(id).stream().map(s -> new SceneRef(s.id(), s.number(), s.status(), s.startedAt(), s.cast())).toList());
    }

    // ---- 创作 ----

    record DraftRequest(String request, String worldId) {}

    @PostMapping("/drafts")
    Draft draft(@RequestBody DraftRequest body) {
        return creation.draft(body.request(), blankToNull(body.worldId()));
    }

    record BirthRequest(String worldId, Draft draft) {}

    @PostMapping("/births")
    Creation.Born birth(@RequestBody BirthRequest body) {
        return creation.birth(body.draft(), blankToNull(body.worldId()));
    }

    // ---- 一场戏 ----

    record NewScene(String worldId, List<String> cast, String possessedId, String followId, String tone, String setup) {}

    @PostMapping("/scenes")
    Map<String, String> create(@RequestBody NewScene body) {
        Scene scene = scenes.create(body.worldId(), body.cast(), blankToNull(body.possessedId()),
                blankToNull(body.followId()), body.tone(), body.setup());
        return Map.of("id", scene.id());
    }

    record CastMember(String id, String name) {}

    record SceneView(String id, int number, String worldId, String worldName, List<CastMember> cast, String possessedId,
                     String followId, String tone, String setup, String status, List<String> pending, String error,
                     String startedAt, String endedAt, List<Line> lines, boolean busy) {}

    @GetMapping("/scenes/{id}")
    SceneView scene(@PathVariable String id) {
        return view(store.scene(id));
    }

    private SceneView view(Scene s) {
        List<CastMember> cast = s.cast().stream().map(store::character).map(c -> new CastMember(c.id(), c.name())).toList();
        return new SceneView(s.id(), s.number(), s.worldId(), store.world(s.worldId()).name(), cast, s.possessedId(),
                s.followId(), s.tone(), s.setup(), s.status(), s.pending() == null ? null : s.pending().steps(), s.error(),
                s.startedAt(), s.endedAt(), store.lines(s.id()), scenes.busy(s.id()));
    }

    record Act(String action, String text) {}

    @PostMapping("/scenes/{id}/act")
    void act(@PathVariable String id, @RequestBody Act body, HttpServletResponse response) {
        scenes.act(id, body.action() == null ? "" : body.action(), body.text(), new Sse(response, json));
    }

    @PostMapping("/scenes/{id}/resume")
    void resume(@PathVariable String id, HttpServletResponse response) {
        scenes.resume(id, new Sse(response, json));
    }

    record End(Boolean closing) {}

    @PostMapping("/scenes/{id}/end")
    void end(@PathVariable String id, @RequestBody End body, HttpServletResponse response) {
        scenes.end(id, body.closing() == null || body.closing(), new Sse(response, json));
    }

    record Identity(String possessedId, String followId) {}

    @PutMapping("/scenes/{id}/identity")
    SceneView identity(@PathVariable String id, @RequestBody Identity body) {
        return view(scenes.setIdentity(id, blankToNull(body.possessedId()), blankToNull(body.followId())));
    }

    // ---- 用量 ----

    record Usage(double spent, Double budget, boolean fake, List<Store.Call> calls) {}

    @GetMapping("/usage")
    Usage usage() {
        return new Usage(gateway.spent(), gateway.budget(), models.backend().equals("fake"), store.recentCalls(100));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
