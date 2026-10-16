package app.lyng.mind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.model.FormatError;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 私密交接严格解析：整份对才收，不修补。 */
class DecisionTest {

    private static final UUID XF = UUID.randomUUID();

    @Test
    void aCompleteHandoff() {
        Decision d = Decision.parse("""
                {"publicIntent":"说明天去。","innerVoice":"他还记得。","changes":{
                  "views":[{"personId":"%s","understanding":"他没变。"}],
                  "concerns":[{"cue":"渡口","content":"明天去渡口。","active":true,"resolved":false}],
                  "memories":[{"cue":"约定","content":"他约我去渡口。","permanent":false,"sources":[12,"13"]}],
                  "developments":[{"earlierId":3,"laterId":"9","correction":true}]}}""".formatted(XF));
        assertThat(d.silent()).isFalse();
        assertThat(d.innerVoice()).isEqualTo("他还记得。");
        assertThat(d.changes().views()).containsExactly(new Decision.ViewChange(XF, "他没变。"));
        assertThat(d.changes().concerns().getFirst().status()).isEqualTo("active");
        assertThat(d.changes().memories().getFirst().sources()).containsExactly(12L, 13L);
        assertThat(d.changes().developments()).containsExactly(new Decision.Link(3, 9, true));
    }

    @Test
    void silenceWithoutChanges() {
        Decision d = Decision.parse("{\"silenceReason\":\"不想回。\",\"innerVoice\":\"随他去。\"}");
        assertThat(d.silent()).isTrue();
        assertThat(d.changes().empty()).isTrue();
    }

    @Test
    void oneWrappingCodeBlockIsTolerated() {
        assertThat(Decision.parse("```json\n{\"publicIntent\":\"在。\",\"innerVoice\":\"嗯。\"}\n```").publicIntent())
                .isEqualTo("在。");
    }

    @Test
    void anythingElseThatIsOffRejectsTheWholeHandoff() {
        for (String bad : List.of(
                "{\"publicIntent\":\"在。\",\"innerVoice\":\"嗯。\"} 好的",
                "好的，{\"publicIntent\":\"在。\",\"innerVoice\":\"嗯。\"}",
                "{\"publicIntent\":\"在。\"}",
                "{\"publicIntent\":\"在。\",\"silenceReason\":\"不想\",\"innerVoice\":\"嗯。\"}",
                "{\"innerVoice\":\"嗯。\"}",
                "{\"publicIntent\":\"在。\",\"innerVoice\":\"嗯。\",\"mood\":\"好\"}",
                "{\"publicIntent\":\"在。\",\"innerVoice\":\"嗯。\",\"developments\":[]}",
                "{\"publicIntent\":\"在。\",\"innerVoice\":\"嗯。\",\"changes\":{\"concerns\":[{\"cue\":\"a\",\"content\":\"b\",\"active\":true,\"resolved\":true}]}}",
                "{\"publicIntent\":\"在。\",\"innerVoice\":\"嗯。\",\"changes\":{\"concerns\":[{\"cue\":\"a\",\"content\":\"b\",\"active\":\"true\",\"resolved\":false}]}}",
                "{\"publicIntent\":\"在。\",\"innerVoice\":\"嗯。\",\"changes\":{\"views\":[{\"personId\":\"\",\"understanding\":\"b\"}]}}",
                "{\"publicIntent\":\"在。\",\"innerVoice\":\"嗯。\",\"changes\":{\"memories\":[{\"cue\":\"a\",\"content\":\"b\",\"permanent\":false,\"sources\":[\"第三条\"]}]}}",
                "{\"publicIntent\":\"在。\",\"innerVoice\":\"嗯。\",\"changes\":{\"developments\":[{\"earlierId\":3,\"laterId\":3}]}}")) {
            assertThatThrownBy(() -> Decision.parse(bad)).as(bad).isInstanceOf(FormatError.class);
        }
    }
}
