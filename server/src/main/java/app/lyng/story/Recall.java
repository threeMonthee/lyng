package app.lyng.story;

import app.lyng.model.Attempt;
import app.lyng.model.ChatModel.Purpose;
import app.lyng.model.Gateway;
import app.lyng.model.Role;
import app.lyng.store.Store;
import app.lyng.store.Store.Character;
import app.lyng.store.Store.Line;
import app.lyng.store.Store.Memory;
import app.lyng.store.Store.Scene;
import app.lyng.story.Prompts.View;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 散场后为每个在场人物写回想与对其他人的看法（含被附身过的人物：做过的算数，怎么看待由 TA 自己）。
 * 异步生成；新的一场开场前补齐缺的，这是唯一的保证路径。补不上时人物上下文退回原文。
 */
@Service
public class Recall {

    private static final Logger log = LoggerFactory.getLogger(Recall.class);

    private final Store store;
    private final Gateway gateway;
    private final Contexts contexts;
    private final Map<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();

    public Recall(Store store, Gateway gateway, Contexts contexts) {
        this.store = store;
        this.gateway = gateway;
        this.contexts = contexts;
    }

    public void startAsync(String sceneId) {
        store.scene(sceneId).cast().forEach(characterId -> submit(sceneId, characterId));
    }

    /** 这些人物经历过的、已结束的场景里缺回想的，补做并等它们完成。 */
    public void ensure(List<String> characterIds) {
        List<CompletableFuture<Void>> waiting = characterIds.stream().flatMap(characterId -> {
            Set<String> done = store.memories(characterId).stream().map(Memory::sceneId).collect(Collectors.toSet());
            return store.endedScenesOf(characterId).stream().filter(s -> !done.contains(s.id()))
                    .map(s -> submit(s.id(), characterId));
        }).toList();
        waiting.forEach(CompletableFuture::join);
    }

    private CompletableFuture<Void> submit(String sceneId, String characterId) {
        return inFlight.computeIfAbsent(characterId + "/" + sceneId, key -> CompletableFuture.runAsync(() -> {
            try {
                generate(sceneId, characterId);
            } finally {
                inFlight.remove(key);
            }
        }, Thread::startVirtualThread));
    }

    private void generate(String sceneId, String characterId) {
        List<Line> lines = store.lines(sceneId);
        if (lines.stream().allMatch(l -> l.kind().equals("narration"))) {
            return; // 没有人开过口的一场戏，没有什么可回想的
        }
        Scene scene = store.scene(sceneId);
        List<Character> cast = contexts.cast(scene);
        Character who = cast.stream().filter(c -> c.id().equals(characterId)).findFirst().orElseThrow();
        List<Character> others = cast.stream().filter(c -> !c.id().equals(characterId)).toList();
        List<String> otherNames = others.stream().map(Character::name).toList();
        List<Memory> earlier = store.memories(characterId).stream().filter(m -> m.sceneNumber() < scene.number()).toList();
        List<View> before = others.stream().map(o -> Contexts.view(earlier, o)).filter(Objects::nonNull).toList();
        try {
            Markup.Recall recall = gateway.call(Role.RECALL, new Purpose("recall", who.name(), sceneId, otherNames),
                    Prompts.recall(who.name(), store.world(scene.worldId()).publicText(), who.profile(), before, otherNames,
                            Prompts.perceive(lines, Contexts.names(cast), who.id())),
                    () -> Attempt.whole(text -> Markup.parseRecall(text, otherNames)));
            Map<String, String> views = new HashMap<>();
            others.forEach(o -> views.put(o.id(), recall.views().get(o.name())));
            store.saveMemory(characterId, sceneId, recall.memory(), views);
        } catch (RuntimeException e) {
            // 不抛出：下一场开场前会再补；补不上时人物上下文退回原文
            log.warn("回想失败 scene={} character={}：{}", sceneId, who.name(), e.getMessage());
        }
    }

    /** 人物页：TA 记得的事与 TA 现在怎么看每个人，都是 TA 自己的口吻；只读。 */
    public record Page(List<Memory> memories, List<View> views) {}

    public Page page(String characterId) {
        List<Memory> memories = store.memories(characterId);
        Set<String> others = memories.stream().flatMap(m -> m.views().keySet().stream()).collect(Collectors.toSet());
        List<View> views = others.stream().map(id -> Contexts.view(memories, store.character(id)))
                .filter(Objects::nonNull).toList();
        return new Page(memories, views);
    }
}
