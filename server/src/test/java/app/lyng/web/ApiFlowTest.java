package app.lyng.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.lyng.support.ApiClient;
import app.lyng.support.ApiClient.Sse;
import app.lyng.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/** 通过 HTTP 走完：创作 → 诞生 → 第一场（开场、说、静候）→ 结束 → 回想 → 第二场接上第一场。 */
class ApiFlowTest extends IntegrationTest {

    @LocalServerPort
    int port;

    ApiClient api;

    @BeforeEach
    void client() {
        api = new ApiClient(port);
    }

    private static List<String> names(List<Sse> events) {
        return events.stream().map(Sse::event).toList();
    }

    @Test
    void fromOneSentenceToASecondScene() {
        JsonNode draft = api.post("/drafts", Map.of("request", "一座小镇，客栈伙计阿青与回乡的刀客沈远")).json();
        assertThat(draft.path("world").path("name").asString()).isEqualTo("替身世界");
        assertThat(draft.path("characters")).hasSize(2);

        // 预览里手改了名字
        Map<String, Object> edited = Map.of(
                "world", Map.of("name", "雨镇", "publicText", draft.path("world").path("publicText").asString(),
                        "narratorText", draft.path("world").path("narratorText").asString()),
                "characters", List.of(
                        Map.of("name", "阿青", "profile", draft.path("characters").path(0).path("profile").asString()),
                        Map.of("name", "沈远", "profile", draft.path("characters").path(1).path("profile").asString())));
        JsonNode born = api.post("/births", Map.of("draft", edited)).json();
        String worldId = born.path("worldId").asString();
        String qing = born.path("characterIds").path(0).asString();
        String yuan = born.path("characterIds").path(1).asString();
        assertThat(api.get("/overview").json().path("worlds").path(0).path("name").asString()).isEqualTo("雨镇");

        String first = api.post("/scenes", Map.of("worldId", worldId, "cast", List.of(yuan, qing), "possessedId", yuan,
                "tone", "日常相处", "setup", "雨夜，客栈里只剩沈远一位客人。")).json().path("id").asString();
        assertThat(api.post("/scenes/" + first + "/act", Map.of("action", "说", "text", "x")).status())
                .as("开场之前不能行动").isEqualTo(409);

        assertThat(names(api.stream("/scenes/" + first + "/resume", Map.of()))).containsExactly("typing", "line", "done");
        List<Sse> round = api.stream("/scenes/" + first + "/act", Map.of("action", "说", "text", "再温一壶酒。"));
        assertThat(names(round)).startsWith("line", "typing").contains("draft").endsWith("done");
        assertThat(round.getFirst().data().path("byUser").asBoolean()).isTrue();
        api.stream("/scenes/" + first + "/act", Map.of("action", "静候"));

        JsonNode scene = api.get("/scenes/" + first).json();
        assertThat(scene.path("possessedId").asString()).isEqualTo(yuan);
        assertThat(scene.path("lines").size()).isGreaterThan(6);
        assertThat(scene.path("busy").asBoolean()).isFalse();
        assertThat(scene.path("pending").isNull()).isTrue();

        assertThat(names(api.stream("/scenes/" + first + "/end", Map.of("closing", true)))).endsWith("line", "done");
        awaitMemories(2);
        JsonNode page = api.get("/characters/" + qing).json();
        assertThat(page.path("memories").path(0).path("memory").asString()).contains("阿青的替身回想");
        assertThat(page.path("views").path(0).path("name").asString()).isEqualTo("沈远");

        String second = api.post("/scenes", Map.of("worldId", worldId, "cast", List.of(qing, yuan), "followId", qing,
                "tone", "冒险", "setup", "第二天清早，镇口来了官差。")).json().path("id").asString();
        api.stream("/scenes/" + second + "/resume", Map.of());
        assertThat(model.lastPrompt("opening")).contains("【上一场】").contains("再温一壶酒。");
        assertThat(api.post("/scenes/" + second + "/act", Map.of("action", "说", "text", "x")).status()).isEqualTo(400);
        api.stream("/scenes/" + second + "/act", Map.of("action", "继续"));
        assertThat(model.lastPrompt("character")).contains("【第1场】");

        JsonNode switched = api.put("/scenes/" + second + "/identity", Map.of("possessedId", qing)).json();
        assertThat(switched.path("possessedId").asString()).isEqualTo(qing);

        JsonNode overview = api.get("/overview").json();
        assertThat(overview.path("scenes")).hasSize(2);
        assertThat(overview.path("fake").asBoolean()).isTrue();
        assertThat(api.get("/usage").json().path("calls").size()).isGreaterThan(5);
    }

    @Test
    void errorsAreReadable() {
        assertThat(api.get("/scenes/nope").status()).isEqualTo(404);
        assertThat(api.get("/scenes/nope").json().path("error").asString()).contains("找不到");
        assertThat(api.post("/drafts", Map.of("request", " ")).status()).isEqualTo(400);
        assertThat(api.post("/births", Map.of("draft", Map.of("characters", List.of()))).status()).isEqualTo(400);
    }
}
