package app.lyng.story;

import app.lyng.store.Store;
import app.lyng.store.Store.Character;
import app.lyng.store.Store.Line;
import app.lyng.store.Store.Memory;
import app.lyng.store.Store.Scene;
import app.lyng.store.Store.World;
import app.lyng.story.Prompts.Past;
import app.lyng.story.Prompts.Persona;
import app.lyng.story.Prompts.Stage;
import app.lyng.story.Prompts.View;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把库里的档案、看法、回想与原文装配成 Prompt 的上下文（tech-design §6）。
 * 人物开口时看到：档案、世界的公开设定、对在场其他人的看法、更早场景的回想、最近几场的原文。
 */
@Component
public class Contexts {

    /** 最近几场给原文，更早的给回想。 */
    static final int RECENT_SCENES = 2;
    static final int MAX_MEMORIES = 20;

    private final Store store;

    public Contexts(Store store) {
        this.store = store;
    }

    /** 一场戏的在场人物，按入场顺序。 */
    public List<Character> cast(Scene scene) {
        return scene.cast().stream().map(store::character).toList();
    }

    public static Map<String, String> names(List<Character> characters) {
        Map<String, String> names = new HashMap<>();
        characters.forEach(c -> names.put(c.id(), c.name()));
        return names;
    }

    /** 一场已结束的戏里，某人感知到的原文（viewerId 为空：公开经过）。 */
    public String original(Scene scene, String viewerId) {
        return Prompts.perceive(store.lines(scene.id()), names(cast(scene)), viewerId);
    }

    public Persona persona(Scene current, World world, Character who) {
        List<Scene> ended = store.endedScenesOf(who.id()).stream().filter(s -> !s.id().equals(current.id())).toList();
        int split = Math.max(0, ended.size() - RECENT_SCENES);
        List<Scene> earlier = ended.subList(Math.max(0, split - MAX_MEMORIES), split);
        List<Scene> recent = ended.subList(split, ended.size());

        List<Memory> own = store.memories(who.id());
        Map<String, Memory> bySceneId = new HashMap<>();
        own.forEach(m -> bySceneId.put(m.sceneId(), m));

        // 回想还没有（失败或还在生成）时退回原文：宁可长一点，也不让人物忘了这一场
        List<Past> memories = earlier.stream().map(s -> new Past(s.number(),
                bySceneId.containsKey(s.id()) ? bySceneId.get(s.id()).memory() : original(s, who.id()))).toList();
        List<Past> originals = recent.stream().map(s -> new Past(s.number(), original(s, who.id()))).toList();
        List<View> views = new ArrayList<>();
        for (String otherId : current.cast()) {
            if (!otherId.equals(who.id())) {
                View v = view(own, store.character(otherId));
                if (v != null) {
                    views.add(v);
                }
            }
        }
        return new Persona(who.name(), world.publicText(), who.profile(), current.scale(), views, memories, originals);
    }

    /** 此前对 other 的看法：最近一条写到 other 的回想。 */
    static View view(List<Memory> memories, Character other) {
        for (int i = memories.size() - 1; i >= 0; i--) {
            String text = memories.get(i).views().get(other.id());
            if (text != null) {
                return new View(other.name(), text);
            }
        }
        return null;
    }

    public Stage stage(Scene scene, World world) {
        List<Character> cast = cast(scene);
        Map<String, String> names = names(cast);
        Scene last = store.latestEndedInWorld(world.id(), scene.id());
        return new Stage(world.publicText(), world.narratorText(),
                cast.stream().map(c -> Prompts.profileForNarrator(c.name(), c.profile())).toList(),
                names.get(scene.possessedId()), names.get(scene.followId()), scene.tone(), scene.scale(), scene.setup(),
                last == null ? null : new Past(last.number(), original(last, null)));
    }

    /** 本场到某一轮之前的所有行（叙事者决定这一轮时看到的“这一场到现在”）。 */
    static List<Line> before(List<Line> lines, int round) {
        return lines.stream().filter(l -> l.round() < round).toList();
    }
}
