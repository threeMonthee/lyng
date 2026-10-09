package app.lyng.scene;

import app.lyng.event.EventKind;
import app.lyng.event.SceneEvent;
import app.lyng.markup.Beat;
import app.lyng.markup.Markup;
import app.lyng.markup.Turn;
import app.lyng.markup.TurnStreamParser;
import app.lyng.model.Attempt;
import app.lyng.model.CallFailure;
import app.lyng.model.CallPurpose;
import app.lyng.model.ModelGateway;
import app.lyng.model.ModelRole;
import app.lyng.prompt.CharacterContext;
import app.lyng.prompt.NarratorContext;
import app.lyng.prompt.Prompts;
import app.lyng.web.ConflictException;
import app.lyng.world.Character;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 对一场戏做一件事（开场、一轮、重试、收尾）的调用链。调用方持有场锁，同一时刻只有一个 SceneRun 在写这场戏。
 *
 * <p>一轮的顺序（tech-design §5.2）：
 * <ul>
 *   <li>说 / 静候：人物立刻开口（流式），叙事者在幕后并发地决定要不要出声，出声的话排在人物之后；
 *   <li>做：叙事者先裁定结果，人物再反应。
 * </ul>
 * 双人戏不做调度：每一轮 AI 人物都接戏，接不接由 TA 决定。
 */
final class SceneRun {

    private static final String STEP_OPENING = "opening";
    private static final String STEP_NARRATOR = "narrator";
    private static final String STEP_CHARACTER = "character";
    private static final String STEP_CLOSING = "closing";

    private final UUID userId;
    private final Scene scene;
    private final SceneCast cast;
    private final ModelGateway gateway;
    private final SceneRepository scenes;
    private final ContextAssembler contexts;
    private final RecallService recalls;
    private final RoundListener listener;
    private final List<SceneEvent> events;
    private NarratorContext narratorContext;

    SceneRun(UUID userId, Scene scene, SceneCast cast, ModelGateway gateway, SceneRepository scenes,
             ContextAssembler contexts, RecallService recalls, RoundListener listener) {
        this.userId = userId;
        this.scene = scene;
        this.cast = cast;
        this.gateway = gateway;
        this.scenes = scenes;
        this.contexts = contexts;
        this.recalls = recalls;
        this.listener = listener;
        this.events = new ArrayList<>(scenes.events(userId, scene.id()));
    }

    // ---- 开场 ----

    void checkOpen() {
        if (SceneStatus.opened(events) || !SceneStatus.unresolvedFailures(events).isEmpty()) {
            throw new ConflictException("这一场已经开场了");
        }
    }

    void open() {
        recalls.ensureFor(userId, cast.present().stream().map(Character::id).toList());
        openingStep();
    }

    private void openingStep() {
        try {
            String text = gateway.call(userId, ModelRole.NARRATOR, purpose("opening", null),
                    Prompts.narratorOpening(narrator()), () -> Attempt.whole(Markup::parseNarration));
            append(SceneEvent.narration(0, text));
        } catch (CallFailure f) {
            append(SceneEvent.failure(0, STEP_OPENING, null, null, f.getMessage()));
        }
    }

    // ---- 一轮 ----

    void checkAct() {
        if (!SceneStatus.opened(events)) {
            throw new ConflictException("这一场还没有开场");
        }
        if (!SceneStatus.unresolvedFailures(events).isEmpty() || SceneStatus.dangling(events)) {
            throw new ConflictException("上一轮还有没完成的步骤，请先重试");
        }
    }

    void act(String action, String text) {
        int round = SceneStatus.lastRound(events) + 1;
        List<SceneEvent> before = List.copyOf(events);
        SceneEvent userEvent = append(SceneEvent.user(round, cast.possessed().id(), cast.possessed().name(), action, text));
        if (action.equals(SceneEvent.DO)) {
            if (settle(round, decide(before, userEvent))) {
                playAll(round);
            }
            return;
        }
        NarratorContext ctx = narrator();
        CompletableFuture<Decision> narration = CompletableFuture.supplyAsync(() -> decide(ctx, before, userEvent),
                task -> Thread.startVirtualThread(task));
        playAll(round);
        settle(round, narration.join());
    }

    private void playAll(int round) {
        for (Character who : cast.ai()) {
            play(who, round, List.copyOf(events));
        }
    }

    private boolean play(Character who, int round, List<SceneEvent> visible) {
        try {
            Turn turn = characterTurn(who, visible);
            append(SceneEvent.character(round, who.id(), who.name(), turn.beats(), turn.inner(), turn.silent()));
            return true;
        } catch (CallFailure f) {
            append(SceneEvent.failure(round, STEP_CHARACTER, who.id(), who.name(), f.getMessage()));
            return false;
        }
    }

    private Turn characterTurn(Character who, List<SceneEvent> visible) {
        CharacterContext ctx = contexts.forCharacter(userId, scene, cast.world(), who, cast.present());
        return gateway.call(userId, ModelRole.CHARACTER, purpose("character", who.name()),
                Prompts.character(ctx, visible), () -> new StreamingTurn(who.name()));
    }

    /** 人物的一次生成：气泡写完一个推一个；这次生成失败就收回已推的气泡。 */
    private final class StreamingTurn implements Attempt<Turn> {
        private final String name;
        private final TurnStreamParser parser;
        private boolean pushed;

        StreamingTurn(String name) {
            this.name = name;
            this.parser = new TurnStreamParser(name, beat -> {
                pushed = true;
                listener.bubble(name, beat);
            });
        }

        @Override
        public void onDelta(String delta) {
            parser.feed(delta);
        }

        @Override
        public Turn finish(String fullText) {
            return parser.finish(fullText);
        }

        @Override
        public void abandon() {
            if (pushed) {
                listener.retract(name);
            }
        }
    }

    /** 叙事者幕后决定这一轮要不要出声。narration 为空表示不出声；failure 非空表示这一步技术失败。 */
    private record Decision(String narration, CallFailure failure) {}

    private Decision decide(List<SceneEvent> before, SceneEvent action) {
        return decide(narrator(), before, action);
    }

    private Decision decide(NarratorContext ctx, List<SceneEvent> before, SceneEvent action) {
        try {
            String narration = gateway.call(userId, ModelRole.NARRATOR_ROUND, purpose("round", action.action()),
                    Prompts.narratorRound(ctx, before, action), () -> Attempt.whole(Markup::parsePairRound));
            return new Decision(narration, null);
        } catch (CallFailure f) {
            return new Decision(null, f);
        }
    }

    /** 把叙事者的决定写进这场戏。返回这一步是否成功。 */
    private boolean settle(int round, Decision decision) {
        if (decision.failure() != null) {
            append(SceneEvent.failure(round, STEP_NARRATOR, null, null, decision.failure().getMessage()));
            return false;
        }
        if (!decision.narration().isEmpty()) {
            append(SceneEvent.narration(round, decision.narration()));
        }
        return true;
    }

    // ---- 重试：只重做失败的那一步 ----

    void checkRetry() {
        if (SceneStatus.unresolvedFailures(events).isEmpty() && !SceneStatus.dangling(events)) {
            throw new ConflictException("没有需要重试的步骤");
        }
    }

    void retry() {
        List<SceneEvent> failures = SceneStatus.unresolvedFailures(events);
        if (failures.isEmpty()) {
            playAll(SceneStatus.lastRound(events));
            return;
        }
        for (SceneEvent failure : failures) {
            redo(failure);
            append(SceneEvent.resolved(failure.round(), failure.seq()));
        }
    }

    private void redo(SceneEvent failure) {
        switch (failure.step()) {
            case STEP_OPENING -> openingStep();
            case STEP_CLOSING -> {
                if (closingStep(failure.round())) {
                    finish();
                }
            }
            case STEP_NARRATOR -> redoNarrator(failure);
            case STEP_CHARACTER -> redoCharacter(failure);
            default -> throw new IllegalStateException("未知步骤：" + failure.step());
        }
    }

    private void redoNarrator(SceneEvent failure) {
        SceneEvent action = events.stream().filter(e -> e.kind() == EventKind.USER && e.round() == failure.round())
                .findFirst().orElseThrow();
        List<SceneEvent> before = events.stream().filter(e -> e.seq() < action.seq()).toList();
        boolean ok = settle(failure.round(), decide(before, action));
        // 做：叙事者先出声，人物还没有反应过；说 / 静候：人物那一路是独立的
        if (ok && action.action().equals(SceneEvent.DO)) {
            playAll(failure.round());
        }
    }

    private void redoCharacter(SceneEvent failure) {
        Character who = cast.ai().stream().filter(c -> c.id().equals(failure.speakerId())).findFirst().orElseThrow();
        play(who, failure.round(), events.stream().filter(e -> e.seq() < failure.seq()).toList());
    }

    // ---- 收尾 ----

    /** 用户结束这场戏。closing 为 true 时叙事者先收尾；收尾失败则这一场仍然开着，可以重试。 */
    void checkEnd(boolean closing) {
        if (closing && SceneStatus.unresolvedFailures(events).stream().anyMatch(f -> !f.step().equals(STEP_CLOSING))) {
            throw new ConflictException("还有没解决的失败：先重试，或者选择直接结束");
        }
    }

    void end(boolean closing) {
        List<SceneEvent> failures = SceneStatus.unresolvedFailures(events);
        if (closing) {
            if (!closingStep(publicRound() + 1)) {
                return;
            }
            failures.forEach(f -> append(SceneEvent.resolved(f.round(), f.seq())));
        }
        finish();
    }

    private int publicRound() {
        return events.stream().filter(e -> e.kind().isPublic()).mapToInt(SceneEvent::round).max().orElse(0);
    }

    private boolean closingStep(int round) {
        try {
            String text = gateway.call(userId, ModelRole.NARRATOR, purpose("closing", null),
                    Prompts.narratorClosing(narrator(), List.copyOf(events)), () -> Attempt.whole(Markup::parseNarration));
            append(SceneEvent.narration(round, text));
            return true;
        } catch (CallFailure f) {
            append(SceneEvent.failure(round, STEP_CLOSING, null, null, f.getMessage()));
            return false;
        }
    }

    private void finish() {
        scenes.end(userId, scene.id());
        recalls.startAsync(userId, scene.id());
    }

    // ---- 共用 ----

    private NarratorContext narrator() {
        if (narratorContext == null) {
            narratorContext = contexts.forNarrator(userId, scene, cast.world(), cast.possessed(), cast.present());
        }
        return narratorContext;
    }

    private CallPurpose purpose(String kind, String subject) {
        return new CallPurpose(kind, subject, scene.id());
    }

    private SceneEvent append(SceneEvent event) {
        SceneEvent saved = scenes.append(userId, scene.id(), event);
        events.add(saved);
        listener.event(saved);
        return saved;
    }
}
