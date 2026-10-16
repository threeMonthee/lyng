package app.lyng.mind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.mind.Markup.Beat;
import app.lyng.mind.Markup.Consolidation;
import app.lyng.mind.Markup.Draft;
import app.lyng.mind.Markup.Round;
import app.lyng.mind.Markup.Turn;
import app.lyng.model.FormatError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkupTest {

    // ---- 人物 ----

    @Test
    void aTurnKeepsActionsAndWordsInOrderAndTheInnerVoiceLast() {
        Turn turn = Markup.parseTurn("""
                陆雪琪（行）：陆雪琪没有回头。
                陆雪琪（说）：你来做什么。
                陆雪琪（行）：她握紧了剑柄，
                指节发白。
                陆雪琪（心声）：他瘦了。""", "陆雪琪", true);
        assertThat(turn.beats()).containsExactly(new Beat("行", "陆雪琪没有回头。"), new Beat("说", "你来做什么。"),
                new Beat("行", "她握紧了剑柄，\n指节发白。"));
        assertThat(turn.inner()).isEqualTo("他瘦了。");
        assertThat(turn.silent()).isFalse();
    }

    @Test
    void onlyAnInnerVoiceMeansNeitherSpeakingNorActing() {
        Turn turn = Markup.parseTurn("陆雪琪（心声）：不想说话。", "陆雪琪", true);
        assertThat(turn.beats()).isEmpty();
        assertThat(turn.silent()).isTrue();
    }

    @Test
    void aTurnWithoutTheInnerVoiceLastIsRejected() {
        assertThatThrownBy(() -> Markup.parseTurn("陆雪琪（说）：走。", "陆雪琪", true)).isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseTurn("陆雪琪（心声）：嗯。\n陆雪琪（说）：走。", "陆雪琪", true))
                .isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseTurn("陆雪琪（心声）：", "陆雪琪", true)).isInstanceOf(FormatError.class);
    }

    @Test
    void writingSomeoneElsesLinesOrFreeTextIsRejected() {
        assertThatThrownBy(() -> Markup.parseTurn("张小凡（说）：雪琪。\n陆雪琪（心声）：嗯。", "陆雪琪", true))
                .isInstanceOf(FormatError.class).hasMessageContaining("张小凡");
        assertThatThrownBy(() -> Markup.parseTurn("好的，我来写。\n陆雪琪（心声）：嗯。", "陆雪琪", true))
                .isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseTurn("叙事：起风了。\n陆雪琪（心声）：嗯。", "陆雪琪", true))
                .isInstanceOf(FormatError.class);
    }

    @Test
    void aPrivateChatHasNoActions() {
        assertThat(Markup.parseTurn("陆雪琪（说）：在。\n陆雪琪（说）：何事。\n陆雪琪（心声）：他终于来了消息。", "陆雪琪", false)
                .beats()).hasSize(2);
        assertThatThrownBy(() -> Markup.parseTurn("陆雪琪（行）：放下书。\n陆雪琪（心声）：嗯。", "陆雪琪", false))
                .isInstanceOf(FormatError.class);
    }

    // ---- 叙事者 ----

    @Test
    void openingAndClosingArePlainText() {
        assertThat(Markup.parseNarration("  河阳城，入夜。\n")).isEqualTo("河阳城，入夜。");
        assertThatThrownBy(() -> Markup.parseNarration("叙事：河阳城，入夜。")).isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseNarration(" ")).isInstanceOf(FormatError.class);
    }

    @Test
    void aPairRoundIsOneNarrationLine() {
        assertThat(Markup.parsePairRound("叙事：无。")).isEqualTo(new Round(null, List.of()));
        assertThat(Markup.parsePairRound("叙事：远处传来钟声。").narration()).isEqualTo("远处传来钟声。");
        assertThatThrownBy(() -> Markup.parsePairRound("叙事：无\n轮到：陆雪琪")).isInstanceOf(FormatError.class);
    }

    @Test
    void aGroupRoundNamesWhoReactsInOrder() {
        List<String> candidates = List.of("陆雪琪", "碧瑶");
        assertThat(Markup.parseGroupRound("叙事：无\n轮到：碧瑶、陆雪琪。", candidates, false, "张小凡"))
                .isEqualTo(new Round(null, List.of("碧瑶", "陆雪琪")));
        assertThat(Markup.parseGroupRound("叙事：起风了。\n轮到：无", candidates, false, "张小凡"))
                .isEqualTo(new Round("起风了。", List.of()));
    }

    @Test
    void aGroupRoundNeverNamesThePossessedOrStrangers() {
        List<String> candidates = List.of("陆雪琪", "碧瑶");
        assertThatThrownBy(() -> Markup.parseGroupRound("叙事：无\n轮到：张小凡", candidates, false, "张小凡"))
                .isInstanceOf(FormatError.class).hasMessageContaining("附身");
        assertThatThrownBy(() -> Markup.parseGroupRound("叙事：无\n轮到：鬼王", candidates, false, "张小凡"))
                .isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseGroupRound("叙事：无\n轮到：碧瑶、碧瑶", candidates, false, null))
                .isInstanceOf(FormatError.class);
    }

    @Test
    void continuingWhileObservingMustMoveForward() {
        assertThatThrownBy(() -> Markup.parseGroupRound("叙事：无\n轮到：无", List.of("陆雪琪", "碧瑶"), true, null))
                .isInstanceOf(FormatError.class);
    }

    @Test
    void consentIsOneVerdictLine() {
        assertThat(Markup.parseConsentRefused("判定：拒绝")).isTrue();
        assertThat(Markup.parseConsentRefused("判定：可以。")).isFalse();
        assertThatThrownBy(() -> Markup.parseConsentRefused("可以")).isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseConsentRefused("判定：也许")).isInstanceOf(FormatError.class);
    }

    // ---- 整理内心 ----

    @Test
    void aConsolidationCanTouchEveryLayer() {
        Consolidation c = Markup.parseConsolidation("""
                【记忆】他把骨铃放在石桌上，说不是他。
                我没有躲开他的眼睛。
                【意义】我错怪了他。
                【看法·张小凡】他还是那个人。我信他。
                【牵挂·张小凡】欠他一句对不起。
                【牵挂】回山后要向师父禀明此事。
                【了结·1】他亲口说了，我信了。
                【放下·2】
                【改·3】等他把话说完。
                【自我】我比自己以为的更愿意相信他。""", List.of("张小凡", "碧瑶"), 3);
        assertThat(c.memories()).singleElement().satisfies(m -> {
            assertThat(m.event()).isEqualTo("他把骨铃放在石桌上，说不是他。\n我没有躲开他的眼睛。");
            assertThat(m.meaning()).isEqualTo("我错怪了他。");
        });
        assertThat(c.views()).containsEntry("张小凡", "他还是那个人。我信他。");
        assertThat(c.concerns()).extracting(Markup.NewConcern::about).containsExactly("张小凡", null);
        assertThat(c.resolved()).containsEntry(1, "他亲口说了，我信了。");
        assertThat(c.background()).containsExactly(2);
        assertThat(c.revised()).containsEntry(3, "等他把话说完。");
        assertThat(c.self()).isEqualTo("我比自己以为的更愿意相信他。");
    }

    @Test
    void nothingToKeepIsAValidConsolidation() {
        assertThat(Markup.parseConsolidation("【无】", List.of("张小凡"), 0).empty()).isTrue();
    }

    @Test
    void aConsolidationOnlyTouchesPeopleAndConcernsThatExist() {
        assertThatThrownBy(() -> Markup.parseConsolidation("【看法·鬼王】可怕。", List.of("张小凡"), 0))
                .isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseConsolidation("【了结·2】好了。", List.of("张小凡"), 1))
                .isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseConsolidation("【记忆】他来了。", List.of("张小凡"), 0))
                .isInstanceOf(FormatError.class).hasMessageContaining("意义");
        assertThatThrownBy(() -> Markup.parseConsolidation("【记忆】他来了。\n【意义】我高兴。\n【无】", List.of(), 0))
                .isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseConsolidation("我想了想。\n【无】", List.of(), 0))
                .isInstanceOf(FormatError.class);
    }

    // ---- 一念生 ----

    @Test
    void questionsComeWithOptionsAndARecommendation() {
        var questions = Markup.parseQuestions("""
                【问题】陆雪琪从哪个时期开始？
                【选项】青云大试前｜诛仙剑阵后 | 十年后
                【推荐】诛仙剑阵后""");
        assertThat(questions).singleElement().satisfies(q -> {
            assertThat(q.options()).containsExactly("青云大试前", "诛仙剑阵后", "十年后");
            assertThat(q.recommended()).isEqualTo("诛仙剑阵后");
        });
        assertThat(Markup.parseQuestions("【无】")).isEmpty();
        assertThatThrownBy(() -> Markup.parseQuestions("【问题】哪个时期？\n【选项】早｜晚\n【推荐】中"))
                .isInstanceOf(FormatError.class);
    }

    @Test
    void aDraftForANewWorldHasTheWorldAndEveryCharacter() {
        Draft d = Markup.parseDraft("""
                【世界名】小城
                【世界·公开】
                临水的小城。
                【世界·只有叙事者知道】
                书院的主人没有走。
                【人物】沈知微
                【小传】
                在城里长大。
                【此刻】二十四岁。
                【看法·顾长川】信得过。
                【牵挂·顾长川】上次的话没说完。
                【人物】顾长川
                【小传】渡口的船家。
                【牵挂】船要修了。""", true, List.of());
        assertThat(d.world().name()).isEqualTo("小城");
        assertThat(d.characters()).extracting(Markup.DraftCharacter::name).containsExactly("沈知微", "顾长川");
        assertThat(d.characters().getFirst().profile()).isEqualTo("【小传】\n在城里长大。\n【此刻】二十四岁。");
        assertThat(d.characters().getFirst().views()).singleElement().satisfies(v -> assertThat(v.name()).isEqualTo("顾长川"));
        assertThat(d.characters().get(1).concerns()).singleElement().satisfies(c -> assertThat(c.about()).isNull());
    }

    @Test
    void aDraftInAnExistingWorldMayLookAtPeopleAlreadyThere() {
        Draft d = Markup.parseDraft("【人物】曾书书\n【小传】风回峰的弟子。\n【看法·张小凡】老朋友。", false, List.of("张小凡"));
        assertThat(d.world()).isNull();
        assertThatThrownBy(() -> Markup.parseDraft("【世界名】又一个\n【人物】曾书书\n【小传】弟子。", false, List.of()))
                .isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseDraft("【人物】曾书书\n【小传】弟子。\n【看法·鬼王】可怕。", false, List.of("张小凡")))
                .isInstanceOf(FormatError.class);
    }

    @Test
    void draftNamesMustBeUsableAndProfilesPresent() {
        assertThatThrownBy(() -> Markup.parseDraft("【人物】曾 书书\n【小传】弟子。", false, List.of()))
                .isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseDraft("【人物】曾书书\n【人物】曾书书\n【小传】弟子。", false, List.of()))
                .isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseDraft("【人物】曾书书", false, List.of())).isInstanceOf(FormatError.class);
    }

    @Test
    void conflictsComeInPairs() {
        assertThat(Markup.parseConflicts("【冲突】年纪对不上。\n【建议】改成二十七岁。")).singleElement()
                .satisfies(c -> assertThat(c.suggestion()).isEqualTo("改成二十七岁。"));
        assertThat(Markup.parseConflicts("【无】")).isEmpty();
        assertThatThrownBy(() -> Markup.parseConflicts("【冲突】年纪对不上。")).isInstanceOf(FormatError.class);
    }

    // ---- 流式切段 ----

    @Test
    void theStreamHandsOverEachBeatOnceItIsCompleteAndNeverTheInnerVoice() {
        List<Beat> beats = new ArrayList<>();
        Markup.TurnStream stream = new Markup.TurnStream("碧瑶", true, beats::add);
        String text = "碧瑶（行）：碧瑶挽住了他的胳膊。\n碧瑶（说）：哟，\n这不是陆师姐么？\n碧瑶（心声）：她来做什么。";
        int handedBeforeTheSecondLabel = -1;
        for (int i = 0; i < text.length(); i++) {
            stream.feed(text.substring(i, i + 1));
            if (text.startsWith("碧瑶（说）：", i + 1 - "碧瑶（说）：".length()) && handedBeforeTheSecondLabel < 0) {
                handedBeforeTheSecondLabel = beats.size();
            }
        }
        assertThat(handedBeforeTheSecondLabel).isEqualTo(1);
        assertThat(beats).containsExactly(new Beat("行", "碧瑶挽住了他的胳膊。"), new Beat("说", "哟，\n这不是陆师姐么？"));
    }

    @Test
    void theStreamStopsEarlyWhenTheModelWritesSomeoneElse() {
        Markup.TurnStream stream = new Markup.TurnStream("碧瑶", true, b -> {});
        assertThatThrownBy(() -> stream.feed("碧瑶（说）：你好。\n陆雪琪（说）：")).isInstanceOf(FormatError.class)
                .hasMessageContaining("陆雪琪");
    }
}
