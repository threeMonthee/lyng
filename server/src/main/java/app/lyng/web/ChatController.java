package app.lyng.web;

import app.lyng.chat.ChatService;
import app.lyng.store.Chats;
import app.lyng.store.Chats.Conversation;
import app.lyng.store.Chats.Message;
import app.lyng.store.Scenes;
import app.lyng.store.Worlds.Character;
import app.lyng.web.Shapes.ChatSummary;
import app.lyng.web.Shapes.SceneSummary;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 私聊：列表、打开、发消息与继续（流式）、换附身的一方、已读、离开；你回来了。 */
@RestController
@RequestMapping("/api")
public class ChatController {

    public record Open(UUID playedId, UUID otherId) {}

    public record Send(String text) {}

    public record Play(UUID playedId) {}

    public record ChatPage(ChatSummary chat, List<String> pending, List<Message> messages, boolean more,
                           List<SceneSummary> busy) {}

    static final int PAGE = 60;

    private final ChatService service;
    private final Chats chats;
    private final Scenes scenes;
    private final Shapes shapes;
    private final Streams streams;

    public ChatController(ChatService service, Chats chats, Scenes scenes, Shapes shapes, Streams streams) {
        this.service = service;
        this.chats = chats;
        this.scenes = scenes;
        this.shapes = shapes;
        this.streams = streams;
    }

    @GetMapping("/chats")
    public List<ChatSummary> list() {
        Map<UUID, Character> all = shapes.everyone();
        Map<UUID, String> worldNames = shapes.worldNames();
        return chats.active().stream().map(c -> shapes.chat(c, all, worldNames)).toList();
    }

    @PostMapping("/chats")
    public ChatSummary open(@RequestBody Open body) {
        if (body.playedId() == null || body.otherId() == null) {
            throw new IllegalArgumentException("选你附身的人物和要私聊的人");
        }
        Conversation c = service.open(body.playedId(), body.otherId());
        return shapes.chat(c, shapes.everyone(), shapes.worldNames());
    }

    @GetMapping("/chats/{id}")
    public ChatPage chat(@PathVariable UUID id, @RequestParam(defaultValue = "" + Long.MAX_VALUE) long before) {
        Conversation c = chats.require(id);
        Map<UUID, Character> all = shapes.everyone();
        Map<UUID, String> worldNames = shapes.worldNames();
        List<Message> messages = chats.messages(id, before, PAGE + 1);
        boolean more = messages.size() > PAGE;
        if (more) {
            messages = messages.subList(1, messages.size());
        }
        List<SceneSummary> busy = java.util.stream.Stream.of(c.aId(), c.bId()).flatMap(p -> scenes.openWith(p).stream())
                .distinct().map(s -> shapes.scene(s, all, worldNames)).toList();
        return new ChatPage(shapes.chat(c, all, worldNames), c.pending(), messages, more, busy);
    }

    @PostMapping("/chats/{id}/send")
    public SseEmitter send(@PathVariable UUID id, @RequestBody Send body) {
        return streams.run(events -> service.send(id, body.text(), events));
    }

    @PostMapping("/chats/{id}/continue")
    public SseEmitter proceed(@PathVariable UUID id) {
        return streams.run(events -> service.proceed(id, events));
    }

    @PostMapping("/chats/{id}/retry")
    public SseEmitter retry(@PathVariable UUID id) {
        return streams.run(events -> service.retry(id, events));
    }

    @PostMapping("/chats/{id}/play")
    public void play(@PathVariable UUID id, @RequestBody Play body) {
        service.play(id, body.playedId());
    }

    @PostMapping("/chats/{id}/read")
    public void read(@PathVariable UUID id) {
        service.read(id);
    }

    @PostMapping("/chats/{id}/leave")
    public void leave(@PathVariable UUID id) {
        service.leave(id);
    }

    /** 你回来了：可能有人物主动给你发消息（后台进行）。 */
    @PostMapping("/presence")
    public Map<String, Integer> presence() {
        return Map.of("reaching", service.presence());
    }
}
