package app.lyng.web;

import app.lyng.chat.ChatService;
import app.lyng.scene.SceneService;
import app.lyng.store.Chats;
import app.lyng.store.Chats.Conversation;
import app.lyng.store.Chats.Message;
import app.lyng.store.Scenes;
import app.lyng.store.Scenes.Line;
import app.lyng.store.Scenes.Scene;
import app.lyng.store.Worlds;
import app.lyng.store.Worlds.Character;
import app.lyng.store.Worlds.World;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 页面上常用的几种摘要：人物、一场戏、一段私聊。 */
@Component
public class Shapes {

    public record Person(UUID id, String name) {}

    public record SceneSummary(UUID id, UUID worldId, String worldName, int number, String tone, String scale,
                               String setup, String status, List<Person> cast, UUID possessedId, UUID followId,
                               String last, boolean pending, String failure, boolean running, Instant createdAt,
                               Instant closedAt) {}

    public record ChatSummary(UUID id, UUID worldId, String worldName, Person a, Person b, UUID playedId, Message last,
                              int unread, boolean pending, String failure, boolean running, Instant lastAt) {}

    private final Worlds worlds;
    private final Scenes scenes;
    private final Chats chats;
    private final SceneService sceneService;
    private final ChatService chatService;

    public Shapes(Worlds worlds, Scenes scenes, Chats chats, SceneService sceneService, ChatService chatService) {
        this.worlds = worlds;
        this.scenes = scenes;
        this.chats = chats;
        this.sceneService = sceneService;
        this.chatService = chatService;
    }

    /** 所有人物的名字（个人规模，一次取完）。 */
    public Map<UUID, Character> everyone() {
        Map<UUID, Character> all = new HashMap<>();
        worlds.allCharacters().forEach(c -> all.put(c.id(), c));
        return all;
    }

    public Map<UUID, String> worldNames() {
        Map<UUID, String> names = new HashMap<>();
        worlds.worlds().forEach(w -> names.put(w.id(), w.name()));
        return names;
    }

    public Person person(Map<UUID, Character> all, UUID id) {
        Character c = all.get(id);
        return new Person(id, c == null ? "?" : c.name());
    }

    public SceneSummary scene(Scene s, Map<UUID, Character> all, Map<UUID, String> worldNames) {
        List<Line> lines = scenes.lines(s.id());
        String last = null;
        for (int i = lines.size() - 1; i >= 0 && last == null; i--) {
            Line l = lines.get(i);
            if (!l.kind().equals("inner") && !l.kind().equals("wait") && !l.text().isBlank()) {
                last = (l.speakerId() == null ? "" : person(all, l.speakerId()).name() + "：") + l.text();
            }
        }
        List<Person> cast = scenes.cast(s.id()).stream().map(id -> person(all, id)).toList();
        return new SceneSummary(s.id(), s.worldId(), worldNames.get(s.worldId()), s.number(), s.tone(), s.scale(),
                s.setup(), s.status(), cast, s.possessedId(), s.followId(), last, !s.pending().isEmpty(), s.failure(),
                sceneService.running(s.id()), s.createdAt(), s.closedAt());
    }

    public ChatSummary chat(Conversation c, Map<UUID, Character> all, Map<UUID, String> worldNames) {
        Character a = all.get(c.aId());
        UUID worldId = a == null ? null : a.worldId();
        return new ChatSummary(c.id(), worldId, worldNames.get(worldId), person(all, c.aId()), person(all, c.bId()),
                c.playedId(), chats.last(c.id()).orElse(null), chats.unread(c.id()), !c.pending().isEmpty(),
                c.failure(), chatService.running(c.id()), c.lastAt());
    }

    public World world(UUID id) {
        return worlds.requireWorld(id);
    }
}
