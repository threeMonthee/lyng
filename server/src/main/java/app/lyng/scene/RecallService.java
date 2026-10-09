package app.lyng.scene;

import app.lyng.event.EventKind;
import app.lyng.event.SceneEvent;
import app.lyng.markup.Markup;
import app.lyng.model.Attempt;
import app.lyng.model.CallFailure;
import app.lyng.model.CallPurpose;
import app.lyng.model.ModelGateway;
import app.lyng.model.ModelRole;
import app.lyng.prompt.Prompts;
import app.lyng.prompt.Relation;
import app.lyng.world.Character;
import app.lyng.world.WorldRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 散场后，为每个在场人物异步写回想（含被附身的人物：做过的算数，怎么看待由 TA 自己）。
 * 失败或丢失不丢连续性：下一场开始前补做缺失的（{@link #ensureFor}）；补不上时人物上下文退回到原文。
 */
@Service
public class RecallService {

    private static final Logger log = LoggerFactory.getLogger(RecallService.class);

    private record Job(UUID characterId, UUID sceneId) {}

    private final SceneRepository scenes;
    private final RecollectionRepository recollections;
    private final WorldRepository worlds;
    private final ModelGateway gateway;
    private final Map<Job, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();

    public RecallService(SceneRepository scenes, RecollectionRepository recollections, WorldRepository worlds,
                         ModelGateway gateway) {
        this.scenes = scenes;
        this.recollections = recollections;
        this.worlds = worlds;
        this.gateway = gateway;
    }

    /** 一场戏结束后调用：每个在场人物各发起一次，互不阻塞。 */
    public void startAsync(UUID userId, UUID sceneId) {
        scenes.characterIds(userId, sceneId).forEach(characterId -> submit(userId, sceneId, characterId));
    }

    /** 新一场开始前：这些人物经历过的、已结束的场景里，缺回想（或回想失败）的补做，并等它们完成。 */
    public void ensureFor(UUID userId, List<UUID> characterIds) {
        List<CompletableFuture<Void>> pending = characterIds.stream()
                .flatMap(characterId -> missing(userId, characterId).stream().map(s -> submit(userId, s.id(), characterId)))
                .toList();
        pending.forEach(CompletableFuture::join);
    }

    /** 人物页：TA 记得的事（每场的回想）和 TA 现在怎么看每个人，都是 TA 自己的口吻；只读。 */
    public record CharacterMemory(List<Entry> memories, List<Relation> relations) {
        public record Entry(int sceneNumber, String text) {}
    }

    public CharacterMemory memoryOf(UUID userId, UUID characterId) {
        Map<UUID, Integer> numbers = new HashMap<>();
        scenes.endedScenesOf(userId, characterId).forEach(s -> numbers.put(s.id(), s.number()));
        List<Recollection> own = recollections.ofCharacter(userId, characterId).stream().filter(Recollection::done).toList();
        List<CharacterMemory.Entry> memories = own.stream()
                .map(r -> new CharacterMemory.Entry(numbers.getOrDefault(r.sceneId(), 0), r.memory())).toList();
        Map<UUID, Character> others = new HashMap<>();
        own.forEach(r -> r.views().keySet().forEach(id -> others.computeIfAbsent(id, k -> worlds.character(userId, k))));
        List<Relation> relations = others.values().stream().map(o -> ContextAssembler.relation(o, own))
                .filter(r -> r != null).toList();
        return new CharacterMemory(memories, relations);
    }

    private List<Scene> missing(UUID userId, UUID characterId) {
        Map<UUID, Recollection> existing = new HashMap<>();
        recollections.ofCharacter(userId, characterId).forEach(r -> existing.put(r.sceneId(), r));
        return scenes.endedScenesOf(userId, characterId).stream()
                .filter(s -> existing.get(s.id()) == null || !existing.get(s.id()).done()).toList();
    }

    private CompletableFuture<Void> submit(UUID userId, UUID sceneId, UUID characterId) {
        Job job = new Job(characterId, sceneId);
        return inFlight.computeIfAbsent(job, j -> CompletableFuture.runAsync(() -> {
            try {
                generate(userId, sceneId, characterId);
            } finally {
                inFlight.remove(j);
            }
        }, task -> Thread.startVirtualThread(task)));
    }

    private void generate(UUID userId, UUID sceneId, UUID characterId) {
        List<SceneEvent> events = scenes.events(userId, sceneId);
        if (events.stream().noneMatch(e -> e.kind() == EventKind.USER || e.kind() == EventKind.CHARACTER)) {
            return; // 没有人开过口的一场戏，没有什么可回想的
        }
        Scene scene = scenes.scene(userId, sceneId);
        Character who = worlds.character(userId, characterId);
        List<Character> others = scenes.characterIds(userId, sceneId).stream()
                .filter(id -> !id.equals(characterId)).map(id -> worlds.character(userId, id)).toList();
        List<Relation> before = relationsBefore(userId, who, others, scene.number());
        try {
            Markup.Recall recall = gateway.call(userId, ModelRole.RECALL, new CallPurpose("recall", who.name(), sceneId,
                            others.stream().map(Character::name).toList()),
                    Prompts.recall(who.name(), worlds.world(userId, scene.worldId()).publicText(), who.profile(), before,
                            others.stream().map(Character::name).toList(), events),
                    () -> Attempt.whole(text -> Markup.parseRecall(text, others.stream().map(Character::name).toList())));
            Map<UUID, String> views = new HashMap<>();
            others.forEach(o -> views.put(o.id(), recall.views().get(o.name())));
            recollections.save(userId, new Recollection(characterId, sceneId, Recollection.DONE, recall.memory(), views, null));
        } catch (CallFailure f) {
            log.warn("回想失败 scene={} character={}：{}", sceneId, who.name(), f.getMessage());
            recollections.save(userId, new Recollection(characterId, sceneId, Recollection.FAILED, null, Map.of(), f.getMessage()));
        }
    }

    /** 这一场之前，这个人物对每个其他在场人物的最近看法。 */
    private List<Relation> relationsBefore(UUID userId, Character who, List<Character> others, int sceneNumber) {
        Map<UUID, Integer> numbers = new HashMap<>();
        scenes.endedScenesOf(userId, who.id()).forEach(s -> numbers.put(s.id(), s.number()));
        List<Recollection> earlier = recollections.ofCharacter(userId, who.id()).stream()
                .filter(r -> numbers.getOrDefault(r.sceneId(), Integer.MAX_VALUE) < sceneNumber).toList();
        return others.stream().map(o -> ContextAssembler.relation(o, earlier)).filter(r -> r != null).toList();
    }
}
