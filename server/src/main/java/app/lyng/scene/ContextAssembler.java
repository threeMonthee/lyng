package app.lyng.scene;

import app.lyng.prompt.CharacterContext;
import app.lyng.prompt.Memory;
import app.lyng.prompt.NarratorContext;
import app.lyng.prompt.PastScene;
import app.lyng.prompt.Perception;
import app.lyng.prompt.Relation;
import app.lyng.world.Character;
import app.lyng.world.World;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 把库里的档案、关系、回想和近期原文装配成给 Prompt 的上下文。
 * 人物开口时看到：档案、世界的公共部分、与在场人物的关系、近期场景的原文、更早场景的回想（tech-design §7.3）。
 */
@Component
class ContextAssembler {

    private final SceneRepository scenes;
    private final RecollectionRepository recollections;
    private final MemoryProperties memory;

    ContextAssembler(SceneRepository scenes, RecollectionRepository recollections, MemoryProperties memory) {
        this.scenes = scenes;
        this.recollections = recollections;
        this.memory = memory;
    }

    CharacterContext forCharacter(UUID userId, Scene current, World world, Character who, List<Character> present) {
        List<Scene> ended = scenes.endedScenesOf(userId, who.id()).stream()
                .filter(s -> !s.id().equals(current.id())).toList();
        int split = Math.max(0, ended.size() - memory.recentScenes());
        List<Scene> earlier = ended.subList(Math.max(0, split - memory.maxEarlierRecollections()), split);
        List<Scene> recent = ended.subList(split, ended.size());

        List<Recollection> own = recollections.ofCharacter(userId, who.id());
        Map<UUID, Recollection> bySceneId = new HashMap<>();
        own.forEach(r -> bySceneId.put(r.sceneId(), r));

        List<Memory> memories = earlier.stream().map(s -> new Memory(s.number(), memoryOf(userId, s, who, bySceneId))).toList();
        List<PastScene> originals = recent.stream().map(s -> new PastScene(s.number(), original(userId, s, who))).toList();
        List<Relation> relations = present.stream()
                .filter(other -> !other.id().equals(who.id()))
                .map(other -> relation(other, own))
                .filter(r -> r != null).toList();
        return new CharacterContext(who.name(), world.publicText(), who.profile(), current.scale(), relations, memories, originals);
    }

    /** 回想还没有（失败或还在生成）时，退回到原文：宁可长一点，也不让人物忘了这一场。 */
    private String memoryOf(UUID userId, Scene scene, Character who, Map<UUID, Recollection> bySceneId) {
        Recollection r = bySceneId.get(scene.id());
        return r != null && r.done() ? r.memory() : original(userId, scene, who);
    }

    private String original(UUID userId, Scene scene, Character who) {
        return Perception.of(scenes.events(userId, scene.id()), who.name());
    }

    /** 此前对 other 的看法：最近一条带有 other 的回想。 */
    static Relation relation(Character other, List<Recollection> ownByScene) {
        for (int i = ownByScene.size() - 1; i >= 0; i--) {
            Recollection r = ownByScene.get(i);
            if (r.done() && r.views().containsKey(other.id())) {
                return new Relation(other.name(), r.views().get(other.id()));
            }
        }
        return null;
    }

    NarratorContext forNarrator(UUID userId, Scene scene, World world, Character possessed, List<Character> present) {
        List<String> profiles = new ArrayList<>();
        profiles.add(possessed.profile());
        present.stream().filter(c -> !c.id().equals(possessed.id())).forEach(c -> profiles.add(c.profile()));
        Scene last = scenes.latestEndedInWorld(userId, world.id());
        PastScene previous = last == null ? null
                : new PastScene(last.number(), Perception.forNarrator(scenes.events(userId, last.id())));
        return new NarratorContext(world.publicText(), world.narratorText(), profiles, possessed.name(), scene.tone(),
                scene.scale(), scene.setup(), previous);
    }
}
