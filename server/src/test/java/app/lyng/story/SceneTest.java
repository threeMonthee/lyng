package app.lyng.story;

import static app.lyng.support.Fixtures.BI_YAO;
import static app.lyng.support.Fixtures.LU_XUEQI;
import static app.lyng.support.Fixtures.WORLD;
import static app.lyng.support.Fixtures.WORLD_NARRATOR;
import static app.lyng.support.Fixtures.ZHANG_XIAOFAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.ApiError;
import app.lyng.model.ModelException;
import app.lyng.store.Store.Character;
import app.lyng.store.Store.Line;
import app.lyng.store.Store.Scene;
import app.lyng.store.Store.World;
import app.lyng.support.IntegrationTest;
import app.lyng.support.Recorder;
import app.lyng.support.ScriptedModel;
import app.lyng.support.ScriptedModel.Reply;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 一场戏的行为（tech-design §5）：确定性替身 + 真实 SQLite + 完整应用。 */
class SceneTest extends IntegrationTest {

    @Autowired
    SceneRunner runner;

    World world;
    Character xf;
    Character lu;
    Character by;

    @BeforeEach
    void setUp() {
        world = store.insertWorld("诛仙", WORLD, WORLD_NARRATOR);
        xf = store.insertCharacter(world.id(), "张小凡", ZHANG_XIAOFAN);
        lu = store.insertCharacter(world.id(), "陆雪琪", LU_XUEQI);
        by = store.insertCharacter(world.id(), "碧瑶", BI_YAO);
    }

    private Scene open(Character possessed, Character... cast) {
        Scene scene = runner.create(world.id(), java.util.Arrays.stream(cast).map(Character::id).toList(),
                possessed == null ? null : possessed.id(), null, "日常相处", "山海苑后院，入夜，张小凡来找陆雪琪。");
        Recorder r = new Recorder();
        runner.resume(scene.id(), r);
        assertThat(r.events).last().isEqualTo("done");
        return store.scene(scene.id());
    }

    private Recorder act(Scene scene, String action, String text) {
        Recorder r = new Recorder();
        runner.act(scene.id(), action, text, r);
        return r;
    }

    private Recorder end(Scene scene) {
        Recorder r = new Recorder();
        runner.end(scene.id(), true, r);
        return r;
    }

    private List<Line> lines(Scene scene) {
        return store.lines(scene.id());
    }

    private static Predicate<ScriptedModel.Request> kind(String kind) {
        return r -> r.purpose().kind().equals(kind);
    }

    // ---- 开场 ----

    @Test
    void theNarratorOpensFromTheUsersSetupOnly() {
        Scene scene = open(xf, xf, lu);
        assertThat(model.lastPrompt("opening")).contains("山海苑后院，入夜，张小凡来找陆雪琪。")
                .contains("张小凡的言行只以给出的为准");
        assertThat(lines(scene)).singleElement().extracting(Line::kind).isEqualTo("narration");
        assertThat(scene.pending()).isNull();
    }

    @Test
    void theCharacterNeverSeesTheSetupOrTheNarratorsSecrets() {
        Scene scene = open(xf, xf, lu);
        act(scene, "说", "雪琪。");
        String prompt = model.lastPrompt("character");
        assertThat(prompt).doesNotContain("山海苑后院，入夜，张小凡来找陆雪琪。").doesNotContain("一个月前，碧瑶醒了");
    }

    // ---- 一轮：叙事者先行 ----

    @Test
    void afterSpeakingTheNarratorDecidesFirstAndTheCharacterSeesIt() {
        Scene scene = open(xf, xf, lu);
        model.script(r -> kind("round").test(r) ? Reply.text("叙事：院外传来更鼓声。") : null);
        Recorder r = act(scene, "说", "那天夜里的事，不是我。");

        assertThat(model.kinds()).containsSubsequence("round:说", "character:陆雪琪");
        assertThat(model.lastPrompt("round")).contains("【刚刚】\n张小凡（说）：那天夜里的事，不是我。");
        assertThat(model.lastPrompt("character")).contains("张小凡（说）：那天夜里的事，不是我。\n叙事：院外传来更鼓声。");
        assertThat(lines(scene)).extracting(Line::kind).containsExactly("narration", "say", "narration", "do", "say", "inner");
        assertThat(lines(scene).get(1).byUser()).isTrue();
        assertThat(r.events).last().isEqualTo("done");
    }

    @Test
    void doingIsWrittenVerbatimAndTheNarratorRulesBeforeTheCharacterReacts() {
        Scene scene = open(xf, xf, lu);
        model.script(r -> kind("round").test(r) ? Reply.text("叙事：墙那边是一条空巷，人影已经不见了。") : null);
        act(scene, "做", "翻过院墙去追那个人影。");

        List<Line> lines = lines(scene);
        assertThat(lines.get(1)).extracting(Line::kind, Line::text).containsExactly("do", "翻过院墙去追那个人影。");
        assertThat(lines.get(2).text()).isEqualTo("墙那边是一条空巷，人影已经不见了。");
        assertThat(model.lastPrompt("character")).contains("叙事：墙那边是一条空巷，人影已经不见了。");
    }

    @Test
    void whenNothingNeedsSayingTheNarratorStaysSilent() {
        Scene scene = open(xf, xf, lu);
        model.script(r -> kind("round").test(r) ? Reply.text("叙事：无。") : null);
        act(scene, "做", "把茶递给她。");
        assertThat(lines(scene)).extracting(Line::kind).containsExactly("narration", "do", "do", "say", "inner");
    }

    @Test
    void waitingIsRecordedAsNotSpeaking() {
        Scene scene = open(xf, xf, lu);
        act(scene, "静候", "");
        assertThat(lines(scene).get(1)).extracting(Line::kind, Line::byUser).containsExactly("wait", true);
        assertThat(model.lastPrompt("character")).contains("张小凡没有开口。");
    }

    @Test
    void theCharacterMayChooseToSayNothing() {
        Scene scene = open(xf, xf, lu);
        model.script(r -> kind("character").test(r) ? Reply.text("陆雪琪（心声）：随他去。") : null);
        act(scene, "说", "雪琪。");
        assertThat(lines(scene).subList(2, 4)).extracting(Line::kind, Line::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("wait", ""), org.assertj.core.groups.Tuple.tuple("inner", "随他去。"));
    }

    @Test
    void thePossessedCharacterIsNeverRunByTheModel() {
        Scene scene = open(xf, xf, lu);
        act(scene, "说", "雪琪。");
        act(scene, "静候", "");
        assertThat(model.requests("character")).allMatch(r -> r.purpose().subject().equals("陆雪琪"));
    }

    @Test
    void eachCharacterSeesOnlyTheirOwnInnerVoiceAndTheNarratorSeesNone() {
        Scene scene = open(xf, xf, lu, by);
        model.script(r -> {
            if (kind("round").test(r)) {
                return Reply.text("叙事：无\n轮到：陆雪琪、碧瑶");
            }
            if (r.purpose().subject() != null && r.purpose().subject().equals("陆雪琪")) {
                return Reply.text("陆雪琪（行）：陆雪琪停住了脚步。\n陆雪琪（心声）：陆雪琪的秘密");
            }
            return null;
        });
        act(scene, "说", "雪琪。");
        String biyao = model.lastPrompt("character");
        assertThat(biyao).contains("陆雪琪（行）：陆雪琪停住了脚步。").doesNotContain("陆雪琪的秘密");
        act(scene, "静候", "");
        assertThat(model.lastPrompt("round")).doesNotContain("心声");
        String luSecond = ScriptedModel.prompt(model.requests("character").get(2));
        assertThat(luSecond).contains("陆雪琪（心声）：陆雪琪的秘密");
    }

    // ---- 群戏与旁观 ----

    @Test
    void inAGroupSceneTheNarratorDecidesWhoReactsAndInWhatOrder() {
        Scene scene = open(xf, xf, lu, by);
        model.script(r -> kind("round").test(r) ? Reply.text("叙事：无\n轮到：碧瑶") : null);
        act(scene, "说", "碧瑶，你先回去。");
        assertThat(model.requests("character")).extracting(r -> r.purpose().subject()).containsExactly("碧瑶");
        assertThat(model.lastPrompt("round")).contains("可选的人物：陆雪琪、碧瑶。").contains("张小凡是被附身的人物");

        model.script(r -> kind("round").test(r) ? Reply.text("叙事：无\n轮到：无") : null);
        act(scene, "静候", "");
        assertThat(model.requests("character")).hasSize(1);
    }

    @Test
    void whenTheNarratorNamesThePossessedCharacterItIsAFormatFailureAndRetried() {
        Scene scene = open(xf, xf, lu, by);
        int[] n = {0};
        model.script(r -> kind("round").test(r) ? Reply.text(n[0]++ == 0 ? "叙事：无\n轮到：张小凡" : "叙事：无\n轮到：陆雪琪") : null);
        act(scene, "静候", "");
        assertThat(model.requests("round")).hasSize(2);
        assertThat(model.requests("character")).extracting(r -> r.purpose().subject()).containsExactly("陆雪琪");
    }

    @Test
    void observingMovesTheSceneForwardOnContinue() {
        Scene scene = open(null, lu, by);
        assertThat(model.lastPrompt("opening")).contains("【镜头】\n这一场跟随陆雪琪。");
        assertThatThrownBy(() -> act(scene, "说", "x")).isInstanceOf(ApiError.class);

        model.script(r -> kind("round").test(r) ? Reply.text("叙事：桥下的水声大了。\n轮到：碧瑶、陆雪琪") : null);
        act(scene, "继续", null);
        assertThat(model.lastPrompt("round")).contains("【刚刚】\n没有新的动作。").contains("不能都是“无”");
        assertThat(lines(scene)).noneMatch(Line::byUser);
        assertThat(model.requests("character")).extracting(r -> r.purpose().subject()).containsExactly("碧瑶", "陆雪琪");
    }

    @Test
    void observingASingleCharacterLetsTheCharacterReactEveryTime() {
        Scene scene = open(null, lu);
        act(scene, "继续", null);
        assertThat(model.requests("character")).hasSize(1);
        assertThatThrownBy(() -> runner.setIdentity(scene.id(), lu.id(), null))
                .isInstanceOf(ApiError.class).hasMessageContaining("只能旁观");
    }

    @Test
    void switchingIdentityChangesWhoTheModelDrivesWithoutWritingAnything() {
        Scene scene = open(xf, xf, lu);
        act(scene, "说", "雪琪。");
        int before = lines(scene).size();

        runner.setIdentity(scene.id(), lu.id(), null);
        assertThat(lines(scene)).hasSize(before);
        act(scene, "说", "你走吧。");
        assertThat(model.requests("character").getLast().purpose().subject()).isEqualTo("张小凡");
        assertThat(model.lastPrompt("round")).contains("【刚刚】\n陆雪琪（说）：你走吧。").contains("陆雪琪的言行只以给出的为准");
        String xfPrompt = model.lastPrompt("character");
        assertThat(xfPrompt).contains("张小凡（说）：雪琪。").doesNotContain("附身");

        runner.setIdentity(scene.id(), null, xf.id());
        assertThat(store.scene(scene.id())).extracting(Scene::possessedId, Scene::followId).containsExactly(null, xf.id());
        act(scene, "继续", null);
        assertThat(model.lastPrompt("round")).contains("这一场跟随张小凡。");
    }

    // ---- 失败与重试 ----

    @Test
    void aFailedCharacterTurnIsATechnicalFailureNotSilenceAndIsRetriedOnce() {
        Scene scene = open(xf, xf, lu);
        model.script(r -> kind("character").test(r) ? Reply.truncated("陆雪琪（说）：我") : null);
        Recorder r = act(scene, "说", "雪琪。");

        assertThat(model.requests("character")).hasSize(2);
        assertThat(r.failed()).isTrue();
        Scene after = store.scene(scene.id());
        assertThat(after.pending().steps()).containsExactly(lu.id());
        assertThat(after.error()).contains("截断").contains("已自动重试一次");
        assertThat(lines(scene)).extracting(Line::kind).containsExactly("narration", "say");
        assertThatThrownBy(() -> act(scene, "说", "再说一句")).isInstanceOf(ApiError.class).hasMessageContaining("先重试");
        assertThatThrownBy(() -> runner.setIdentity(scene.id(), lu.id(), null)).isInstanceOf(ApiError.class);
    }

    @Test
    void retryRedoesOnlyTheFailedStepWithTheSameContext() {
        Scene scene = open(xf, xf, lu);
        model.script(r -> kind("character").test(r) ? Reply.text("") : null);
        act(scene, "说", "雪琪。");
        String firstPrompt = model.lastPrompt("character");
        int rounds = model.requests("round").size();

        model.script(r -> null);
        Recorder r = new Recorder();
        runner.resume(scene.id(), r);
        assertThat(r.events).last().isEqualTo("done");
        assertThat(model.requests("round")).hasSize(rounds);
        assertThat(model.lastPrompt("character")).isEqualTo(firstPrompt);
        assertThat(store.scene(scene.id()).pending()).isNull();
        assertThat(lines(scene)).extracting(Line::kind).containsExactly("narration", "say", "do", "say", "inner");
    }

    @Test
    void ifTheNarratorFailsBeforeTheCharacterTheCharacterWaitsForTheRetry() {
        Scene scene = open(xf, xf, lu);
        model.script(r -> kind("round").test(r) ? Reply.text("这一轮我不出声。") : null);
        act(scene, "做", "翻墙。");
        assertThat(model.requests("character")).isEmpty();
        assertThat(store.scene(scene.id()).pending().steps()).containsExactly("narrator");

        model.script(r -> null);
        runner.resume(scene.id(), new Recorder());
        assertThat(model.requests("character")).hasSize(1);
    }

    @Test
    void anInvalidTurnAfterDraftsWereShownIsResetAndRetried() {
        Scene scene = open(xf, xf, lu);
        int[] n = {0};
        model.script(r -> kind("character").test(r) && n[0]++ == 0
                ? Reply.text("陆雪琪（说）：你来了。\n陆雪琪（行）：她转身。") : null);
        Recorder r = act(scene, "说", "雪琪。");
        assertThat(r.events).containsSubsequence("draft:" + lu.id() + ":你来了。", "reset:" + lu.id(), "done");
        assertThat(r.failed()).isFalse();
    }

    @Test
    void anotherPersonsLineIsCaughtWhileStreamingAndNeverShown() {
        Scene scene = open(xf, xf, lu);
        int[] n = {0};
        model.script(r -> kind("character").test(r) && n[0]++ == 0
                ? Reply.text("陆雪琪（说）：你说。\n张小凡（说）：我偏不。\n陆雪琪（心声）：嗯。") : null);
        Recorder r = act(scene, "说", "雪琪。");
        assertThat(r.events).noneMatch(e -> e.contains("我偏不"));
        assertThat(lines(scene)).noneMatch(l -> l.text().contains("我偏不"));
    }

    @Test
    void theOpeningCanFailAndBeRetried() {
        model.script(r -> kind("opening").test(r) ? Reply.fail(ModelException.Kind.TRANSIENT) : null);
        Scene scene = runner.create(world.id(), List.of(xf.id(), lu.id()), xf.id(), null, "冒险", "渡口。");
        Recorder r = new Recorder();
        runner.resume(scene.id(), r);
        assertThat(r.failed()).isTrue();
        assertThat(model.requests("opening")).hasSize(2);
        assertThatThrownBy(() -> act(scene, "说", "x")).isInstanceOf(ApiError.class).hasMessageContaining("还没有开场");

        model.script(req -> null);
        runner.resume(scene.id(), new Recorder());
        assertThat(lines(scene)).hasSize(1);
    }

    @Test
    void aProviderRefusalOrAFatalErrorIsNotRetried() {
        Scene scene = open(xf, xf, lu);
        model.script(r -> kind("character").test(r) ? Reply.fail(ModelException.Kind.REFUSED) : null);
        Recorder r = act(scene, "说", "雪琪。");
        assertThat(model.requests("character")).hasSize(1);
        assertThat(r.events).last().asString().contains("服务商拒绝");
    }

    @Test
    void oneSceneRunsOneThingAtATime() throws Exception {
        Scene scene = open(xf, xf, lu);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.script(r -> {
            if (kind("round").test(r)) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        });
        Thread first = Thread.startVirtualThread(() -> act(scene, "说", "雪琪。"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runner.busy(scene.id())).isTrue();
        assertThatThrownBy(() -> act(scene, "说", "又一句")).isInstanceOf(ApiError.class).hasMessageContaining("正在进行");
        release.countDown();
        first.join();
        assertThat(runner.busy(scene.id())).isFalse();
    }

    // ---- 结束、回想、下一场 ----

    @Test
    void endingWritesAClosingAndRecollectionsForEveryoneIncludingThePossessed() {
        Scene scene = open(xf, xf, lu);
        act(scene, "说", "雪琪。");
        Recorder r = end(scene);
        assertThat(r.events).last().isEqualTo("done");
        assertThat(store.scene(scene.id()).ended()).isTrue();
        assertThat(lines(scene).getLast().kind()).isEqualTo("narration");
        awaitMemories(2);
        assertThat(store.memories(xf.id())).singleElement().satisfies(m -> assertThat(m.views()).containsKey(lu.id()));
        assertThat(ScriptedModel.prompt(model.requests("recall").stream()
                .filter(q -> q.purpose().subject().equals("张小凡")).findFirst().orElseThrow()))
                .contains("张小凡（说）：雪琪。").doesNotContain("附身");
        assertThatThrownBy(() -> act(scene, "说", "x")).isInstanceOf(ApiError.class).hasMessageContaining("结束");
    }

    @Test
    void aClosingFailureKeepsTheSceneOpenAndCanBeRetriedOrSkipped() {
        Scene scene = open(xf, xf, lu);
        act(scene, "说", "雪琪。");
        model.script(r -> kind("closing").test(r) ? Reply.text("") : null);
        assertThat(end(scene).failed()).isTrue();
        assertThat(store.scene(scene.id()).ended()).isFalse();

        Recorder r = new Recorder();
        runner.end(scene.id(), false, r);
        assertThat(r.events).containsExactly("done");
        assertThat(store.scene(scene.id()).ended()).isTrue();
    }

    @Test
    void theSecondSceneContinuesFromTheFirst() {
        Scene first = open(xf, xf, lu);
        act(first, "说", "那根噬魂，我放在石桌上了。");
        end(first);
        awaitMemories(2);

        Scene second = open(xf, xf, lu);
        assertThat(model.lastPrompt("opening")).contains("【上一场】").contains("张小凡（说）：那根噬魂，我放在石桌上了。");
        act(second, "说", "雪琪。");
        String prompt = model.lastPrompt("character");
        assertThat(prompt).contains("【第" + first.number() + "场】").contains("那根噬魂，我放在石桌上了。")
                .contains("【你眼中的他们】\n张小凡：");
    }

    @Test
    void olderScenesAreRememberedThroughRecollectionsNotRawText() {
        for (int i = 1; i <= 3; i++) {
            Scene s = open(xf, xf, lu);
            act(s, "说", "第" + i + "场的话。");
            end(s);
            awaitMemories(2 * i);
        }
        Scene fourth = open(xf, xf, lu);
        act(fourth, "静候", "");
        String prompt = model.lastPrompt("character");
        assertThat(prompt).contains("【你记得的事】\n第1场：（陆雪琪的替身回想）").doesNotContain("第1场的话。")
                .contains("第2场的话。").contains("第3场的话。");
    }

    @Test
    void aMissingRecollectionIsMadeUpBeforeTheNextSceneOpens() {
        Scene first = open(xf, xf, lu);
        act(first, "说", "雪琪。");
        model.script(r -> kind("recall").test(r) ? Reply.text("") : null);
        end(first);
        long deadline = System.currentTimeMillis() + 5000;
        while (model.requests("recall").size() < 4 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(store.memories(lu.id())).isEmpty();

        model.script(r -> null);
        open(xf, xf, lu);
        assertThat(store.memories(lu.id())).hasSize(1);
        assertThat(store.memories(xf.id())).hasSize(1);
    }

    // ---- 记账 ----

    @Test
    void everyAttemptIsRecorded() {
        Scene scene = open(xf, xf, lu);
        model.script(r -> kind("character").test(r) ? Reply.text("") : null);
        act(scene, "说", "雪琪。");
        List<String> outcomes = jdbc.sql("SELECT kind || ':' || outcome FROM model_calls ORDER BY id").query(String.class).list();
        assertThat(outcomes).containsExactly("opening:ok", "round:ok", "character:error", "character:error");
    }
}
