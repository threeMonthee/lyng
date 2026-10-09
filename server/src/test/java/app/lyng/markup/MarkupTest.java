package app.lyng.markup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.model.FormatError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 从验证原型的 test_dataset_and_markup 移植。 */
class MarkupTest {

    private static final String LU = "陆雪琪";

    @Test
    void turnKeepsActionsAndSpeechInOrder() {
        Turn t = Markup.parseTurn("陆雪琪（行）：她转过身。\n陆雪琪（说）：你来了。\n陆雪琪（行）：她没有看他。\n陆雪琪（心声）：终于。", LU);
        assertThat(t.beats()).containsExactly(new Beat("行", "她转过身。"), new Beat("说", "你来了。"), new Beat("行", "她没有看他。"));
        assertThat(t.inner()).isEqualTo("终于。");
        assertThat(t.silent()).isFalse();
    }

    @Test
    void innerVoiceOnlyMeansSilence() {
        Turn t = Markup.parseTurn("陆雪琪（心声）：随他去。", LU);
        assertThat(t.silent()).isTrue();
        assertThat(t.beats()).isEmpty();
    }

    @Test
    void aBlockMayRunOverSeveralLines() {
        Turn t = Markup.parseTurn("陆雪琪（说）：第一句。\n第二句。\n陆雪琪（心声）：嗯。", LU);
        assertThat(t.beats()).containsExactly(new Beat("说", "第一句。\n第二句。"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "前言\n陆雪琪（说）：你好。\n陆雪琪（心声）：嗯。",
            "陆雪琪（说）：你好。",
            "陆雪琪（心声）：嗯。\n陆雪琪（说）：你好。",
            "陆雪琪（说）：你好。\n陆雪琪（心声）：嗯。\n陆雪琪（心声）：又一句。",
            "陆雪琪（心声）：",
            "陆雪琪（想）：你好。\n陆雪琪（心声）：嗯。",
            "陆雪琪（说）：\n陆雪琪（心声）：嗯。",
            "【说】你好。\n【心声】嗯。",
            "叙事：雨停了。\n陆雪琪（心声）：嗯。"})
    void turnRejectsMalformedOutput(String bad) {
        assertThatThrownBy(() -> Markup.parseTurn(bad, LU)).isInstanceOf(FormatError.class);
    }

    @Test
    void turnRejectsOtherPeoplesLines() {
        assertThatThrownBy(() -> Markup.parseTurn("陆雪琪（说）：你说。\n碧瑶（说）：我偏不。\n陆雪琪（心声）：嗯。", LU))
                .isInstanceOf(FormatError.class).hasMessageContaining("写了别人的言行：碧瑶");
    }

    @Test
    void narrationIsPlainTextWithoutLabels() {
        assertThat(Markup.parseNarration("  雨停了。  ")).isEqualTo("雨停了。");
        assertThatThrownBy(() -> Markup.parseNarration("叙事：雨停了。")).isInstanceOf(FormatError.class);
        assertThatThrownBy(() -> Markup.parseNarration("   ")).isInstanceOf(FormatError.class);
    }

    @Test
    void pairRound() {
        assertThat(Markup.parsePairRound("叙事：无")).isEmpty();
        assertThat(Markup.parsePairRound("叙事：无。")).as("模型常把无写成无。").isEmpty();
        assertThat(Markup.parsePairRound("叙事：无 ")).isEmpty();
        assertThat(Markup.parsePairRound("叙事：无人应门。")).isEqualTo("无人应门。");
        assertThat(Markup.parsePairRound("叙事：雪停了。")).isEqualTo("雪停了。");
        for (String bad : List.of("", "无", "叙事：", "叙事：无\n轮到：陆雪琪", "叙事：无\n叙事：雪停了。", "雪停了。")) {
            assertThatThrownBy(() -> Markup.parsePairRound(bad)).as(bad).isInstanceOf(FormatError.class);
        }
    }

    @Test
    void groupRound() {
        List<String> candidates = List.of("陆雪琪", "碧瑶");
        assertThat(Markup.parseRound("叙事：无\n轮到：陆雪琪、碧瑶", candidates, false, null))
                .isEqualTo(new Markup.Round(null, List.of("陆雪琪", "碧瑶")));
        assertThat(Markup.parseRound("叙事：雨停了。\n轮到：无", candidates, true, null))
                .isEqualTo(new Markup.Round("雨停了。", List.of()));
        assertThat(Markup.parseRound("轮到：碧瑶。\n叙事：无", candidates, false, null).reactors()).containsExactly("碧瑶");
        for (String bad : List.of("叙事：无\n轮到：张小凡", "叙事：雨。", "轮到：陆雪琪", "叙事：无\n轮到：陆雪琪、陆雪琪",
                "叙事：\n轮到：无", "一、不需要。\n二、陆雪琪", "叙事：无\n陆雪琪（行）：她抬眼。\n轮到：陆雪琪",
                "叙事：无\n轮到：陆雪琪脚步一顿，没有回头", "叙事：无\n反应：陆雪琪")) {
            assertThatThrownBy(() -> Markup.parseRound(bad, candidates, false, null)).as(bad).isInstanceOf(FormatError.class);
        }
        assertThatThrownBy(() -> Markup.parseRound("叙事：无\n轮到：无", candidates, true, null)).isInstanceOf(FormatError.class);
    }

    /** 2026-10-09 裁定：群戏调度里排除附身人物，解析时也拒绝，并说明原因。 */
    @Test
    void groupRoundRejectsThePossessedCharacter() {
        List<String> candidates = List.of("陆雪琪", "碧瑶");
        assertThatThrownBy(() -> Markup.parseRound("叙事：无\n轮到：张小凡、碧瑶", candidates, false, "张小凡"))
                .isInstanceOf(FormatError.class).hasMessageContaining("张小凡是被附身的人物");
        assertThat(Markup.parseRound("叙事：无\n轮到：碧瑶", candidates, false, "张小凡").reactors()).containsExactly("碧瑶");
    }

    @Test
    void recall() {
        Markup.Recall r = Markup.parseRecall("回想：我们说了很多。\n第二句。\n看待张小凡：他变了。", List.of("张小凡"));
        assertThat(r.memory()).isEqualTo("我们说了很多。\n第二句。");
        assertThat(r.views()).isEqualTo(Map.of("张小凡", "他变了。"));
        for (String bad : List.of("", "看待张小凡：他变了。", "回想：好。", "回想：好。\n看待路人：谁。\n看待张小凡：他。",
                "回想：好。\n回想：又。\n看待张小凡：他。", "闲话\n回想：好。\n看待张小凡：他。", "回想：\n看待张小凡：他。")) {
            assertThatThrownBy(() -> Markup.parseRecall(bad, List.of("张小凡"))).as(bad).isInstanceOf(FormatError.class);
        }
    }

    @Test
    void draftForNewWorld() {
        Draft d = Markup.parseDraft("""
                【世界名】诛仙
                【世界·公开】
                青云门在青云山。
                【世界·只有叙事者知道】
                碧瑶醒了。
                【人物】陆雪琪
                【小传】
                她是小竹峰的弟子。
                【人物】碧瑶
                【小传】
                她是鬼王的独女。
                """, true);
        assertThat(d.world().name()).isEqualTo("诛仙");
        assertThat(d.world().publicText()).isEqualTo("青云门在青云山。");
        assertThat(d.world().narratorText()).isEqualTo("碧瑶醒了。");
        assertThat(d.characters()).extracting(Draft.CharacterDraft::name).containsExactly("陆雪琪", "碧瑶");
        assertThat(d.characters().getFirst().profile()).isEqualTo("【小传】\n她是小竹峰的弟子。");
    }

    @Test
    void draftForExistingWorldHasOnlyCharacters() {
        Draft d = Markup.parseDraft("【人物】张小凡\n【小传】\n他是大竹峰的弟子。", false);
        assertThat(d.world()).isNull();
        assertThat(d.characters()).hasSize(1);
        assertThatThrownBy(() -> Markup.parseDraft("【世界名】x\n【人物】张小凡\n档案", false)).isInstanceOf(FormatError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "前言\n【世界名】x\n【世界·公开】a\n【世界·只有叙事者知道】b\n【人物】甲\n档案",
            "【世界名】x\n【世界·公开】a\n【人物】甲\n档案",
            "【世界名】x\n【世界·公开】a\n【世界·只有叙事者知道】b",
            "【世界名】x\n【世界·公开】a\n【世界·只有叙事者知道】b\n【人物】甲\n",
            "【世界名】x\n【世界·公开】a\n【世界·只有叙事者知道】b\n【人物】甲\n档案\n【人物】甲\n档案",
            "【世界名】x\n【世界·公开】a\n【世界·只有叙事者知道】b\n【人物】名字太长太长太长太长太长\n档案"})
    void draftRejectsMalformedOutput(String bad) {
        assertThatThrownBy(() -> Markup.parseDraft(bad, true)).isInstanceOf(FormatError.class);
    }
}
