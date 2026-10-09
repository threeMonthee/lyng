package app.lyng.scene;

import static app.lyng.support.ScriptedChatModel.Reply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.event.EventKind;
import app.lyng.event.SceneEvent;
import app.lyng.model.ChatRequest;
import app.lyng.model.ModelException;
import app.lyng.support.IntegrationTest;
import app.lyng.support.StoryFixture;
import app.lyng.support.StoryFixture.Log;
import app.lyng.web.ConflictException;
import app.lyng.world.WorldRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 人物行为测试：用确定性替身验证一场戏的调用链、失败与重试、可见性和记忆，不用序列化或协议检查代替。 */
class SceneBehaviorTest extends IntegrationTest {

    @Autowired
    SceneService scenes;

    @Autowired
    SceneRepository sceneRepository;

    @Autowired
    WorldRepository worlds;

    @Autowired
    RecallService recalls;

    StoryFixture story;

    @BeforeEach
    void setUp() {
        story = StoryFixture.create(jdbc, worlds);
    }

    private UUID newScene() {
        return scenes.create(story.userId(),
                new SceneService.CreateScene(story.yuan().id(), story.qing().id(), "日常相处", "雨夜，客栈后院，阿远刚回来。")).id();
    }

    private Log open(UUID scene) {
        Log log = new Log();
        scenes.open(story.userId(), scene, log).run();
        return log;
    }

    private Log act(UUID scene, String action, String text) {
        Log log = new Log();
        scenes.act(story.userId(), scene, action, text, log).run();
        return log;
    }

    private Log retry(UUID scene) {
        Log log = new Log();
        scenes.retry(story.userId(), scene, log).run();
        return log;
    }

    private List<SceneEvent> events(UUID scene) {
        return sceneRepository.events(story.userId(), scene);
    }

    private List<String> kinds(UUID scene) {
        return events(scene).stream().map(e -> e.kind().name().toLowerCase() + (e.step() != null ? "/" + e.step() : "")).toList();
    }

    private void endAndWaitForRecall(UUID scene) {
        scenes.end(story.userId(), scene, true, new Log()).run();
        recalls.ensureFor(story.userId(), List.of(story.qing().id(), story.yuan().id()));
    }

    // ---- 一轮的顺序 ----

    @Test
    void theNarratorOpensTheSceneFromTheUsersSetupOnly() {
        UUID scene = newScene();
        open(scene);
        assertThat(kinds(scene)).containsExactly("narration");
        ChatRequest opening = model.requests("opening").getFirst();
        assertThat(model.promptOf(opening)).contains("雨夜，客栈后院，阿远刚回来。").contains("客栈的地窖里藏着一个秘密。");
    }

    @Test
    void sayingLetsTheCharacterSpeakFirstAndTheNarratorNeverBlocksHer() throws Exception {
        UUID scene = newScene();
        open(scene);
        CountDownLatch characterStarted = new CountDownLatch(1);
        AtomicBoolean narratorSawCharacterRunning = new AtomicBoolean();
        model.script(request -> switch (request.purpose().kind()) {
            case "character" -> {
                characterStarted.countDown();
                yield null;
            }
            case "round" -> {
                // 叙事者与人物并发：如果叙事者挡在人物前面，人物永远等不到开始，这里就会超时
                narratorSawCharacterRunning.set(awaitQuietly(characterStarted));
                yield Reply.text("叙事：屋檐下的雨声大了。");
            }
            default -> null;
        });

        Log log = act(scene, "说", "我回来了。");

        assertThat(narratorSawCharacterRunning).isTrue();
        assertThat(kinds(scene)).containsExactly("narration", "user", "character", "narration");
        assertThat(log.lines()).containsSubsequence("event:user", "bubble:阿青/行", "bubble:阿青/说", "event:character", "event:narration");
    }

    @Test
    void doingMakesTheNarratorRuleFirstAndTheCharacterSeesTheOutcome() {
        UUID scene = newScene();
        open(scene);
        model.script(request -> request.purpose().kind().equals("round") ? Reply.text("叙事：墙头的瓦片哗啦一声碎了。") : null);

        act(scene, "做", "翻过院墙去追那个人影。");

        assertThat(kinds(scene)).containsExactly("narration", "user", "narration", "character");
        String characterPrompt = model.promptOf(model.requests("character").getFirst());
        assertThat(characterPrompt).contains("阿远（行）：翻过院墙去追那个人影。").contains("叙事：墙头的瓦片哗啦一声碎了。");
    }

    @Test
    void doingWithoutAnUncertainOutcomeLeavesTheNarratorSilent() {
        UUID scene = newScene();
        open(scene);
        model.script(request -> request.purpose().kind().equals("round") ? Reply.text("叙事：无") : null);

        act(scene, "做", "递过去一杯茶。");

        assertThat(kinds(scene)).containsExactly("narration", "user", "character");
    }

    @Test
    void waitingIsRecordedAsNotSpeakingAndTheCharacterMayStillAct() {
        UUID scene = newScene();
        open(scene);
        act(scene, "静候", "");

        assertThat(events(scene).get(1).action()).isEqualTo("静候");
        assertThat(model.promptOf(model.requests("character").getFirst())).contains("阿远没有开口。");
        assertThat(model.promptOf(model.requests("round").getFirst())).contains("阿远没有开口。");
    }

    @Test
    void theCharacterMayChooseToSayNothing() {
        UUID scene = newScene();
        open(scene);
        model.script(request -> request.purpose().kind().equals("character") ? Reply.text("阿青（心声）：随他去。") : null);

        act(scene, "说", "我回来了。");

        SceneEvent turn = events(scene).get(2);
        assertThat(turn.silent()).isTrue();
        assertThat(turn.beats()).isEmpty();
        assertThat(turn.inner()).isEqualTo("随他去。");
    }

    @Test
    void thePossessedCharacterIsNeverRunByTheModel() {
        UUID scene = newScene();
        open(scene);
        act(scene, "说", "我回来了。");
        act(scene, "说", "外面雨很大。");

        assertThat(model.requests("character")).hasSize(2).allSatisfy(r -> assertThat(r.purpose().subject()).isEqualTo("阿青"));
        assertThat(events(scene)).filteredOn(e -> e.kind() == EventKind.CHARACTER).allSatisfy(e -> assertThat(e.speaker()).isEqualTo("阿青"));
    }

    // ---- 可见性 ----

    @Test
    void theCharacterSeesOwnInnerVoiceButTheNarratorSeesNone() {
        UUID scene = newScene();
        open(scene);
        act(scene, "说", "我回来了。");
        String inner = events(scene).get(2).inner();

        act(scene, "说", "外面雨很大。");

        String secondCharacterPrompt = model.promptOf(model.requests("character").get(1));
        assertThat(secondCharacterPrompt).contains("阿青（心声）：" + inner);
        model.requests().stream().filter(r -> r.purpose().kind().equals("round") || r.purpose().kind().equals("closing"))
                .forEach(r -> assertThat(model.promptOf(r)).doesNotContain(inner));
    }

    @Test
    void theCharacterNeverSeesTheUsersSetupOrNarratorOnlySettings() {
        UUID scene = newScene();
        open(scene);
        act(scene, "说", "我回来了。");

        String prompt = model.promptOf(model.requests("character").getFirst());
        assertThat(prompt).doesNotContain("雨夜，客栈后院，阿远刚回来。").doesNotContain("客栈的地窖里藏着一个秘密。");
        assertThat(prompt).contains("山脚有一座小镇").contains("阿青是客栈的伙计").doesNotContain("阿远是路过的刀客");
    }

    // ---- 失败与重试 ----

    @Test
    void aFailedCharacterTurnIsATechnicalFailureNotSilenceAndIsRetriedOnce() {
        UUID scene = newScene();
        open(scene);
        model.script(request -> request.purpose().kind().equals("character") ? Reply.text("   ") : null);

        Log log = act(scene, "说", "我回来了。");

        assertThat(kinds(scene)).containsExactly("narration", "user", "failure/character");
        SceneEvent failure = events(scene).get(2);
        assertThat(failure.text()).contains("空响应").contains("已重试一次");
        assertThat(failure.speaker()).isEqualTo("阿青");
        assertThat(events(scene)).noneMatch(e -> e.kind() == EventKind.CHARACTER); // 没有“沉默”事件
        assertThat(model.requests("character")).hasSize(2);
        assertThat(log.lines()).contains("event:failure/character");
        // 两次尝试都记了账
        assertThat(jdbc.sql("SELECT count(*) FROM model_calls WHERE kind = 'character'").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM model_calls WHERE kind = 'character' AND outcome = 'error'").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void nobodySeesTheFailureAndTheUserCannotMoveOnUntilItIsRetried() {
        UUID scene = newScene();
        open(scene);
        model.script(request -> request.purpose().kind().equals("character") ? Reply.truncated("阿青（说）：你") : null);
        act(scene, "说", "我回来了。");

        assertThatThrownBy(() -> scenes.act(story.userId(), scene, "说", "喂？", new Log()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("请先重试");
        model.reset();
        retry(scene);
        act(scene, "说", "喂？");

        String laterPrompt = model.promptOf(model.requests("character").getLast());
        assertThat(laterPrompt).doesNotContain("空响应").doesNotContain("截断").doesNotContain("技术失败").doesNotContain("阿青没有开口");
    }

    @Test
    void retryRedoesOnlyTheFailedStepAndKeepsWhatWasAlreadySaid() {
        UUID scene = newScene();
        open(scene);
        AtomicInteger narratorCalls = new AtomicInteger();
        model.script(request -> {
            if (request.purpose().kind().equals("round")) {
                narratorCalls.incrementAndGet();
                return Reply.fail(ModelException.Kind.TRANSIENT, 503); // 叙事者两次都失败
            }
            return null;
        });
        act(scene, "说", "我回来了。");
        // 人物那一路不受影响
        assertThat(kinds(scene)).containsExactly("narration", "user", "character", "failure/narrator");
        SceneEvent said = events(scene).get(2);
        assertThat(SceneStatus.unresolvedFailures(events(scene))).hasSize(1);
        int characterCalls = model.requests("character").size();

        model.script(request -> request.purpose().kind().equals("round") ? Reply.text("叙事：远处有人在敲门。") : null);
        Log log = retry(scene);

        assertThat(model.requests("character")).hasSize(characterCalls); // 没有重抽人物
        assertThat(events(scene).get(2)).isEqualTo(said);
        assertThat(kinds(scene)).containsExactly("narration", "user", "character", "failure/narrator", "narration", "resolved");
        assertThat(SceneStatus.unresolvedFailures(events(scene))).isEmpty();
        assertThat(log.lines()).containsExactly("event:narration", "event:resolved");
        assertThat(narratorCalls.get()).isEqualTo(2);
    }

    @Test
    void ifTheNarratorFailsBeforeADoActionTheCharacterWaitsForTheRetry() {
        UUID scene = newScene();
        open(scene);
        model.script(request -> request.purpose().kind().equals("round") ? Reply.fail(ModelException.Kind.TRANSIENT, 500) : null);
        act(scene, "做", "翻过院墙。");
        assertThat(kinds(scene)).containsExactly("narration", "user", "failure/narrator");
        assertThat(model.requests("character")).isEmpty();

        model.script(request -> request.purpose().kind().equals("round") ? Reply.text("叙事：瓦片碎了。") : null);
        retry(scene);

        assertThat(kinds(scene)).containsExactly("narration", "user", "failure/narrator", "narration", "character", "resolved");
        assertThat(model.promptOf(model.requests("character").getFirst())).contains("叙事：瓦片碎了。");
    }

    @Test
    void aRetriedCharacterSeesExactlyWhatSheSawBefore() {
        UUID scene = newScene();
        open(scene);
        model.script(request -> switch (request.purpose().kind()) {
            case "character" -> Reply.text("");
            case "round" -> Reply.text("叙事：窗外打了一个闪。");
            default -> null;
        });
        act(scene, "说", "我回来了。");
        String before = model.promptOf(model.requests("character").getFirst());

        model.reset();
        retry(scene);

        // 那句晚到的叙事不会塞进她重试时的上下文：条件与第一次相同
        assertThat(model.promptOf(model.requests("character").getFirst())).isEqualTo(before);
        assertThat(kinds(scene)).containsSubsequence("failure/character", "narration", "character", "resolved");
    }

    @Test
    void anInvalidTurnAfterBubblesWereShownIsRetractedAndRetried() {
        UUID scene = newScene();
        open(scene);
        AtomicInteger attempts = new AtomicInteger();
        model.script(request -> {
            if (request.purpose().kind().equals("character") && attempts.getAndIncrement() == 0) {
                return Reply.text("阿青（说）：你回来了。\n阿青（行）：她笑了。"); // 缺少心声
            }
            return null;
        });

        Log log = act(scene, "说", "我回来了。");

        assertThat(log.lines()).containsSubsequence("bubble:阿青/说", "bubble:阿青/行", "retract:阿青", "bubble:阿青/行", "bubble:阿青/说", "event:character");
        assertThat(events(scene)).filteredOn(e -> e.kind() == EventKind.CHARACTER).hasSize(1);
        assertThat(jdbc.sql("SELECT outcome FROM model_calls WHERE kind = 'character' ORDER BY id").query(String.class).list())
                .containsExactly("error", "ok");
    }

    @Test
    void anotherPersonsLineIsCaughtWhileStreamingAndNeverShown() {
        UUID scene = newScene();
        open(scene);
        AtomicInteger attempts = new AtomicInteger();
        model.script(request -> {
            if (request.purpose().kind().equals("character") && attempts.getAndIncrement() == 0) {
                return Reply.text("阿青（说）：你回来了。\n阿远（说）：我累了。\n阿青（心声）：嗯。");
            }
            return null;
        });

        Log log = act(scene, "说", "我回来了。");

        assertThat(log.lines()).doesNotContain("bubble:阿远/说");
        assertThat(events(scene)).filteredOn(e -> e.kind() == EventKind.CHARACTER).hasSize(1);
    }

    @Test
    void theOpeningCanFailAndBeRetried() {
        UUID scene = newScene();
        model.script(request -> Reply.fail(ModelException.Kind.TRANSIENT, 429));
        open(scene);
        assertThat(kinds(scene)).containsExactly("failure/opening");
        assertThat(events(scene).getFirst().text()).contains("HTTP 429");

        model.reset();
        retry(scene);

        assertThat(kinds(scene)).containsExactly("failure/opening", "narration", "resolved");
        assertThat(SceneStatus.opened(events(scene))).isTrue();
    }

    @Test
    void aProviderRefusalOrAFatalErrorIsNotRetriedAutomatically() {
        UUID scene = newScene();
        open(scene);
        model.script(request -> request.purpose().kind().equals("character")
                ? Reply.fail(ModelException.Kind.REFUSED, 403) : null);
        act(scene, "说", "我回来了。");
        assertThat(model.requests("character")).hasSize(1);
        assertThat(events(scene).get(2).text()).contains("服务商拒绝");
    }

    @Test
    void oneSceneRunsOneRoundAtATime() {
        UUID scene = newScene();
        Runnable first = scenes.open(story.userId(), scene, new Log());
        assertThatThrownBy(() -> scenes.open(story.userId(), scene, new Log())).isInstanceOf(ConflictException.class);
        first.run();
        assertThat(scenes.detail(story.userId(), scene).busy()).isFalse();
    }

    // ---- 散场、回想与第二场 ----

    @Test
    void endingTheSceneWritesAClosingAndRecollectionsForEveryoneIncludingThePossessed() {
        UUID scene = newScene();
        open(scene);
        act(scene, "说", "我回来了。");
        endAndWaitForRecall(scene);

        assertThat(kinds(scene).getLast()).isEqualTo("narration");
        assertThat(sceneRepository.scene(story.userId(), scene).ended()).isTrue();
        List<Recollection> qing = recollectionsOf(story.qing().id());
        List<Recollection> yuan = recollectionsOf(story.yuan().id());
        assertThat(qing).singleElement().satisfies(r -> {
            assertThat(r.done()).isTrue();
            assertThat(r.views()).containsKey(story.yuan().id());
        });
        assertThat(yuan).singleElement().satisfies(r -> assertThat(r.views()).containsKey(story.qing().id()));
        assertThatThrownBy(() -> scenes.act(story.userId(), scene, "说", "还在吗", new Log())).isInstanceOf(ConflictException.class);
    }

    private List<Recollection> recollectionsOf(UUID characterId) {
        return new RecollectionRepository(jdbc, jsonMapper()).ofCharacter(story.userId(), characterId);
    }

    @Autowired
    tools.jackson.databind.json.JsonMapper json;

    private tools.jackson.databind.json.JsonMapper jsonMapper() {
        return json;
    }

    @Test
    void thePossessedCharactersRecollectionDoesNotShowTheWordPossession() {
        UUID scene = newScene();
        open(scene);
        act(scene, "说", "我回来了。");
        endAndWaitForRecall(scene);

        String prompt = model.promptOf(model.requests("recall").stream()
                .filter(r -> r.purpose().subject().equals("阿远")).findFirst().orElseThrow());
        assertThat(prompt).doesNotContain("附身").contains("阿远（说）：我回来了。");
        assertThat(prompt).doesNotContain("阿青（心声）");
    }

    @Test
    void theSecondSceneContinuesFromTheFirst() {
        UUID first = newScene();
        open(first);
        act(first, "说", "我回来了。");
        endAndWaitForRecall(first);
        model.clearRequests();

        UUID second = newScene();
        open(second);
        act(second, "说", "又下雨了。");

        String prompt = model.promptOf(model.requests("character").getFirst());
        assertThat(prompt).contains("【第1场】").contains("阿远（说）：我回来了。").contains("阿青（心声）：");
        assertThat(prompt).contains("【你眼中的他们】").contains("阿远：（替身看法）");
        assertThat(model.promptOf(model.requests("opening").getFirst())).contains("【上一场】").contains("阿远（说）：我回来了。");
        assertThat(model.promptOf(model.requests("opening").getFirst())).doesNotContain("阿青（心声）");
    }

    @Test
    void olderScenesAreRememberedThroughRecollectionsNotRawText() {
        for (int i = 1; i <= 3; i++) {
            UUID scene = newScene();
            open(scene);
            act(scene, "说", "这是第" + i + "场的话。");
            endAndWaitForRecall(scene);
        }
        model.clearRequests();

        UUID fourth = newScene();
        open(fourth);
        act(fourth, "说", "第四场了。");

        String prompt = model.promptOf(model.requests("character").getFirst());
        // 最近两场给原文，更早的一场给回想
        assertThat(prompt).contains("【第3场】").contains("【第2场】").doesNotContain("这是第1场的话。");
        assertThat(prompt).contains("【你记得的事】").contains("第1场：（阿青的替身回想）");
    }

    @Test
    void aMissingRecollectionIsMadeUpBeforeTheNextSceneAndOtherwiseFallsBackToTheOriginal() {
        UUID first = newScene();
        open(first);
        act(first, "说", "我回来了。");
        // 回想失败
        model.script(request -> request.purpose().kind().equals("recall") ? Reply.text("乱写一通") : null);
        endAndWaitForRecall(first);
        assertThat(recollectionsOf(story.qing().id())).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo("failed"));

        model.reset();
        UUID second = newScene();
        open(second); // 新一场开始前补做缺失的回想
        assertThat(recollectionsOf(story.qing().id())).singleElement().satisfies(r -> assertThat(r.done()).isTrue());
    }

    @Test
    void recollectionIsReadOnlyOnTheCharacterPage() {
        UUID scene = newScene();
        open(scene);
        act(scene, "说", "我回来了。");
        endAndWaitForRecall(scene);

        RecallService.CharacterMemory memory = recalls.memoryOf(story.userId(), story.qing().id());
        assertThat(memory.memories()).singleElement().satisfies(m -> assertThat(m.sceneNumber()).isEqualTo(1));
        assertThat(memory.relations()).singleElement().satisfies(r -> assertThat(r.name()).isEqualTo("阿远"));
    }

    @Test
    void closingFailureKeepsTheSceneOpenAndCanBeRetriedOrSkipped() {
        UUID scene = newScene();
        open(scene);
        act(scene, "说", "我回来了。");
        model.script(request -> request.purpose().kind().equals("closing") ? Reply.fail(ModelException.Kind.TRANSIENT, 503) : null);
        scenes.end(story.userId(), scene, true, new Log()).run();
        assertThat(sceneRepository.scene(story.userId(), scene).ended()).isFalse();
        assertThat(kinds(scene).getLast()).isEqualTo("failure/closing");

        model.reset();
        retry(scene);
        assertThat(sceneRepository.scene(story.userId(), scene).ended()).isTrue();

        UUID other = newScene();
        open(other);
        scenes.end(story.userId(), other, false, new Log()).run(); // 直接结束，不写收尾
        assertThat(sceneRepository.scene(story.userId(), other).ended()).isTrue();
        assertThat(model.requests("closing")).hasSize(1); // reset 之后只有重试这一次；直接结束不写收尾
    }

    @Test
    void usageIsRecordedForEveryCall() {
        UUID scene = newScene();
        open(scene);
        act(scene, "说", "我回来了。");
        List<String> kinds = jdbc.sql("SELECT kind FROM model_calls ORDER BY id").query(String.class).list();
        assertThat(kinds).containsExactlyInAnyOrder("opening", "character", "round");
        assertThat(jdbc.sql("SELECT count(*) FROM model_calls WHERE provider IS NOT NULL AND cost IS NOT NULL").query(Integer.class).single())
                .isEqualTo(3);
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
