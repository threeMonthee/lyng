package app.lyng.model;

import static app.lyng.support.ScriptedChatModel.Reply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.markup.Markup;
import app.lyng.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ModelGatewayTest extends IntegrationTest {

    @Autowired
    ModelGateway gateway;

    @Autowired
    ModelSettings settings;

    UUID userId;

    @BeforeEach
    void user() {
        userId = jdbc.sql("SELECT id FROM users WHERE name = 'me'").query(UUID.class).single();
    }

    private String narration() {
        return gateway.call(userId, ModelRole.NARRATOR, new CallPurpose("opening", null, null),
                List.of(ChatMessage.user("写开场")), () -> Attempt.whole(Markup::parseNarration));
    }

    @Test
    void retriesOnceAfterATransientFailureAndRecordsBothAttempts() {
        int[] calls = {0};
        model.script(request -> calls[0]++ == 0 ? Reply.fail(ModelException.Kind.TRANSIENT, 429) : Reply.text("雨停了。"));

        assertThat(narration()).isEqualTo("雨停了。");

        assertThat(jdbc.sql("SELECT outcome FROM model_calls ORDER BY id").query(String.class).list()).containsExactly("error", "ok");
        assertThat(jdbc.sql("SELECT error FROM model_calls ORDER BY id LIMIT 1").query(String.class).single()).isEqualTo("请求失败：HTTP 429");
    }

    @Test
    void failsAfterTheSecondFailureAndSaysItRetried() {
        model.script(request -> Reply.text("  "));
        assertThatThrownBy(this::narration).isInstanceOfSatisfying(CallFailure.class, f -> {
            assertThat(f.kind()).isEqualTo(CallFailure.Kind.TECHNICAL);
            assertThat(f.getMessage()).isEqualTo("空响应（已重试一次）");
        });
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void truncationAndBadFormatAreTechnicalFailures() {
        model.script(request -> Reply.truncated("雨停了"));
        assertThatThrownBy(this::narration).hasMessageContaining("截断");
        model.script(request -> Reply.text("叙事：雨停了。"));
        assertThatThrownBy(this::narration).hasMessageContaining("格式不合规");
    }

    @Test
    void refusedAndFatalErrorsAreNotRetried() {
        model.script(request -> Reply.fail(ModelException.Kind.REFUSED, 403));
        assertThatThrownBy(this::narration).isInstanceOfSatisfying(CallFailure.class, f -> assertThat(f.kind()).isEqualTo(CallFailure.Kind.REFUSED));
        model.clearRequests();
        model.script(request -> Reply.fail(ModelException.Kind.FATAL, 401));
        assertThatThrownBy(this::narration).isInstanceOfSatisfying(CallFailure.class, f -> assertThat(f.kind()).isEqualTo(CallFailure.Kind.STOPPED));
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void usesTheModelChosenInSettingsForEachRole() {
        settings.save(userId, ModelRole.NARRATOR, "other/model@off");
        narration();
        ChatRequest sent = model.requests().getFirst();
        assertThat(sent.choice()).isEqualTo(new ModelChoice("other/model", "off"));
        assertThat(settings.choice(userId, ModelRole.CHARACTER).model()).isEqualTo("deepseek/deepseek-v4.1-flash");

        settings.save(userId, ModelRole.NARRATOR, "");
        assertThat(settings.choice(userId, ModelRole.NARRATOR).model()).isEqualTo("deepseek/deepseek-v4.1-flash");
        assertThatThrownBy(() -> settings.save(userId, ModelRole.NARRATOR, "x/y@turbo")).isInstanceOf(IllegalArgumentException.class);
    }
}
