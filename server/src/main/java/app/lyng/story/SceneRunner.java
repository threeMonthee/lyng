package app.lyng.story;

import app.lyng.ApiError;
import app.lyng.model.Attempt;
import app.lyng.model.CallFailure;
import app.lyng.model.ChatModel.Purpose;
import app.lyng.model.Gateway;
import app.lyng.model.Role;
import app.lyng.store.Store;
import app.lyng.store.Store.Character;
import app.lyng.store.Store.Line;
import app.lyng.store.Store.Pending;
import app.lyng.store.Store.Scene;
import app.lyng.store.Store.World;
import app.lyng.story.Markup.Beat;
import app.lyng.story.Markup.Round;
import app.lyng.story.Markup.Turn;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * 一场戏（tech-design §5）。要做的事都是“步骤”：opening、narrator、某个人物的 id、closing。
 * 场景上记着还剩哪几步；每一步成功就落库并推进，失败就停在这一步、记下原因，重试从这一步接着做。
 *
 * <p>每一轮：用户动作 → 叙事者（叙事；群戏再加“轮到”）→ 被点到的人物依次开口。双人戏不做调度。
 */
@Service
public class SceneRunner {

    public static final Set<String> TONES = Set.of(Prompts.TONE_DAILY, Prompts.TONE_ADVENTURE);
    private static final Set<String> POSSESSED_ACTIONS = Set.of("说", "做", "静候");
    private static final String ADVANCE = "继续";

    /** 一场戏里正在发生的事，推给页面。 */
    public interface Listener {
        void line(Line line);

        /** 人物正在写的一段（暂定）。 */
        void draft(String speakerId, Beat beat);

        /** 收回这个人物的暂定段：这次生成作废了。 */
        void reset(String speakerId);

        /** 谁在写；speakerId 为空表示叙事者。 */
        void typing(String speakerId);

        void done();

        void failed(String error);
    }

    private final Store store;
    private final Gateway gateway;
    private final Contexts contexts;
    private final Recall recall;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SceneRunner(Store store, Gateway gateway, Contexts contexts, Recall recall) {
        this.store = store;
        this.gateway = gateway;
        this.contexts = contexts;
        this.recall = recall;
    }

    // ---- 建一场戏、切换身份 ----

    public Scene create(String worldId, List<String> cast, String possessedId, String followId, String tone, String setup) {
        World world = store.world(worldId);
        if (cast == null || cast.isEmpty() || cast.stream().distinct().count() != cast.size()) {
            throw ApiError.badRequest("至少选一个在场人物，且不重复");
        }
        for (String id : cast) {
            if (!store.character(id).worldId().equals(world.id())) {
                throw ApiError.badRequest("在场人物要来自这个世界（穿越还没有做）");
            }
        }
        if (!TONES.contains(tone)) {
            throw ApiError.badRequest("基调只能是" + String.join("或", TONES));
        }
        if (setup == null || setup.isBlank()) {
            throw ApiError.badRequest("写一句开场处境");
        }
        String follow = checkIdentity(cast, possessedId, followId);
        return store.insertScene(world.id(), cast, possessedId, follow, tone, Prompts.SCALE_MILD, setup.strip(),
                new Pending(0, null, List.of("opening")));
    }

    /** 附身在场的另一个人物，或转为旁观（跟随 followId）。不写任何行，人物不知道。 */
    public Scene setIdentity(String sceneId, String possessedId, String followId) {
        return locked(sceneId, () -> {
            Scene scene = store.scene(sceneId);
            if (scene.ended()) {
                throw ApiError.conflict("这一场已经结束了");
            }
            if (scene.pending() != null) {
                throw ApiError.conflict("这一步还没有完成，先重试或结束这一场");
            }
            store.setIdentity(sceneId, possessedId, checkIdentity(scene.cast(), possessedId, followId));
            return store.scene(sceneId);
        });
    }

    /** 校验身份，返回旁观时跟随的人物（附身时为空）。 */
    private static String checkIdentity(List<String> cast, String possessedId, String followId) {
        if (possessedId != null) {
            if (!cast.contains(possessedId)) {
                throw ApiError.badRequest("只能附身在场的人物");
            }
            if (cast.size() < 2) {
                throw ApiError.badRequest("附身之后场上还要有一个由 AI 驱动的人物；只有一个人物时只能旁观");
            }
            return null;
        }
        if (followId == null) {
            return cast.getFirst();
        }
        if (!cast.contains(followId)) {
            throw ApiError.badRequest("旁观时只能跟随在场的人物");
        }
        return followId;
    }

    public boolean busy(String sceneId) {
        ReentrantLock lock = locks.get(sceneId);
        return lock != null && lock.isLocked();
    }

    // ---- 会跑模型的动作 ----

    /** 附身时说 / 做 / 静候；旁观时“继续”。 */
    public void act(String sceneId, String action, String text, Listener listener) {
        run(sceneId, listener, scene -> {
            requireIdle(scene);
            if (scene.possessedId() == null ? !action.equals(ADVANCE) : !POSSESSED_ACTIONS.contains(action)) {
                throw ApiError.badRequest(scene.possessedId() == null ? "旁观时只能继续" : "附身时只能说、做或静候");
            }
            String body = text == null ? "" : text.strip();
            if ((action.equals("说") || action.equals("做")) && body.isEmpty()) {
                throw ApiError.badRequest("写点什么再" + action);
            }
            int round = store.lines(scene.id()).stream().mapToInt(Line::round).max().orElse(0) + 1;
            if (!action.equals(ADVANCE)) {
                String kind = switch (action) {
                    case "说" -> "say";
                    case "做" -> "do";
                    default -> "wait";
                };
                emit(scene, List.of(Line.of(round, kind, scene.possessedId(), true, action.equals("静候") ? "" : body)), listener);
            }
            return new Pending(round, action, List.of("narrator"));
        });
    }

    /** 从剩下的第一步接着做：新场景的开场、失败之后的重试都是它。 */
    public void resume(String sceneId, Listener listener) {
        run(sceneId, listener, scene -> {
            if (scene.pending() == null) {
                throw ApiError.conflict("没有需要继续的步骤");
            }
            return scene.pending();
        });
    }

    /** 结束这一场。closing 为真时叙事者先收尾；为假时直接结束（未完成的步骤一并放弃）。 */
    public void end(String sceneId, boolean closing, Listener listener) {
        run(sceneId, listener, scene -> {
            if (scene.ended()) {
                throw ApiError.conflict("这一场已经结束了");
            }
            if (!closing) {
                store.end(scene.id());
                recall.startAsync(scene.id());
                return null;
            }
            boolean closingFailed = scene.pending() != null && scene.pending().steps().equals(List.of("closing"));
            if (scene.pending() != null && !closingFailed) {
                throw ApiError.conflict("这一步还没有完成，先重试，或者不要收尾直接结束");
            }
            return new Pending(0, null, List.of("closing"));
        });
    }

    private static void requireIdle(Scene scene) {
        if (scene.ended()) {
            throw ApiError.conflict("这一场已经结束了");
        }
        if (scene.pending() != null) {
            throw ApiError.conflict(scene.pending().steps().contains("opening") ? "还没有开场" : "上一步还没有完成，先重试");
        }
    }

    private void run(String sceneId, Listener listener, Function<Scene, Pending> start) {
        locked(sceneId, () -> {
            Pending pending = start.apply(store.scene(sceneId));
            if (pending == null) {
                listener.done();
            } else {
                store.setPending(sceneId, pending, null);
                advance(sceneId, pending, listener);
            }
            return null;
        });
    }

    private <T> T locked(String sceneId, java.util.function.Supplier<T> work) {
        ReentrantLock lock = locks.computeIfAbsent(sceneId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw ApiError.conflict("这一场正在进行，稍等");
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    private void advance(String sceneId, Pending pending, Listener listener) {
        while (!pending.steps().isEmpty()) {
            Scene scene = store.scene(sceneId);
            String step = pending.steps().getFirst();
            try {
                pending = switch (step) {
                    case "opening" -> opening(scene, pending, listener);
                    case "narrator" -> narrator(scene, pending, listener);
                    case "closing" -> closing(scene, pending, listener);
                    default -> character(scene, store.character(step), pending, listener);
                };
            } catch (CallFailure f) {
                store.setPending(sceneId, pending, f.getMessage());
                listener.failed(f.getMessage());
                return;
            }
            if (!scene.ended()) {
                store.setPending(sceneId, pending.steps().isEmpty() ? null : pending, null);
            }
        }
        listener.done();
    }

    // ---- 步骤 ----

    private Pending opening(Scene scene, Pending pending, Listener listener) {
        listener.typing(null);
        recall.ensure(scene.cast());
        String text = gateway.call(Role.NARRATOR, new Purpose("opening", null, scene.id()),
                Prompts.narratorOpening(contexts.stage(scene, store.world(scene.worldId()))),
                () -> Attempt.whole(Markup::parseNarration));
        emit(scene, List.of(Line.of(0, "narration", null, false, text)), listener);
        return pending.next();
    }

    private Pending narrator(Scene scene, Pending pending, Listener listener) {
        List<Character> cast = contexts.cast(scene);
        Map<String, String> names = Contexts.names(cast);
        List<Line> lines = store.lines(scene.id());
        String possessed = names.get(scene.possessedId());
        String userText = lines.stream().filter(l -> l.round() == pending.round() && l.byUser())
                .map(Line::text).findFirst().orElse("");
        List<String> ai = scene.ai();
        List<String> candidates = ai.stream().map(names::get).toList();
        boolean pair = ai.size() == 1;
        boolean advance = pending.action().equals(ADVANCE);

        listener.typing(null);
        Round round = gateway.call(Role.NARRATOR_ROUND,
                new Purpose("round", pending.action(), scene.id(), pair ? List.of() : candidates),
                Prompts.narratorRound(contexts.stage(scene, store.world(scene.worldId())),
                        Prompts.perceive(Contexts.before(lines, pending.round()), names, null),
                        Prompts.latest(possessed, pending.action(), userText), candidates, advance),
                () -> Attempt.whole(text -> pair ? Markup.parsePairRound(text)
                        : Markup.parseGroupRound(text, candidates, advance, possessed)));
        if (round.narration() != null) {
            emit(scene, List.of(Line.of(pending.round(), "narration", null, false, round.narration())), listener);
        }
        List<String> reactors = pair ? ai : round.reactors().stream()
                .map(name -> cast.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow().id())
                .toList();
        return new Pending(pending.round(), pending.action(), reactors);
    }

    private Pending character(Scene scene, Character who, Pending pending, Listener listener) {
        if (who.id().equals(scene.possessedId())) {
            return pending.next(); // 被附身的人物从不由模型驱动
        }
        Map<String, String> names = Contexts.names(contexts.cast(scene));
        listener.typing(who.id());
        Turn turn = gateway.call(Role.CHARACTER, new Purpose("character", who.name(), scene.id()),
                Prompts.character(contexts.persona(scene, store.world(scene.worldId()), who),
                        Prompts.perceive(store.lines(scene.id()), names, who.id())),
                () -> new Streaming(who.id(), who.name(), listener));
        List<Line> lines = new ArrayList<>();
        if (turn.silent()) {
            lines.add(Line.of(pending.round(), "wait", who.id(), false, ""));
        }
        turn.beats().forEach(b -> lines.add(Line.of(pending.round(), b.tag().equals("说") ? "say" : "do", who.id(), false, b.text())));
        lines.add(Line.of(pending.round(), "inner", who.id(), false, turn.inner()));
        emit(scene, lines, listener);
        return pending.next();
    }

    private Pending closing(Scene scene, Pending pending, Listener listener) {
        List<Line> lines = store.lines(scene.id());
        listener.typing(null);
        String text = gateway.call(Role.NARRATOR, new Purpose("closing", null, scene.id()),
                Prompts.narratorClosing(contexts.stage(scene, store.world(scene.worldId())),
                        Prompts.perceive(lines, Contexts.names(contexts.cast(scene)), null)),
                () -> Attempt.whole(Markup::parseNarration));
        int round = lines.stream().mapToInt(Line::round).max().orElse(0) + 1;
        emit(scene, List.of(Line.of(round, "narration", null, false, text)), listener);
        store.end(scene.id());
        recall.startAsync(scene.id());
        return pending.next();
    }

    private void emit(Scene scene, List<Line> lines, Listener listener) {
        store.append(scene.id(), lines).forEach(listener::line);
    }

    /** 人物的一次生成：一段写完推一段；这次生成作废就收回已推的段。 */
    private static final class Streaming implements Attempt<Turn> {
        private final String speakerId;
        private final String name;
        private final Listener listener;
        private final Markup.TurnStream stream;
        private boolean pushed;

        Streaming(String speakerId, String name, Listener listener) {
            this.speakerId = speakerId;
            this.name = name;
            this.listener = listener;
            this.stream = new Markup.TurnStream(name, beat -> {
                pushed = true;
                listener.draft(speakerId, beat);
            });
        }

        @Override
        public void onDelta(String delta) {
            stream.feed(delta);
        }

        @Override
        public Turn finish(String text) {
            return Markup.parseTurn(text, name);
        }

        @Override
        public void abandon() {
            if (pushed) {
                listener.reset(speakerId);
            }
        }
    }
}
