package app.lyng.markup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.model.FormatError;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TurnStreamParserTest {

    private static final String LU = "陆雪琪";
    private static final String FULL =
            "陆雪琪（行）：她转过身。\n陆雪琪（说）：你来了。\n第二句。\n陆雪琪（行）：她没有看他。\n陆雪琪（心声）：终于。";

    private static List<Beat> stream(String text, int chunk, List<Beat> out) {
        TurnStreamParser parser = new TurnStreamParser(LU, out::add);
        for (int i = 0; i < text.length(); i += chunk) {
            parser.feed(text.substring(i, Math.min(text.length(), i + chunk)));
        }
        parser.finish(text);
        return out;
    }

    @Test
    void emitsEachBubbleOnceWhateverTheChunking() {
        List<Beat> expected = Markup.parseTurn(FULL, LU).beats();
        for (int chunk : new int[] {1, 2, 3, 5, 7, 13, FULL.length()}) {
            assertThat(stream(FULL, chunk, new ArrayList<>())).as("chunk=" + chunk).isEqualTo(expected);
        }
    }

    @Test
    void emitsEachBubbleOnceUnderRandomChunking() {
        List<Beat> expected = Markup.parseTurn(FULL, LU).beats();
        Random random = new Random(7);
        for (int round = 0; round < 200; round++) {
            List<Beat> out = new ArrayList<>();
            TurnStreamParser parser = new TurnStreamParser(LU, out::add);
            for (int i = 0; i < FULL.length(); ) {
                int next = Math.min(FULL.length(), i + 1 + random.nextInt(9));
                parser.feed(FULL.substring(i, next));
                i = next;
            }
            parser.finish(FULL);
            assertThat(out).isEqualTo(expected);
        }
    }

    @Test
    void aBubbleIsPushedAsSoonAsTheNextLabelAppears() {
        List<Beat> out = new ArrayList<>();
        TurnStreamParser parser = new TurnStreamParser(LU, out::add);
        parser.feed("陆雪琪（说）：你来了。\n陆雪琪（行）：她");
        assertThat(out).containsExactly(new Beat("说", "你来了。"));
        parser.feed("转过身。\n陆雪琪（心声）：嗯。");
        assertThat(out).containsExactly(new Beat("说", "你来了。"), new Beat("行", "她转过身。"));
    }

    @Test
    void innerVoiceOnlyEmitsNoBubble() {
        assertThat(stream("陆雪琪（心声）：随他去。", 3, new ArrayList<>())).isEmpty();
    }

    @Test
    void anotherPersonsLineAbortsBeforeItIsPushed() {
        List<Beat> out = new ArrayList<>();
        TurnStreamParser parser = new TurnStreamParser(LU, out::add);
        parser.feed("陆雪琪（说）：你说。\n");
        assertThatThrownBy(() -> parser.feed("碧瑶（说）：我偏不。"))
                .isInstanceOf(FormatError.class).hasMessageContaining("写了别人的言行：碧瑶");
        assertThat(out).isEmpty(); // 别人的那一行没有放行，也没有因为它冲出前一个气泡
    }

    @Test
    void textBeforeTheFirstLabelAborts() {
        TurnStreamParser parser = new TurnStreamParser(LU, b -> {});
        assertThatThrownBy(() -> parser.feed("陆雪琪看了他一眼，没有说话。\n")).isInstanceOf(FormatError.class);
    }

    @Test
    void anythingAfterTheInnerVoiceAborts() {
        TurnStreamParser parser = new TurnStreamParser(LU, b -> {});
        parser.feed("陆雪琪（心声）：嗯。\n");
        assertThatThrownBy(() -> parser.feed("陆雪琪（说）：你好。\n")).isInstanceOf(FormatError.class);
    }

    @Test
    void finishRejectsMissingInnerVoice() {
        TurnStreamParser parser = new TurnStreamParser(LU, b -> {});
        String text = "陆雪琪（说）：你好。";
        parser.feed(text);
        assertThatThrownBy(() -> parser.finish(text)).isInstanceOf(FormatError.class);
    }
}
