package app.lyng.scene;

import app.lyng.mind.Consolidator;
import app.lyng.mind.Events;
import app.lyng.mind.Markup;
import app.lyng.mind.Markup.Beat;
import app.lyng.mind.Markup.Round;
import app.lyng.mind.Markup.Turn;
import app.lyng.mind.Perceive;
import app.lyng.mind.Prompts;
import app.lyng.mind.Prompts.Stage;
import app.lyng.mind.Voice;
import app.lyng.mind.Witness;
import app.lyng.model.CallFailure;
import app.lyng.model.Gateway;
import app.lyng.model.Gateway.Links;
import app.lyng.model.Msg;
import app.lyng.model.Purpose;
import app.lyng.store.Conflict;
import app.lyng.store.NotFound;
import app.lyng.store.Scenes;
import app.lyng.store.Scenes.Line;
import app.lyng.store.Scenes.Scene;
import app.lyng.store.Settings;
import app.lyng.store.Worlds;
import app.lyng.store.Worlds.Character;
import app.lyng.store.Worlds.World;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * 一场戏。每一轮都是“叙事者先行”：你的动作 → 叙事者（叙事；群戏再决定轮到谁）→ 被点到的人物依次开口。
 *
 * <p>一场戏要做的事都是“步骤”：opening、narrator、某个人物的 id、closing。场景上记着还剩哪几步；
 * 每一步成功就落库并推进，失败就停在这一步，重试从剩下的第一步接着做。已经落库的行没有任何改写的接口。
 */
@Service
public class SceneService {

    public static final String OPENING = "opening";
    public static final String NARRATOR = "narrator";
    public static final String CLOSING = "closing";
    static final int PREVIOUS_LINES = 60;

    private final Scenes scenes;
    private final Worlds worlds;
    private final Settings settings;
    private final Gateway gateway;
    private final Voice voice;
    private final Witness witness;
    private final Consolidator consolidator;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SceneService(Scenes scenes, Worlds worlds, Settings settings, Gateway gateway, Voice voice, Witness witness,
                        Consolidator consolidator) {
        this.scenes = scenes;
        this.worlds = worlds;
        this.settings = settings;
        this.gateway = gateway;
        this.voice = voice;
        this.witness = witness;
        this.consolidator = consolidator;
    }

    // ---- 建场 ----

    public UUID create(UUID worldId, List<UUID> cast, UUID possessedId, UUID followId, String tone, String setup) {
        World world = worlds.requireWorld(worldId);
        if (cast.isEmpty() || cast.stream().distinct().count() != cast.size()) {
            throw new IllegalArgumentException("选择在场的人物，且不重复");
        }
        List<Character> people = worlds.characters(cast);
        if (people.stream().anyMatch(c -> !c.worldId().equals(world.id()))) {
            throw new IllegalArgumentException("在场的人物都要来自这个世界");
        }
        if (possessedId != null && !cast.contains(possessedId)) {
            throw new IllegalArgumentException("附身的人物要在场");
        }
        if (possessedId == null && (followId == null || !cast.contains(followId))) {
            throw new IllegalArgumentException("旁观时要选镜头跟随在场的哪个人物");
        }
        if (cast.stream().filter(id -> !id.equals(possessedId)).findAny().isEmpty()) {
            throw new IllegalArgumentException("至少要有一个不由你附身的人物在场");
        }
        if (!Prompts.DAILY.equals(tone) && !Prompts.ADVENTURE.equals(tone)) {
            throw new IllegalArgumentException("基调只有日常相处与冒险");
        }
        if (setup == null || setup.isBlank()) {
            throw new IllegalArgumentException("写一句开场处境");
        }
        for (Character c : people) {
            List<Scene> busy = scenes.openWith(c.id());
            if (!busy.isEmpty()) {
                throw new Conflict(c.name() + "还在第" + busy.getFirst().number() + "场里，先结束那一场");
            }
        }
        return scenes.create(worldId, tone, settings.get().scale(), setup.strip(), possessedId,
                possessedId == null ? followId : null, cast);
    }

    // ---- 你的动作 ----

    /** 附身时：say / do / wait；旁观时：continue。 */
    public void act(UUID sceneId, String action, String text, Events events) {
        withLock(sceneId, () -> {
            Scene scene = scenes.require(sceneId);
            requireIdle(scene);
            if (scene.possessedId() == null && !action.equals("continue")) {
                throw new IllegalArgumentException("旁观时只能点“继续”");
            }
            if (scene.possessedId() != null && !List.of("say", "do", "wait").contains(action)) {
                throw new IllegalArgumentException("附身时可以说、做或静候");
            }
            boolean needsText = action.equals("say") || action.equals("do");
            if (needsText && (text == null || text.isBlank())) {
                throw new IllegalArgumentException(action.equals("say") ? "写下要说的话" : "写下要做的事");
            }
            Ctx ctx = context(scene);
            if (action.equals("do") && scene.scale().equals(Prompts.ADULT) && consentRefused(ctx, text.strip(), events)) {
                return;
            }
            int round = scene.round() + 1;
            scenes.setRound(scene.id(), round);
            if (scene.possessedId() != null) {
                String kind = action.equals("wait") ? "wait" : action;
                long id = witness.line(scene, ctx.cast, ctx.names, round, kind, scene.possessedId(),
                        action.equals("wait") ? "" : text.strip(), true, null);
                events.emit("saved", Map.of("lines", List.of(lineOf(scene.id(), id))));
            }
            scenes.setPending(scene.id(), List.of(NARRATOR), null);
            runPending(scene.id(), events);
        });
    }

    /** 从剩下的第一步接着做（开场，或技术失败后的重试）。 */
    public void resume(UUID sceneId, Events events) {
        withLock(sceneId, () -> {
            Scene scene = scenes.require(sceneId);
            if (!scene.open()) {
                throw new Conflict("这一场已经结束了");
            }
            runPending(sceneId, events);
        });
    }

    /** 换一个附身的人物，或转为旁观（followId 是镜头跟随的人）。只改身份，不写任何行。 */
    public void switchIdentity(UUID sceneId, UUID possessedId, UUID followId) {
        withLock(sceneId, () -> {
            Scene scene = scenes.require(sceneId);
            requireIdle(scene);
            List<UUID> cast = scenes.cast(sceneId);
            if (possessedId != null && !cast.contains(possessedId)) {
                throw new IllegalArgumentException("只能附身在场的人物");
            }
            if (possessedId == null && (followId == null || !cast.contains(followId))) {
                throw new IllegalArgumentException("旁观时要选镜头跟随在场的哪个人物");
            }
            if (cast.stream().filter(id -> !id.equals(possessedId)).findAny().isEmpty()) {
                throw new IllegalArgumentException("至少要留一个不由你附身的人物");
            }
            scenes.setIdentity(sceneId, possessedId, possessedId == null ? followId : null);
        });
    }

    /** 结束这一场。closing 为真时先由叙事者收尾；收尾失败可以重试，也可以不要收尾直接结束。 */
    public void end(UUID sceneId, boolean closing, Events events) {
        withLock(sceneId, () -> {
            Scene scene = scenes.require(sceneId);
            if (!scene.open()) {
                throw new Conflict("这一场已经结束了");
            }
            if (closing) {
                scenes.setPending(sceneId, List.of(CLOSING), null);
                runPending(sceneId, events);
            } else {
                close(scene);
                events.emit("closed", Map.of());
            }
        });
    }

    public boolean running(UUID sceneId) {
        ReentrantLock lock = locks.get(sceneId);
        return lock != null && lock.isLocked();
    }

    // ---- 步骤 ----

    private void runPending(UUID sceneId, Events events) {
        while (true) {
            Scene scene = scenes.require(sceneId);
            if (scene.pending().isEmpty() || !scene.open()) {
                events.emit("done", Map.of());
                return;
            }
            String step = scene.pending().getFirst();
            List<String> rest = new ArrayList<>(scene.pending().subList(1, scene.pending().size()));
            Ctx ctx = context(scene);
            try {
                switch (step) {
                    case OPENING -> opening(scene, ctx, events);
                    case NARRATOR -> rest.addAll(0, narrator(scene, ctx, events));
                    case CLOSING -> {
                        closing(scene, ctx, events);
                        return;
                    }
                    default -> character(scene, ctx, UUID.fromString(step), events);
                }
            } catch (CallFailure e) {
                scenes.setPending(sceneId, scene.pending(), e.getMessage());
                Map<String, Object> data = new HashMap<>();
                data.put("message", e.getMessage());
                data.put("runId", e.runId());
                events.emit("failed", data);
                return;
            }
            scenes.setPending(sceneId, rest, null);
        }
    }

    private void opening(Scene scene, Ctx ctx, Events events) {
        events.emit("status", status(OPENING, null));
        var result = gateway.run(Gateway.Spec.of(Purpose.OPENING, "第" + scene.number() + "场 · 叙事者开场",
                links(scene, null), Prompts.narratorOpening(stage(scene, ctx)), Map.of()), Markup::parseNarration);
        long id = witness.line(scene, ctx.cast, ctx.names, 0, "narration", null, result.value(), false, result.runId());
        events.emit("saved", Map.of("lines", List.of(lineOf(scene.id(), id))));
    }

    /** 叙事者这一轮的决定；返回接下来要开口的人物（步骤）。 */
    private List<String> narrator(Scene scene, Ctx ctx, Events events) {
        events.emit("status", status(NARRATOR, null));
        List<Line> lines = scenes.lines(scene.id());
        List<Line> before = lines.stream().filter(l -> !(l.round() == scene.round() && l.byUser())).toList();
        Line latest = lines.stream().filter(l -> l.round() == scene.round() && l.byUser()).reduce((a, b) -> b).orElse(null);
        String possessed = scene.possessedId() == null ? null : ctx.names.get(scene.possessedId());
        String latestText = latest == null ? Prompts.latest(possessed, "continue", null)
                : Prompts.latest(possessed, latest.kind(), latest.text());
        List<Character> ai = ctx.ai(scene);
        List<String> candidates = ai.stream().map(Character::name).toList();
        boolean advance = scene.possessedId() == null;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("candidates", candidates);
        meta.put("advance", advance);
        var result = gateway.run(Gateway.Spec.of(Purpose.NARRATOR, "第" + scene.number() + "场 · 叙事者（第" + scene.round() + "轮）",
                        links(scene, null), Prompts.narratorRound(stage(scene, ctx), Perceive.scene(before, ctx.names, null),
                                latestText, candidates, advance), meta),
                text -> candidates.size() == 1 ? Markup.parsePairRound(text)
                        : Markup.parseGroupRound(text, candidates, advance, possessed));
        Round round = result.value();
        List<Long> saved = new ArrayList<>();
        if (round.narration() != null) {
            saved.add(witness.line(scene, ctx.cast, ctx.names, scene.round(), "narration", null, round.narration(),
                    false, result.runId()));
            events.emit("saved", Map.of("lines", saved.stream().map(id -> lineOf(scene.id(), id)).toList()));
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("叙事", round.narration());
        List<String> next;
        if (candidates.size() == 1) {
            next = List.of(ai.getFirst().id().toString());
        } else {
            next = round.reactors().stream()
                    .map(name -> ai.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow().id().toString())
                    .toList();
            outcome.put("轮到", round.reactors());
        }
        gateway.outcome(result.runId(), outcome);
        return next;
    }

    private void character(Scene scene, Ctx ctx, UUID characterId, Events events) {
        Character me = ctx.byId.get(characterId);
        if (me == null) {
            throw new NotFound("这个人物不在这一场里");
        }
        events.emit("status", status("character", characterId));
        List<Line> lines = scenes.lines(scene.id());
        List<Character> others = ctx.people.stream().filter(c -> !c.id().equals(characterId)).toList();
        String situation = lines.stream().skip(Math.max(0, lines.size() - 6))
                .map(l -> Perceive.line(l, ctx.names, characterId)).filter(s -> s != null)
                .reduce((a, b) -> a + "\n" + b).orElse(scene.setup());
        Voice.Here here = new Voice.Here("scene", scene.id(), 0);
        var persona = voice.persona(me, ctx.world, others, situation, here, scene.scale());
        List<Msg> messages = Prompts.characterInScene(persona, Perceive.scene(lines, ctx.names, characterId));
        var result = voice.speak(me, messages, true, links(scene, characterId),
                "第" + scene.number() + "场 · " + me.name() + "（第" + scene.round() + "轮）", here, new Voice.Sink() {
                    @Override
                    public void beat(Beat beat) {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("speakerId", characterId);
                        data.put("kind", beat.tag().equals("说") ? "say" : "do");
                        data.put("text", beat.text());
                        events.emit("draft", data);
                    }

                    @Override
                    public void discard() {
                        events.emit("discard", Map.of());
                    }
                });
        Turn turn = result.value();
        List<Long> ids = new ArrayList<>();
        for (Beat b : turn.beats()) {
            ids.add(witness.line(scene, ctx.cast, ctx.names, scene.round(), b.tag().equals("说") ? "say" : "do",
                    characterId, b.text(), false, result.runId()));
        }
        if (turn.silent()) {
            ids.add(witness.line(scene, ctx.cast, ctx.names, scene.round(), "wait", characterId, "", false, result.runId()));
        }
        ids.add(witness.line(scene, ctx.cast, ctx.names, scene.round(), "inner", characterId, turn.inner(), false,
                result.runId()));
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("言行", turn.beats().stream().map(b -> b.tag() + "：" + b.text()).toList());
        outcome.put("心声", turn.inner());
        gateway.outcome(result.runId(), outcome);
        events.emit("saved", Map.of("lines", ids.stream().map(id -> lineOf(scene.id(), id)).toList()));
    }

    private void closing(Scene scene, Ctx ctx, Events events) {
        events.emit("status", status(CLOSING, null));
        var result = gateway.run(Gateway.Spec.of(Purpose.CLOSING, "第" + scene.number() + "场 · 叙事者收尾",
                links(scene, null), Prompts.narratorClosing(stage(scene, ctx),
                        Perceive.scene(scenes.lines(scene.id()), ctx.names, null)), Map.of()), Markup::parseNarration);
        long id = witness.line(scene, ctx.cast, ctx.names, scene.round() + 1, "closing", null, result.value(), false,
                result.runId());
        events.emit("saved", Map.of("lines", List.of(lineOf(scene.id(), id))));
        close(scene);
        events.emit("closed", Map.of());
        events.emit("done", Map.of());
    }

    /** 结束：场上发生的事按各人所见成为经历，每个在场人物整理一次内心。 */
    private void close(Scene scene) {
        scenes.close(scene.id());
        List<Line> lines = scenes.lines(scene.id());
        long last = lines.isEmpty() ? 0 : lines.getLast().id();
        for (UUID id : scenes.cast(scene.id())) {
            consolidator.enqueue(id, "scene", scene.id(), 0, last);
        }
    }

    // ---- 同意检查 ----

    private boolean consentRefused(Ctx ctx, String action, Events events) {
        Scene scene = ctx.scene;
        List<Character> ai = ctx.ai(scene);
        events.emit("status", status("consent", null));
        String perception = perceiveAllInner(scenes.lines(scene.id()), ctx.names);
        String actor = ctx.names.get(scene.possessedId());
        var result = gateway.run(Gateway.Spec.of(Purpose.CONSENT, "第" + scene.number() + "场 · 同意检查",
                links(scene, null), Prompts.consent(perception, actor, action, ai.stream().map(Character::name).toList()),
                Map.of()), Markup::parseConsentRefused);
        gateway.outcome(result.runId(), Map.of("判定", result.value() ? "拒绝" : "可以"));
        if (result.value()) {
            events.emit("notice", Map.of("message", "对方此前拒绝过你的亲近，这个动作没有发生。可以换一个。"));
            events.emit("done", Map.of());
        }
        return result.value();
    }

    /** 同意检查要看到在场 AI 人物的心声（是否心里不愿意）。 */
    private static String perceiveAllInner(List<Line> lines, Map<UUID, String> names) {
        List<String> out = new ArrayList<>();
        for (Line l : lines) {
            String text = l.kind().equals("inner") ? names.get(l.speakerId()) + "（心声）：" + l.text()
                    : Perceive.line(l, names, null);
            if (text != null) {
                out.add(text);
            }
        }
        return out.isEmpty() ? Perceive.EMPTY : String.join("\n", out);
    }

    // ---- 小工具 ----

    /** 一场戏里要用的东西：世界、在场的人、名字。 */
    private final class Ctx {
        final Scene scene;
        final World world;
        final List<UUID> cast;
        final List<Character> people;
        final Map<UUID, Character> byId = new LinkedHashMap<>();
        final Map<UUID, String> names;

        Ctx(Scene scene) {
            this.scene = scene;
            this.world = worlds.requireWorld(scene.worldId());
            this.cast = scenes.cast(scene.id());
            this.people = worlds.characters(cast);
            people.forEach(c -> byId.put(c.id(), c));
            this.names = voice.names(scene.worldId());
        }

        /** 由 AI 驱动的在场人物。 */
        List<Character> ai(Scene current) {
            return people.stream().filter(c -> !c.id().equals(current.possessedId())).toList();
        }
    }

    private Ctx context(Scene scene) {
        return new Ctx(scene);
    }

    private Stage stage(Scene scene, Ctx ctx) {
        String previous = scenes.previous(scene.worldId(), scene.number()).map(p -> {
            List<Line> lines = scenes.lines(p.id());
            List<Line> tail = lines.subList(Math.max(0, lines.size() - PREVIOUS_LINES), lines.size());
            return Perceive.scene(tail, ctx.names, null);
        }).orElse(null);
        return new Stage(ctx.world.publicText(), ctx.world.narratorText(),
                ctx.people.stream().map(c -> Prompts.profileWithName(c.name(), c.profile())).toList(),
                scene.possessedId() == null ? null : ctx.names.get(scene.possessedId()),
                scene.followId() == null ? null : ctx.names.get(scene.followId()),
                scene.tone(), scene.scale(), scene.setup(), previous);
    }

    private static Links links(Scene scene, UUID characterId) {
        return new Links(characterId, scene.id(), null, null);
    }

    private static Map<String, Object> status(String step, UUID speakerId) {
        Map<String, Object> data = new HashMap<>();
        data.put("step", step);
        data.put("speakerId", speakerId);
        return data;
    }

    private Line lineOf(UUID sceneId, long id) {
        return scenes.lines(sceneId).stream().filter(l -> l.id() == id).findFirst().orElseThrow();
    }

    private static void requireIdle(Scene scene) {
        if (!scene.open()) {
            throw new Conflict("这一场已经结束了");
        }
        if (!scene.pending().isEmpty()) {
            throw new Conflict(scene.failure() != null ? "上一步失败了，先重试" : "这一场还有没走完的步骤");
        }
    }

    private void withLock(UUID sceneId, Runnable body) {
        ReentrantLock lock = locks.computeIfAbsent(sceneId, id -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new Conflict("这一场正在进行，稍等");
        }
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }
}
