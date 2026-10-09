package app.lyng.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import app.lyng.event.SceneEvent;
import app.lyng.event.Visibility;
import app.lyng.markup.Beat;
import app.lyng.markup.Markup;
import app.lyng.markup.Turn;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 可见性规则（从验证原型的 test_visibility 移植）：人物只感知场上公开发生的事和自己的心声。 */
class PerceptionTest {

    private static final UUID LU = UUID.randomUUID();
    private static final UUID BI = UUID.randomUUID();

    private static final List<SceneEvent> EVENTS = List.of(
            SceneEvent.narration(0, "长街上人来人往。"),
            SceneEvent.user(1, UUID.randomUUID(), "张小凡", "静候", ""),
            SceneEvent.character(1, BI, "碧瑶", List.of(new Beat("行", "碧瑶挽住了张小凡的胳膊。"), new Beat("说", "哟，这不是陆师姐么？")),
                    "碧瑶的秘密心声", false),
            SceneEvent.character(1, LU, "陆雪琪", List.of(new Beat("行", "陆雪琪停住了脚步。")), "陆雪琪的秘密心声", false),
            SceneEvent.user(2, UUID.randomUUID(), "张小凡", "说", "雪琪。"),
            SceneEvent.character(2, LU, "陆雪琪", List.of(), "陆雪琪第二句心声", true),
            SceneEvent.failure(2, "character", BI, "碧瑶", "空响应（已重试一次）"),
            SceneEvent.resolved(2, 7));

    @Test
    void aCharacterSeesOwnInnerVoiceButNotOthers() {
        String lu = Perception.of(EVENTS, "陆雪琪");
        assertThat(lu).contains("陆雪琪（心声）：陆雪琪的秘密心声").doesNotContain("碧瑶的秘密心声");
        assertThat(lu).contains("碧瑶（说）：哟，这不是陆师姐么？");
        assertThat(Perception.of(EVENTS, "碧瑶")).contains("碧瑶的秘密心声").doesNotContain("陆雪琪的秘密心声");
    }

    @Test
    void theNarratorSeesNoInnerVoice() {
        assertThat(Perception.forNarrator(EVENTS)).doesNotContain("心声");
    }

    @Test
    void technicalFailureIsInvisibleAndNotSilence() {
        String text = Perception.of(EVENTS, "陆雪琪");
        assertThat(text).doesNotContain("技术失败").doesNotContain("空响应").doesNotContain("碧瑶没有开口");
        assertThat(Visibility.publicEvents(EVENTS)).hasSize(6);
    }

    @Test
    void waitingAndSilenceAreWrittenAsNotSpeaking() {
        String text = Perception.of(EVENTS, "陆雪琪");
        assertThat(text).contains("张小凡没有开口。").contains("陆雪琪没有开口。");
    }

    @Test
    void theOutputReadsBackAsTheSamePerception() {
        String text = "陆雪琪（行）：她把剑收回鞘中。\n陆雪琪（说）：你走吧。\n陆雪琪（心声）：别走。";
        Turn turn = Markup.parseTurn(text, "陆雪琪");
        SceneEvent event = SceneEvent.character(1, LU, "陆雪琪", turn.beats(), turn.inner(), turn.silent());
        assertThat(Perception.of(List.of(event), "陆雪琪")).isEqualTo(text);
    }

    @Test
    void emptySceneHasAPlaceholder() {
        assertThat(Perception.of(List.of(), "陆雪琪")).isEqualTo("（还没有发生任何事）");
    }
}
