package app.lyng.scene;

import static app.lyng.support.ScriptedModel.forName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.mind.Prompts;
import app.lyng.model.ModelRequest;
import app.lyng.model.Purpose;
import app.lyng.store.Conflict;
import app.lyng.store.Inner;
import app.lyng.store.Scenes;
import app.lyng.store.Scenes.Line;
import app.lyng.store.Settings;
import app.lyng.store.Traces;
import app.lyng.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SceneFlowTest extends IntegrationTest {

    @Autowired
    SceneService scenes;
    @Autowired
    Scenes store;
    @Autowired
    Traces traces;
    @Autowired
    Inner inner;
    @Autowired
    Settings settings;

    private UUID groupScene() {
        UUID id = scenes.create(world, List.of(xf, lu, by), xf, null, Prompts.DAILY, "长街，午后。");
        model.reply(Purpose.OPENING, "长街上人来人往。");
        scenes.resume(id, new Recorder());
        return id;
    }

    private static String user(ModelRequest r) {
        return r.messages().getLast().content();
    }

    private List<String> kinds(UUID scene) {
        return store.lines(scene).stream().map(l -> l.kind() + ":" + (l.speakerId() == null ? "叙事" : name(l.speakerId())))
                .toList();
    }

    @Test
    void aGroupRoundIsNarratorFirstThenEachNamedCharacterInOrder() {
        UUID scene = groupScene();
        model.reply(Purpose.NARRATOR, "叙事：无\n轮到：陆雪琪、碧瑶")
                .reply(Purpose.CHARACTER, "陆雪琪（行）：陆雪琪停住了脚步。\n陆雪琪（心声）：他来了。")
                .reply(Purpose.CHARACTER, "碧瑶（说）：哟，这不是陆师姐么？\n碧瑶（心声）：她来做什么。");
        Recorder events = new Recorder();
        scenes.act(scene, "say", "雪琪。", events);

        assertThat(kinds(scene)).containsExactly("narration:叙事", "say:张小凡", "do:陆雪琪", "inner:陆雪琪", "say:碧瑶",
                "inner:碧瑶");
        assertThat(store.lines(scene).get(1).byUser()).isTrue();
        assertThat(events.names).containsSubsequence("saved", "status", "status", "draft", "saved", "status", "draft",
                "saved", "done");
        assertThat(store.require(scene).pending()).isEmpty();

        ModelRequest narrator = model.requests(Purpose.NARRATOR).getFirst();
        assertThat(user(narrator)).contains("【刚刚】\n张小凡（说）：雪琪。");
        assertThat(user(narrator).substring(0, user(narrator).indexOf("【刚刚】"))).doesNotContain("雪琪。");
        assertThat(narrator.meta()).containsEntry("candidates", List.of("陆雪琪", "碧瑶"));

        ModelRequest biyao = model.requests(Purpose.CHARACTER).get(1);
        assertThat(user(biyao)).contains("陆雪琪（行）：陆雪琪停住了脚步。").doesNotContain("他来了。");
        assertThat(biyao.meta()).containsEntry("name", "碧瑶").containsEntry("allowActions", true);
    }

    @Test
    void everyoneRemembersOnlyWhatTheyCouldPerceive() {
        UUID scene = groupScene();
        model.reply(Purpose.NARRATOR, "叙事：无\n轮到：陆雪琪、碧瑶")
                .reply(Purpose.CHARACTER, "陆雪琪（行）：陆雪琪停住了脚步。\n陆雪琪（心声）：他来了。")
                .reply(Purpose.CHARACTER, "碧瑶（心声）：不理她。");
        scenes.act(scene, "say", "雪琪。", new Recorder());

        List<String> seenByBiyao = traces.bySource(by, scene, 0, 100).stream().map(Traces.Trace::text).toList();
        assertThat(seenByBiyao).containsExactly("叙事：长街上人来人往。", "张小凡（说）：雪琪。", "陆雪琪（行）：陆雪琪停住了脚步。",
                "碧瑶没有开口。", "碧瑶（心声）：不理她。");
        List<String> seenByXiaofan = traces.bySource(xf, scene, 0, 100).stream().map(Traces.Trace::text).toList();
        assertThat(seenByXiaofan).contains("碧瑶没有开口。").noneMatch(t -> t.contains("心声"));
        assertThat(traces.bySource(by, scene, 0, 100)).allSatisfy(t -> assertThat(t.sourceLabel()).isEqualTo("第1场"));
    }

    @Test
    void aFailedStepStaysPendingAndRetryContinuesFromIt() {
        UUID scene = groupScene();
        model.reply(Purpose.NARRATOR, "叙事：一阵风起。\n轮到：陆雪琪")
                .reply(Purpose.CHARACTER, "陆雪琪（说）：走吧。\n陆雪琪（行）：转身。\n碧瑶（说）：替她说。\n陆雪琪（心声）：嗯。")
                .reply(Purpose.CHARACTER, "胡言乱语");
        Recorder failed = new Recorder();
        scenes.act(scene, "say", "雪琪。", failed);

        assertThat(failed.names).contains("draft", "discard", "failed");
        assertThat(failed.first("failed")).containsKey("runId");
        Scenes.Scene s = store.require(scene);
        assertThat(s.pending()).containsExactly(lu.toString());
        assertThat(s.failure()).contains("已自动重试一次");
        assertThat(kinds(scene)).containsExactly("narration:叙事", "say:张小凡", "narration:叙事");
        assertThatThrownBy(() -> scenes.act(scene, "say", "怎么了？", new Recorder())).isInstanceOf(Conflict.class);

        model.reply(Purpose.CHARACTER, "陆雪琪（说）：走吧。\n陆雪琪（心声）：嗯。");
        scenes.resume(scene, new Recorder());
        assertThat(kinds(scene)).endsWith("say:陆雪琪", "inner:陆雪琪");
        assertThat(store.require(scene).pending()).isEmpty();
        assertThat(store.require(scene).failure()).isNull();
    }

    @Test
    void inAdultModeAnUnwelcomeTouchDoesNotHappen() {
        settings.update(Prompts.ADULT, null);
        UUID scene = scenes.create(world, List.of(xf, by), xf, null, Prompts.DAILY, "狐岐山，夜。");
        model.reply(Purpose.OPENING, "石室里很静。");
        scenes.resume(scene, new Recorder());
        model.reply(Purpose.CONSENT, "判定：拒绝");
        Recorder events = new Recorder();
        scenes.act(scene, "do", "握住碧瑶的手。", events);

        assertThat(events.first("notice")).isNotNull();
        assertThat(kinds(scene)).containsExactly("narration:叙事");
        assertThat(store.require(scene).round()).isZero();
        assertThat(model.requests(Purpose.NARRATOR)).isEmpty();

        model.reply(Purpose.CONSENT, "判定：可以").reply(Purpose.NARRATOR, "叙事：无")
                .reply(Purpose.CHARACTER, "碧瑶（行）：碧瑶没有抽回手。\n碧瑶（心声）：呆子。");
        scenes.act(scene, "do", "握住碧瑶的手。", new Recorder());
        assertThat(kinds(scene)).containsExactly("narration:叙事", "do:张小凡", "do:碧瑶", "inner:碧瑶");
        assertThat(user(model.requests(Purpose.CONSENT).getFirst())).contains("张小凡（行）：握住碧瑶的手。");
    }

    @Test
    void inMildModeThereIsNoConsentCheck() {
        UUID scene = scenes.create(world, List.of(xf, by), xf, null, Prompts.DAILY, "狐岐山，夜。");
        scenes.resume(scene, new Recorder());
        scenes.act(scene, "do", "握住碧瑶的手。", new Recorder());
        assertThat(model.requests(Purpose.CONSENT)).isEmpty();
    }

    @Test
    void observingMeansContinuingAndTheNarratorMustMoveThingsForward() {
        UUID scene = scenes.create(world, List.of(lu, by), null, lu, Prompts.ADVENTURE, "渡口，傍晚。");
        scenes.resume(scene, new Recorder());
        assertThatThrownBy(() -> scenes.act(scene, "say", "你好", new Recorder())).isInstanceOf(IllegalArgumentException.class);

        model.reply(Purpose.NARRATOR, "叙事：无\n轮到：无", "叙事：起风了。\n轮到：碧瑶")
                .reply(Purpose.CHARACTER, "碧瑶（说）：要下雨了。\n碧瑶（心声）：嗯。");
        scenes.act(scene, "continue", null, new Recorder());

        ModelRequest narrator = model.requests(Purpose.NARRATOR).getFirst();
        assertThat(narrator.meta()).containsEntry("advance", true);
        assertThat(user(narrator)).contains("没有新的动作。");
        assertThat(narrator.messages().getFirst().content()).contains("这一场跟随陆雪琪");
        assertThat(kinds(scene)).endsWith("narration:叙事", "say:碧瑶", "inner:碧瑶");
    }

    @Test
    void switchingIdentityChangesWhoTheNarratorLeavesAlone() {
        UUID scene = groupScene();
        scenes.switchIdentity(scene, by, null);
        model.reply(Purpose.NARRATOR, "叙事：无\n轮到：陆雪琪");
        scenes.act(scene, "say", "陆师姐。", new Recorder());
        assertThat(model.requests(Purpose.NARRATOR).getFirst().meta()).containsEntry("candidates", List.of("张小凡", "陆雪琪"));
        assertThat(store.lines(scene).get(1).speakerId()).isEqualTo(by);
        assertThatThrownBy(() -> scenes.switchIdentity(scene, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCharacterCanOnlyBeInOneOpenScene() {
        groupScene();
        assertThatThrownBy(() -> scenes.create(world, List.of(lu), null, lu, Prompts.DAILY, "别处。"))
                .isInstanceOf(Conflict.class).hasMessageContaining("陆雪琪");
    }

    @Test
    void theNextSceneOpensWithThePreviousOneInMind() {
        UUID first = scenes.create(world, List.of(xf, lu), xf, null, Prompts.DAILY, "山海苑，入夜。");
        model.reply(Purpose.OPENING, "山海苑后院，入夜。");
        scenes.resume(first, new Recorder());
        scenes.end(first, false, new Recorder());
        settle();
        UUID second = scenes.create(world, List.of(xf, lu), xf, null, Prompts.DAILY, "渡口，次日。");
        scenes.resume(second, new Recorder());
        assertThat(model.requests(Purpose.OPENING).getLast().messages().getFirst().content())
                .contains("【上一场】\n叙事：山海苑后院，入夜。");
        assertThat(store.require(second).number()).isEqualTo(2);
    }

    @Test
    void closingTheSceneLetsEveryoneLookBackInTheirOwnWords() {
        UUID concern = inner.addConcern(lu, xf, "他说不是他，我没有信。", null);
        UUID scene = scenes.create(world, List.of(xf, lu), xf, null, Prompts.DAILY, "山海苑，入夜。");
        model.reply(Purpose.OPENING, "山海苑后院，入夜。");
        scenes.resume(scene, new Recorder());
        model.reply(Purpose.NARRATOR, "叙事：无")
                .reply(Purpose.CHARACTER, "陆雪琪（说）：是我错怪了你。\n陆雪琪（心声）：他瘦了。");
        scenes.act(scene, "do", "把骨铃放在石桌上。", new Recorder());
        model.reply(Purpose.CLOSING, "夜深了，灯还亮着。")
                .replyWhen(Purpose.CONSOLIDATE, forName("陆雪琪"), """
                        【记忆】他把骨铃放在石桌上，我说是我错怪了他。
                        【意义】我欠他的，比一句对不起多。
                        【看法·张小凡】他还是从前那个人。我信他。
                        【了结·1】他把真凶押来了，我信了。
                        【牵挂·张小凡】还欠他一句好好的道歉。
                        【自我】我比自己以为的更愿意相信他。""")
                .replyWhen(Purpose.CONSOLIDATE, forName("张小凡"), "【无】");
        Recorder events = new Recorder();
        scenes.end(scene, true, events);
        assertThat(events.names).contains("closed", "done");
        assertThat(store.require(scene).status()).isEqualTo("closed");
        settle();

        Inner.Memory memory = inner.memories(lu, 10, 0).getFirst();
        assertThat(memory.event()).contains("骨铃");
        assertThat(memory.people()).containsExactly(xf);
        assertThat(memory.sourceLabel()).isEqualTo("第1场");
        assertThat(inner.view(lu, xf).orElseThrow().text()).isEqualTo("他还是从前那个人。我信他。");
        assertThat(inner.concerns(lu)).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(concern);
            assertThat(c.status()).isEqualTo("resolved");
            assertThat(c.resolution()).isEqualTo("他把真凶押来了，我信了。");
        });
        assertThat(inner.activeConcernsAbout(lu, xf)).extracting(Inner.Concern::text).containsExactly("还欠他一句好好的道歉。");
        assertThat(inner.self(lu).orElseThrow().text()).isEqualTo("我比自己以为的更愿意相信他。");
        assertThat(inner.memories(xf, 10, 0)).isEmpty();

        String prompt = model.requests(Purpose.CONSOLIDATE).stream().filter(forName("陆雪琪")).findFirst().orElseThrow()
                .messages().getLast().content();
        assertThat(prompt).contains("陆雪琪（心声）：他瘦了。", "1. 他说不是他，我没有信。（关于张小凡）", "可写的人：张小凡");
        assertThat(jdbc.sql("SELECT count(*) FROM consolidations WHERE status = 'done'").query(Integer.class).single())
                .isEqualTo(2);
    }

    @Test
    void aFailedLookBackIsKeptAndDoneBeforeTheCharacterSpeaksAgain() {
        UUID scene = scenes.create(world, List.of(xf, lu), xf, null, Prompts.DAILY, "山海苑，入夜。");
        scenes.resume(scene, new Recorder());
        model.replyWhen(Purpose.CONSOLIDATE, forName("陆雪琪"), "乱写")
                .replyWhen(Purpose.CONSOLIDATE, forName("陆雪琪"), "还是乱写");
        scenes.end(scene, false, new Recorder());
        settle();
        assertThat(jdbc.sql("SELECT status FROM consolidations WHERE character_id = ?").param(lu).query(String.class).single())
                .isEqualTo("failed");

        UUID next = scenes.create(world, List.of(xf, lu), xf, null, Prompts.DAILY, "渡口。");
        scenes.resume(next, new Recorder());
        model.reply(Purpose.NARRATOR, "叙事：无").reply(Purpose.CHARACTER, "陆雪琪（心声）：嗯。");
        scenes.act(next, "wait", null, new Recorder());
        assertThat(jdbc.sql("SELECT status FROM consolidations WHERE character_id = ?").param(lu).query(String.class).single())
                .isEqualTo("done");
        List<Line> lines = store.lines(next);
        assertThat(lines.getLast().kind()).isEqualTo("inner");
    }
}
