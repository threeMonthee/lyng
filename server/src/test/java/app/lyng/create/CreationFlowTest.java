package app.lyng.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.mind.Markup;
import app.lyng.mind.Markup.Draft;
import app.lyng.mind.Prompts.Answer;
import app.lyng.model.ModelException;
import app.lyng.model.Purpose;
import app.lyng.store.Conflict;
import app.lyng.store.Inner;
import app.lyng.store.Worlds.Character;
import app.lyng.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CreationFlowTest extends IntegrationTest {

    @Autowired
    Inner inner;

    private static final String NEW_WORLD = """
            【世界名】临水
            【世界·公开】
            临水的小城，靠渡口与茶馆过日子。
            此刻是初秋。
            【世界·只有叙事者知道】
            书院的旧主人没有离开。
            【人物】沈知微
            【小传】
            十六岁撑起了茶馆。
            【此刻】二十四岁。
            【看法·顾长川】信得过，只是猜不透。
            【牵挂·顾长川】上次的话没说完。
            【人物】顾长川
            【小传】渡口的船家。
            【牵挂】船要修了。""";

    @Test
    void fromOneSentenceToANewWorldWithItsPeople() {
        model.reply(Purpose.ASK, "【问题】沈知微和顾长川是什么关系？\n【选项】旧识｜初见\n【推荐】旧识")
                .reply(Purpose.WRITE, NEW_WORLD)
                .reply(Purpose.CHECK, "【冲突】沈知微的年纪与小传对不上。\n【建议】写明十六岁是八年前。");
        CreationService.Page asked = creation.start(null, "临水小城，茶馆老板沈知微和船家顾长川");
        assertThat(asked.status()).isEqualTo("asking");
        assertThat(asked.questions()).singleElement().satisfies(q -> assertThat(q.recommended()).isEqualTo("旧识"));

        Recorder events = new Recorder();
        creation.write(asked.id(), List.of(new Answer("沈知微和顾长川是什么关系？", "旧识")), events);
        assertThat(model.requests(Purpose.WRITE).getFirst().messages().getLast().content())
                .contains("【用户对追问的回答】\n沈知微和顾长川是什么关系？ → 旧识");
        assertThat(events.names).containsSubsequence("status", "status", "status", "saved", "status", "saved", "done");
        assertThat(events.data).anySatisfy(d -> assertThat(d).containsEntry("name", "顾长川"));

        CreationService.Page ready = creation.page(asked.id());
        assertThat(ready.status()).isEqualTo("ready");
        assertThat(ready.content().characters()).extracting(Markup.DraftCharacter::name).containsExactly("沈知微", "顾长川");
        assertThat(ready.conflicts()).singleElement().satisfies(c -> assertThat(c.suggestion()).contains("八年前"));

        CreationService.Born born = creation.birth(asked.id());
        assertThat(worlds.requireWorld(born.worldId()).name()).isEqualTo("临水");
        List<Character> people = worlds.characters(born.worldId());
        assertThat(people).extracting(Character::name).containsExactly("沈知微", "顾长川");
        UUID shen = people.get(0).id();
        UUID gu = people.get(1).id();
        assertThat(inner.view(shen, gu).orElseThrow().text()).isEqualTo("信得过，只是猜不透。");
        assertThat(inner.activeConcernsAbout(shen, gu)).extracting(Inner.Concern::text).containsExactly("上次的话没说完。");
        assertThat(inner.concerns(gu)).singleElement().satisfies(c -> assertThat(c.aboutId()).isNull());
        assertThat(creation.page(asked.id()).status()).isEqualTo("born");
        assertThatThrownBy(() -> creation.birth(asked.id())).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> creation.delete(asked.id())).isInstanceOf(Conflict.class);
    }

    @Test
    void handEditsFollowTheSameRulesAndAiRevisionsAreOnlySuggestions() {
        model.reply(Purpose.ASK, "【无】").reply(Purpose.WRITE, NEW_WORLD).reply(Purpose.CHECK, "【无】");
        CreationService.Page page = creation.start(null, "临水小城");
        creation.write(page.id(), List.of(), new Recorder());
        Draft content = creation.page(page.id()).content();

        Draft badName = new Draft(content.world(), List.of(new Markup.DraftCharacter("沈 知微", "【小传】茶馆。", List.of(),
                List.of())));
        assertThatThrownBy(() -> creation.edit(page.id(), badName)).isInstanceOf(IllegalArgumentException.class);

        model.reply(Purpose.REVISE, "【小传】\n十六岁撑起了茶馆，爱喝一点酒。\n【此刻】二十四岁。");
        CreationService.Revision revision = creation.revise(page.id(), "character.0", "让她爱喝一点酒");
        assertThat(revision.text()).contains("爱喝一点酒");
        assertThat(model.requests(Purpose.REVISE).getFirst().messages().getLast().content())
                .contains("【要改的一段：沈知微的档案】", "让她爱喝一点酒");
        assertThat(creation.page(page.id()).content().characters().getFirst().profile()).doesNotContain("酒");

        var first = content.characters().getFirst();
        Draft edited = new Draft(content.world(), List.of(new Markup.DraftCharacter(first.name(), revision.text(),
                first.views(), first.concerns()), content.characters().get(1)));
        assertThat(creation.edit(page.id(), edited).content().characters().getFirst().profile()).contains("爱喝一点酒");
        assertThatThrownBy(() -> creation.revise(page.id(), "character.9", "改")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newPeopleJoinAnExistingWorldAndMayAlreadyKnowSomeone() {
        model.reply(Purpose.ASK, "【无】")
                .reply(Purpose.WRITE, "【人物】曾书书\n【小传】风回峰的弟子，张小凡的老朋友。\n【看法·张小凡】老朋友，可惜走了岔路。")
                .reply(Purpose.CHECK, "【无】");
        CreationService.Page page = creation.start(world, "再添一位：曾书书");
        assertThat(model.requests(Purpose.ASK).getFirst().messages().getLast().content())
                .contains("【这个世界里已有的人物】\n陆雪琪、张小凡、碧瑶");
        creation.write(page.id(), List.of(), new Recorder());
        CreationService.Born born = creation.birth(page.id());
        assertThat(born.worldId()).isEqualTo(world);
        UUID zeng = born.characterIds().getFirst();
        assertThat(inner.view(zeng, xf).orElseThrow().text()).isEqualTo("老朋友，可惜走了岔路。");
    }

    @Test
    void aNameAlreadyInTheWorldStopsTheBirthWithoutHalfTheWorldBorn() {
        model.reply(Purpose.ASK, "【无】")
                .reply(Purpose.WRITE, "【人物】林惊羽\n【小传】龙首峰弟子。\n【人物】陆雪琪\n【小传】另一个陆雪琪。")
                .reply(Purpose.CHECK, "【无】");
        CreationService.Page page = creation.start(world, "再添两位");
        creation.write(page.id(), List.of(), new Recorder());
        assertThatThrownBy(() -> creation.birth(page.id())).isInstanceOf(Conflict.class).hasMessageContaining("陆雪琪");
        assertThat(worlds.characters(world)).extracting(Character::name).doesNotContain("林惊羽");
        assertThat(creation.page(page.id()).status()).isEqualTo("ready");
    }

    @Test
    void aFailedWriteCanBeTriedAgain() {
        model.reply(Purpose.ASK, "【无】").fail(Purpose.WRITE, ModelException.Kind.TRANSIENT, "502")
                .fail(Purpose.WRITE, ModelException.Kind.TRANSIENT, "502");
        CreationService.Page page = creation.start(null, "临水小城");
        Recorder failed = new Recorder();
        creation.write(page.id(), List.of(), failed);
        assertThat(failed.names).contains("failed");
        assertThat(creation.page(page.id()).status()).isEqualTo("failed");
        assertThat(creation.page(page.id()).failure()).contains("502");

        model.reply(Purpose.WRITE, NEW_WORLD).reply(Purpose.CHECK, "【无】");
        creation.write(page.id(), List.of(), new Recorder());
        assertThat(creation.page(page.id()).status()).isEqualTo("ready");
        assertThat(creation.page(page.id()).conflicts()).isEmpty();
    }

    @Test
    void aFailedQuestionStepIsVisibleAndCanBeAskedAgain() {
        model.fail(Purpose.ASK, ModelException.Kind.FATAL, "401");
        CreationService.Page page = creation.start(null, "临水小城");
        assertThat(creation.unfinished()).extracting(CreationService.Page::id).containsExactly(page.id());
        assertThat(page.failure()).contains("401");
        assertThat(page.questions()).isNull();
        model.reply(Purpose.ASK, "【无】");
        assertThat(creation.ask(page.id()).questions()).isEmpty();
    }

    @Test
    void theSeedWorldIsTheValidatedOneWithItsFirstFeelings() {
        assertThat(Seed.text()).startsWith("【世界名】诛仙\n【世界·公开】\n诛仙 · 世界概要");
        Draft seed = Markup.parseDraft(Seed.text(), true, List.of());
        assertThat(seed.characters()).extracting(Markup.DraftCharacter::name).containsExactly("陆雪琪", "张小凡", "碧瑶");
        assertThat(seed.characters()).allSatisfy(c -> assertThat(c.views()).hasSize(2));
    }
}
