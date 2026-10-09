package app.lyng.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.markup.Markup;
import app.lyng.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 费用硬上限：每次调用前按 model_calls 累计的实际费用检查，达到就停，不再发出请求。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=optional:file:./does-not-exist",
            "lyng.model.backend=fake",
            "lyng.model.backoff-ms=0",
            "lyng.auth.password=test-password",
            "lyng.model.budget-usd=0.00025"
        })
class BudgetTest extends IntegrationTest {

    @Autowired
    ModelGateway gateway;

    @Test
    void stopsOnceTheCumulativeCostReachesTheLimit() {
        UUID userId = jdbc.sql("SELECT id FROM users WHERE name = 'me'").query(UUID.class).single();
        for (int i = 0; i < 3; i++) { // 每次 0.0001：第三次之后累计 0.0003 ≥ 上限
            gateway.call(userId, ModelRole.NARRATOR, new CallPurpose("opening", null, null),
                    List.of(ChatMessage.user("x")), () -> Attempt.whole(Markup::parseNarration));
        }
        model.clearRequests();

        assertThatThrownBy(() -> gateway.call(userId, ModelRole.NARRATOR, new CallPurpose("opening", null, null),
                List.of(ChatMessage.user("x")), () -> Attempt.whole(Markup::parseNarration)))
                .isInstanceOfSatisfying(CallFailure.class, f -> {
                    assertThat(f.kind()).isEqualTo(CallFailure.Kind.STOPPED);
                    assertThat(f.getMessage()).contains("已达费用上限");
                });
        assertThat(model.requests()).isEmpty();
    }
}
