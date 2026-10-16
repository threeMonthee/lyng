package app.lyng.mind;

import app.lyng.mind.Markup.Beat;
import app.lyng.mind.Markup.Turn;
import app.lyng.mind.Prompts.Care;
import app.lyng.mind.Prompts.Persona;
import app.lyng.mind.Prompts.Recalled;
import app.lyng.mind.Prompts.ViewOf;
import app.lyng.model.Gateway;
import app.lyng.model.Gateway.Links;
import app.lyng.model.Msg;
import app.lyng.model.Purpose;
import app.lyng.model.Tool;
import app.lyng.store.Inner;
import app.lyng.store.Inner.Memory;
import app.lyng.store.Worlds;
import app.lyng.store.Worlds.Character;
import app.lyng.store.Worlds.World;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 一个 AI 人物开口：装配 TA 看到的自己（档案、自我、看法、牵挂、记得的事），一次生成写出言行与心声。
 * 人物可以调用 recall 自己去翻更早的经历。一场戏与私聊共用这里。
 */
@Service
public class Voice {

    static final int ACTIVE_CARES = 8;

    private final Gateway gateway;
    private final Inner inner;
    private final Worlds worlds;
    private final Recall recall;
    private final Consolidator consolidator;
    private final JsonMapper json;

    public Voice(Gateway gateway, Inner inner, Worlds worlds, Recall recall, Consolidator consolidator, JsonMapper json) {
        this.gateway = gateway;
        this.inner = inner;
        this.worlds = worlds;
        this.recall = recall;
        this.consolidator = consolidator;
        this.json = json;
    }

    /** 流式接收：一段（行）或（说）写完就交出；discard 表示这次尝试作废，已交出的要收回。 */
    public interface Sink {
        void beat(Beat beat);

        void discard();
    }

    /** 当前来源：一场戏，或一段私聊里从 fromRef 起已经在上下文里的部分。召回不重复这些。 */
    public record Here(String kind, UUID sourceId, long fromRef) {}

    /**
     * 人物此刻看到的自己。others 是眼前的人（一场戏的在场者，或私聊的对方）；situation 是最近发生的事，用来召回。
     */
    public Persona persona(Character me, World world, List<Character> others, String situation, Here here, String scale) {
        consolidator.catchUp(me.id());
        String self = inner.self(me.id()).map(Inner.Self::text).orElse(null);
        List<ViewOf> views = new ArrayList<>();
        for (Character other : others) {
            inner.view(me.id(), other.id()).ifPresent(v -> views.add(new ViewOf(other.name(), v.text())));
        }
        Map<UUID, String> names = names(me.worldId());
        List<Care> cares = inner.activeConcerns(me.id(), ACTIVE_CARES).stream()
                .map(c -> new Care(c.text(), c.aboutId() == null ? null : names.get(c.aboutId()))).toList();
        List<Memory> recentMemories = recall.recentMemories(me.id());
        List<Recalled> recent = recentMemories.stream().map(Recall::recalled).toList();
        List<Recalled> recalled = recall.around(me.id(), situation, recentMemories.stream().map(Memory::id).toList(),
                here.sourceId(), here.fromRef());
        return new Persona(me.name(), world.publicText(), me.profile(), self, views, cares, recent, recalled, scale);
    }

    /** 开口。成功返回解析好的言行与心声和运行 id；失败抛 CallFailure。 */
    public Gateway.Result<Turn> speak(Character me, List<Msg> messages, boolean allowActions, Links links, String title,
                                      Here here, Sink sink) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", me.name());
        meta.put("allowActions", allowActions);
        Function<app.lyng.model.ToolCall, String> runner = call -> {
            if (!Prompts.RECALL_TOOL.equals(call.name())) {
                return "没有这个工具。";
            }
            JsonNode args = json.readTree(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments());
            return recall.search(me.id(), args.path("query").asString(""), here.sourceId(), here.fromRef());
        };
        Gateway.Spec spec = new Gateway.Spec(Purpose.CHARACTER, title, links, messages,
                List.of(new Tool(Prompts.RECALL_TOOL, Prompts.RECALL_TOOL_DESCRIPTION, Prompts.RECALL_TOOL_PARAMETERS)),
                runner, meta);
        return gateway.run(spec, () -> new Gateway.Attempt<>() {
            private Markup.TurnStream stream = new Markup.TurnStream(me.name(), allowActions, sink::beat);

            @Override
            public void content(String delta) {
                stream.feed(delta);
            }

            @Override
            public void abandon() {
                sink.discard();
                stream = new Markup.TurnStream(me.name(), allowActions, sink::beat);
            }

            @Override
            public Turn finish(String text) {
                return Markup.parseTurn(text, me.name(), allowActions);
            }
        });
    }

    public Map<UUID, String> names(UUID worldId) {
        Map<UUID, String> names = new LinkedHashMap<>();
        worlds.characters(worldId).forEach(c -> names.put(c.id(), c.name()));
        return names;
    }
}
