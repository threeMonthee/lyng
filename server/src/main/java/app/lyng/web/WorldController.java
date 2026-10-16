package app.lyng.web;

import app.lyng.create.CreationService;
import app.lyng.model.ModelProperties;
import app.lyng.store.Chats;
import app.lyng.store.Inner;
import app.lyng.store.Scenes;
import app.lyng.store.Traces;
import app.lyng.store.Worlds;
import app.lyng.store.Worlds.Character;
import app.lyng.store.Worlds.World;
import app.lyng.web.Shapes.ChatSummary;
import app.lyng.web.Shapes.Person;
import app.lyng.web.Shapes.SceneSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 首页、世界、人物主页（关于 / 牵挂 / 记忆 / 看法 / 经历）。只读，不触发人物。 */
@RestController
@RequestMapping("/api")
public class WorldController {

    public record WorldCard(UUID id, String name, List<Person> characters, int scenes, boolean busy) {}

    public record Home(List<WorldCard> worlds, List<SceneSummary> openScenes, List<SceneSummary> recentScenes,
                       List<ChatSummary> chats, int unread, List<CreationService.Page> drafts, boolean fake) {}

    public record CharacterCard(UUID id, String name, String profile, SceneSummary openScene) {}

    public record WorldPage(UUID id, String name, String publicText, String narratorText, List<CharacterCard> characters,
                            List<SceneSummary> scenes, List<ChatSummary> chats, Instant createdAt) {}

    public record ViewItem(UUID otherId, String otherName, String text, UUID runId, Instant createdAt, int versions) {}

    public record ConcernItem(UUID id, UUID aboutId, String aboutName, String text, String status, String resolution,
                              UUID runId, Instant createdAt, Instant updatedAt) {}

    public record CharacterPage(UUID id, UUID worldId, String worldName, String name, String profile, Inner.Self self,
                                int selfVersions, List<ViewItem> views, List<ConcernItem> concerns, int memories,
                                SceneSummary openScene, List<ChatSummary> chats, List<Person> others,
                                Instant createdAt) {}

    public record MemoryItem(UUID id, String event, String meaning, List<Person> people, String sourceKind,
                             UUID sourceId, String sourceLabel, UUID runId, Instant createdAt) {}

    public record Memories(List<MemoryItem> items, int total) {}

    private final Worlds worlds;
    private final Inner inner;
    private final Scenes scenes;
    private final Chats chats;
    private final Traces traces;
    private final CreationService creation;
    private final Shapes shapes;
    private final ModelProperties model;

    public WorldController(Worlds worlds, Inner inner, Scenes scenes, Chats chats, Traces traces,
                           CreationService creation, Shapes shapes, ModelProperties model) {
        this.worlds = worlds;
        this.inner = inner;
        this.scenes = scenes;
        this.chats = chats;
        this.traces = traces;
        this.creation = creation;
        this.shapes = shapes;
        this.model = model;
    }

    @GetMapping("/home")
    public Home home() {
        Map<UUID, Character> all = shapes.everyone();
        Map<UUID, String> worldNames = shapes.worldNames();
        List<WorldCard> cards = worlds.worlds().stream().map(w -> {
            List<Character> people = worlds.characters(w.id());
            List<Scenes.Scene> list = scenes.byWorld(w.id());
            return new WorldCard(w.id(), w.name(), people.stream().map(c -> new Person(c.id(), c.name())).toList(),
                    list.size(), list.stream().anyMatch(Scenes.Scene::open));
        }).toList();
        List<Scenes.Scene> recent = scenes.recent(12);
        List<SceneSummary> open = recent.stream().filter(Scenes.Scene::open)
                .map(s -> shapes.scene(s, all, worldNames)).toList();
        List<SceneSummary> closed = recent.stream().filter(s -> !s.open()).limit(4)
                .map(s -> shapes.scene(s, all, worldNames)).toList();
        List<ChatSummary> recentChats = chats.active().stream().limit(4).map(c -> shapes.chat(c, all, worldNames)).toList();
        return new Home(cards, open, closed, recentChats, chats.unreadTotal(), creation.unfinished(),
                "fake".equals(model.backend()));
    }

    @GetMapping("/worlds/{id}")
    public WorldPage world(@PathVariable UUID id) {
        World w = worlds.requireWorld(id);
        Map<UUID, Character> all = shapes.everyone();
        Map<UUID, String> worldNames = shapes.worldNames();
        List<CharacterCard> people = worlds.characters(id).stream().map(c -> new CharacterCard(c.id(), c.name(),
                c.profile(), scenes.openWith(c.id()).stream().findFirst().map(s -> shapes.scene(s, all, worldNames))
                        .orElse(null))).toList();
        List<SceneSummary> list = scenes.byWorld(id).stream().map(s -> shapes.scene(s, all, worldNames)).toList();
        List<ChatSummary> worldChats = chats.active().stream().filter(c -> id.equals(all.get(c.aId()).worldId()))
                .map(c -> shapes.chat(c, all, worldNames)).toList();
        return new WorldPage(w.id(), w.name(), w.publicText(), w.narratorText(), people, list, worldChats, w.createdAt());
    }

    @GetMapping("/characters/{id}")
    public CharacterPage character(@PathVariable UUID id) {
        Character me = worlds.requireCharacter(id);
        Map<UUID, Character> all = shapes.everyone();
        Map<UUID, String> worldNames = shapes.worldNames();
        List<ViewItem> views = inner.views(id).stream().map(v -> new ViewItem(v.otherId(),
                shapes.person(all, v.otherId()).name(), v.text(), v.runId(), v.createdAt(),
                inner.viewHistory(id, v.otherId()).size())).toList();
        List<ConcernItem> concerns = inner.concerns(id).stream().map(c -> new ConcernItem(c.id(), c.aboutId(),
                c.aboutId() == null ? null : shapes.person(all, c.aboutId()).name(), c.text(), c.status(),
                c.resolution(), c.runId(), c.createdAt(), c.updatedAt())).toList();
        SceneSummary open = scenes.openWith(id).stream().findFirst().map(s -> shapes.scene(s, all, worldNames)).orElse(null);
        List<ChatSummary> mine = chats.with(id).stream().filter(c -> chats.lastId(c.id()) > 0)
                .map(c -> shapes.chat(c, all, worldNames)).toList();
        List<Person> others = worlds.characters(me.worldId()).stream().filter(c -> !c.id().equals(id))
                .map(c -> new Person(c.id(), c.name())).toList();
        return new CharacterPage(me.id(), me.worldId(), worldNames.get(me.worldId()), me.name(), me.profile(),
                inner.self(id).orElse(null), inner.selfHistory(id).size(), views, concerns, inner.memoryCount(id), open,
                mine, others, me.createdAt());
    }

    @GetMapping("/characters/{id}/self")
    public List<Inner.Self> selfHistory(@PathVariable UUID id) {
        worlds.requireCharacter(id);
        return inner.selfHistory(id);
    }

    @GetMapping("/characters/{id}/views/{otherId}")
    public List<Inner.View> viewHistory(@PathVariable UUID id, @PathVariable UUID otherId) {
        worlds.requireCharacter(id);
        return inner.viewHistory(id, otherId);
    }

    @GetMapping("/characters/{id}/memories")
    public Memories memories(@PathVariable UUID id, @RequestParam(defaultValue = "0") int offset,
                             @RequestParam(defaultValue = "20") int limit) {
        worlds.requireCharacter(id);
        Map<UUID, Character> all = shapes.everyone();
        List<MemoryItem> items = inner.memories(id, Math.min(limit, 100), Math.max(offset, 0)).stream()
                .map(m -> new MemoryItem(m.id(), m.event(), m.meaning(),
                        m.people().stream().map(p -> shapes.person(all, p)).toList(), m.sourceKind(), m.sourceId(),
                        m.sourceLabel(), m.runId(), m.createdAt()))
                .toList();
        return new Memories(items, inner.memoryCount(id));
    }

    /** 人物亲历过的每一场戏、每一段私聊（TA 自己看到的那一份）。 */
    @GetMapping("/characters/{id}/experiences")
    public List<Traces.Source> experiences(@PathVariable UUID id) {
        worlds.requireCharacter(id);
        return traces.sources(id);
    }

    @GetMapping("/characters/{id}/experiences/{sourceId}")
    public List<Traces.Trace> experience(@PathVariable UUID id, @PathVariable UUID sourceId,
                                         @RequestParam(defaultValue = "0") long after) {
        worlds.requireCharacter(id);
        return traces.bySource(id, sourceId, after, 200);
    }
}
