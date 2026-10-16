package app.lyng.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.mind.Markup;
import app.lyng.store.Runs;
import app.lyng.store.Settings;
import app.lyng.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GatewayTest extends IntegrationTest {

    @Autowired
    Gateway gateway;
    @Autowired
    Runs runs;
    @Autowired
    Settings settings;

    private Gateway.Spec spec(Purpose purpose) {
        return Gateway.Spec.of(purpose, "测试", Gateway.Links.NONE, List.of(Msg.user("开场")), Map.of());
    }

    @Test
    void aFormatErrorIsRetriedOnceAndBothAttemptsAreRecorded() {
        model.reply(Purpose.OPENING, "叙事：带了标签", "河阳城，入夜。");
        var result = gateway.run(spec(Purpose.OPENING), Markup::parseNarration);
        assertThat(result.value()).isEqualTo("河阳城，入夜。");
        var detail = runs.detail(result.runId()).orElseThrow();
        assertThat(detail.status()).isEqualTo("ok");
        assertThat(detail.attempts()).isEqualTo(2);
        assertThat(detail.reasoning()).contains("（思考）");
        assertThat(runs.calls(result.runId())).extracting(Runs.Call::outcome).containsExactly("error", "ok");
        assertThat(runs.calls(result.runId()).getFirst().error()).contains("格式不合规");
    }

    @Test
    void twoFailuresEndTheRunAndSaySoHonestly() {
        model.truncated(Purpose.OPENING, "河阳城，").fail(Purpose.OPENING, ModelException.Kind.TRANSIENT, "429");
        assertThatThrownBy(() -> gateway.run(spec(Purpose.OPENING), Markup::parseNarration))
                .isInstanceOfSatisfying(CallFailure.class, e -> {
                    assertThat(e.getMessage()).contains("429").contains("已自动重试一次");
                    var detail = runs.detail(e.runId()).orElseThrow();
                    assertThat(detail.status()).isEqualTo("failed");
                    assertThat(runs.calls(e.runId())).extracting(Runs.Call::error)
                            .containsExactly("生成被截断", "429");
                });
    }

    @Test
    void anEmptyResponseIsAFailureNotSilence() {
        model.reply(Purpose.OPENING, "  ", "河阳城。");
        var result = gateway.run(spec(Purpose.OPENING), Markup::parseNarration);
        assertThat(runs.calls(result.runId()).getFirst().error()).isEqualTo("空响应");
    }

    @Test
    void aRefusalIsNotRetried() {
        model.fail(Purpose.OPENING, ModelException.Kind.REFUSED, "content policy").reply(Purpose.OPENING, "不该用到");
        assertThatThrownBy(() -> gateway.run(spec(Purpose.OPENING), Markup::parseNarration))
                .isInstanceOf(CallFailure.class).hasMessageContaining("拒绝");
        assertThat(model.requests(Purpose.OPENING)).hasSize(1);
    }

    @Test
    void aResponseWithoutCostCannotBeAccountedForAndStops() {
        model.withoutCost(Purpose.OPENING, "河阳城。").reply(Purpose.OPENING, "不该用到");
        assertThatThrownBy(() -> gateway.run(spec(Purpose.OPENING), Markup::parseNarration))
                .isInstanceOf(CallFailure.class).hasMessageContaining("费用");
        assertThat(model.requests(Purpose.OPENING)).hasSize(1);
    }

    @Test
    void toolCallsAreAnsweredAndRecordedThenTheAnswerIsParsed() {
        model.toolCall(Purpose.CHARACTER, "recall", "{\"query\":\"骨铃\"}")
                .reply(Purpose.CHARACTER, "陆雪琪（心声）：想起来了。");
        List<String> asked = new ArrayList<>();
        var spec = new Gateway.Spec(Purpose.CHARACTER, "测试", Gateway.Links.NONE, List.of(Msg.user("轮到你")),
                List.of(new Tool("recall", "翻经历", Map.of())), call -> {
                    asked.add(call.arguments());
                    return "· 第1场：骨铃";
                }, Map.of());
        var result = gateway.run(spec, text -> Markup.parseTurn(text, "陆雪琪", true));
        assertThat(result.value().inner()).isEqualTo("想起来了。");
        assertThat(asked).containsExactly("{\"query\":\"骨铃\"}");
        List<ModelRequest> requests = model.requests(Purpose.CHARACTER);
        assertThat(requests.get(1).messages()).extracting(Msg::role).containsExactly("user", "assistant", "tool");
        assertThat(requests.get(1).messages().getLast().content()).isEqualTo("· 第1场：骨铃");
        assertThat(runs.detail(result.runId()).orElseThrow().tools()).contains("骨铃");
        assertThat(runs.calls(result.runId())).extracting(Runs.Call::outcome).containsExactly("tool", "ok");
    }

    @Test
    void afterThreeToolRoundsTheModelMustAnswer() {
        for (int i = 0; i < 3; i++) {
            model.toolCall(Purpose.CHARACTER, "recall", "{\"query\":\"再翻\"}");
        }
        model.reply(Purpose.CHARACTER, "陆雪琪（心声）：够了。");
        var spec = new Gateway.Spec(Purpose.CHARACTER, "测试", Gateway.Links.NONE, List.of(Msg.user("轮到你")),
                List.of(new Tool("recall", "翻经历", Map.of())), call -> "没有翻到相关的经历。", Map.of());
        gateway.run(spec, text -> Markup.parseTurn(text, "陆雪琪", true));
        List<ModelRequest> requests = model.requests(Purpose.CHARACTER);
        assertThat(requests).hasSize(4);
        assertThat(requests.subList(0, 3)).allSatisfy(r -> assertThat(r.tools()).hasSize(1));
        assertThat(requests.get(3).tools()).isEmpty();
    }

    @Test
    void theBudgetStopsCallsBeforeTheyAreMade() {
        model.reply(Purpose.OPENING, "河阳城。");
        gateway.run(spec(Purpose.OPENING), Markup::parseNarration);
        settings.update("mild", new BigDecimal("0.001"));
        assertThatThrownBy(() -> gateway.run(spec(Purpose.OPENING), Markup::parseNarration))
                .isInstanceOf(CallFailure.class).hasMessageContaining("费用上限");
        assertThat(model.requests(Purpose.OPENING)).hasSize(1);
        assertThat(gateway.spent()).isEqualTo(0.001);
    }

    @Test
    void eachPurposeUsesItsConfiguredModelAndReasoning() {
        model.reply(Purpose.NARRATOR, "叙事：无");
        gateway.run(spec(Purpose.NARRATOR), Markup::parsePairRound);
        ModelRequest r = model.requests(Purpose.NARRATOR).getFirst();
        assertThat(r.reasoning()).isEqualTo("off");
        model.reply(Purpose.OPENING, "河阳城。");
        gateway.run(spec(Purpose.OPENING), Markup::parseNarration);
        assertThat(model.requests(Purpose.OPENING).getFirst().reasoning()).isNull();
        model.reply(Purpose.CHARACTER, "陆雪琪（心声）：嗯。");
        gateway.run(spec(Purpose.CHARACTER), text -> text);
        assertThat(model.requests(Purpose.CHARACTER).getFirst().reasoning()).isEqualTo("low");
    }
}
