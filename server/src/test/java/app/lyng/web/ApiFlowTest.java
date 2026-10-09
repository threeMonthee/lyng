package app.lyng.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.lyng.support.ApiClient;
import app.lyng.support.ApiClient.Sse;
import app.lyng.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.JsonNode;

/** 自动走完：登录 → 一句话创作 → 预览确认 → 第一场 → 散场 → 第二场；并验证按用户隔离。全程替身模型，不发网络请求。 */
class ApiFlowTest extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient db;

    private ApiClient login() {
        ApiClient api = new ApiClient(port);
        assertThat(api.post("/login", Map.of("name", "me", "password", "test-password")).status()).isEqualTo(200);
        return api;
    }

    private static List<String> names(List<Sse> events) {
        return events.stream().map(Sse::event).toList();
    }

    @Test
    void everythingNeedsALoginExceptLoginItself() {
        ApiClient anonymous = new ApiClient(port);
        assertThat(anonymous.get("/me").status()).isEqualTo(401);
        assertThat(anonymous.get("/worlds").status()).isEqualTo(401);
        assertThat(anonymous.get("/health").status()).isEqualTo(200);
        assertThat(anonymous.post("/login", Map.of("name", "me", "password", "wrong")).status()).isEqualTo(401);
        ApiClient api = login();
        assertThat(api.get("/me").json().path("name").asString()).isEqualTo("me");
        api.post("/logout", Map.of());
        assertThat(api.get("/me").status()).isEqualTo(401);
    }

    @Test
    void fromOneSentenceToASecondSceneThatContinuesTheFirst() {
        ApiClient api = login();

        // 1. 一句话创作 → 预览（草稿可手改）→ 确认诞生
        ApiClient.Response draft = api.post("/creation/draft", Map.of("request", "客栈，阿青和阿远"));
        assertThat(draft.status()).isEqualTo(200);
        assertThat(draft.json().path("characters")).hasSize(2);
        Map<String, Object> edited = Map.of("draft", Map.of(
                "world", Map.of("name", "客栈世界", "publicText", "手改过的公共设定。", "narratorText", "手改过的秘密。"),
                "characters", List.of(
                        Map.of("name", "阿青", "profile", "阿青是客栈的伙计。"),
                        Map.of("name", "阿远", "profile", "阿远是路过的刀客。"))));
        JsonNode born = api.post("/creation/confirm", edited).json();
        UUID qing = UUID.fromString(born.path("characters").path(0).path("id").asString());
        UUID yuan = UUID.fromString(born.path("characters").path(1).path("id").asString());
        assertThat(api.get("/worlds").json().path(0).path("name").asString()).isEqualTo("客栈世界");
        assertThat(api.get("/worlds").json().path(0).path("publicText").asString()).isEqualTo("手改过的公共设定。");

        // 2. 第一场：叙事者开场 → 说 → 做 → 静候 → 由用户结束
        UUID first = startScene(api, yuan, qing, "雨夜，客栈后院。");
        List<Sse> say = api.stream("/scenes/" + first + "/act", Map.of("action", "说", "text", "我回来了。"));
        assertThat(names(say)).containsSubsequence("user", "bubble", "bubble", "commit", "done");
        JsonNode commit = say.stream().filter(e -> e.event().equals("commit")).findFirst().orElseThrow().data();
        assertThat(commit.path("speaker").asString()).isEqualTo("阿青");
        assertThat(commit.path("inner").asString()).isNotBlank(); // 心声随事件下发，客户端默认隐藏
        assertThat(names(api.stream("/scenes/" + first + "/act", Map.of("action", "做", "text", "把伞靠在墙边。"))))
                .containsSubsequence("user", "narration", "commit", "done");
        assertThat(names(api.stream("/scenes/" + first + "/act", Map.of("action", "静候", "text", "")))).contains("commit", "done");
        assertThat(names(api.stream("/scenes/" + first + "/end?closing=true", Map.of()))).containsExactly("narration", "done");
        assertThat(api.get("/scenes/" + first).json().path("status").asString()).isEqualTo("ended");

        // 3. 散场后生成回想；第二场接得上第一场
        awaitRecollections(2);
        UUID second = startScene(api, yuan, qing, "第二天清晨。");
        api.stream("/scenes/" + second + "/act", Map.of("action", "说", "text", "昨晚谢谢你。"));
        String prompt = model.promptOf(model.requests("character").getLast());
        assertThat(prompt).contains("【第1场】").contains("阿远（说）：我回来了。").contains("【你眼中的他们】");
        assertThat(api.get("/characters/" + qing).json().path("memory").path("memories")).hasSize(1);

        // 4. 记账：每次调用都有记录
        JsonNode usage = api.get("/usage").json();
        assertThat(usage.path("totalCost").asDouble()).isGreaterThan(0);
        assertThat(usage.path("recent").path(0).path("provider").asString()).isNotBlank();
        assertThat(api.get("/scenes").json()).hasSize(2);
    }

    private UUID startScene(ApiClient api, UUID possessed, UUID other, String setup) {
        ApiClient.Response created = api.post("/scenes",
                Map.of("possessedId", possessed, "otherId", other, "tone", "日常相处", "setup", setup));
        assertThat(created.status()).isEqualTo(200);
        UUID scene = UUID.fromString(created.json().path("id").asString());
        assertThat(names(api.stream("/scenes/" + scene + "/open", Map.of()))).containsExactly("narration", "done");
        return scene;
    }

    private void awaitRecollections(int expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (db.sql("SELECT count(*) FROM recollections WHERE status = 'done'").query(Integer.class).single() >= expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("回想没有在 10 秒内生成");
    }

    @Test
    void technicalFailureIsVisibleAndRetriableOverHttp() {
        ApiClient api = login();
        JsonNode born = api.post("/creation/confirm", Map.of("draft", Map.of(
                "world", Map.of("name", "客栈世界", "publicText", "公共。", "narratorText", "秘密。"),
                "characters", List.of(Map.of("name", "阿青", "profile", "档案一。"), Map.of("name", "阿远", "profile", "档案二。"))))).json();
        UUID scene = startScene(api, UUID.fromString(born.path("characters").path(1).path("id").asString()),
                UUID.fromString(born.path("characters").path(0).path("id").asString()), "雨夜。");
        model.script(request -> request.purpose().kind().equals("character") ? app.lyng.support.ScriptedChatModel.Reply.text("") : null);

        List<Sse> failed = api.stream("/scenes/" + scene + "/act", Map.of("action", "说", "text", "喂？"));

        assertThat(names(failed)).containsSubsequence("user", "failure", "done");
        JsonNode view = api.get("/scenes/" + scene).json();
        assertThat(view.path("events").path(2).path("kind").asString()).isEqualTo("failure");
        assertThat(view.path("events").path(2).path("resolved").asBoolean()).isFalse();
        assertThat(api.post("/scenes/" + scene + "/act", Map.of("action", "说", "text", "再来")).status()).isEqualTo(409);

        model.reset();
        assertThat(names(api.stream("/scenes/" + scene + "/retry", Map.of()))).containsSubsequence("commit", "resolved", "done");
        assertThat(api.get("/scenes/" + scene).json().path("events").path(2).path("resolved").asBoolean()).isTrue();
    }

    @Test
    void creationFailureIsReportedAsATechnicalFailure() {
        ApiClient api = login();
        model.script(request -> app.lyng.support.ScriptedChatModel.Reply.text("这是一段没有标记的话。"));
        ApiClient.Response response = api.post("/creation/draft", Map.of("request", "随便"));
        assertThat(response.status()).isEqualTo(502);
        assertThat(response.json().path("error").asString()).contains("格式不合规").contains("已重试一次");
        assertThat(api.post("/creation/draft", Map.of("request", "  ")).status()).isEqualTo(400);
    }

    @Test
    void modelSettingsTakeEffectWithoutRestart() {
        ApiClient api = login();
        api.put("/settings", Map.of("character", "other/model@off"));
        assertThat(api.get("/settings").json().path("models").path("character").asString()).isEqualTo("other/model@off");
        JsonNode born = api.post("/creation/confirm", Map.of("draft", Map.of(
                "world", Map.of("name", "客栈世界", "publicText", "公共。", "narratorText", "秘密。"),
                "characters", List.of(Map.of("name", "阿青", "profile", "档案一。"), Map.of("name", "阿远", "profile", "档案二。"))))).json();
        UUID scene = startScene(api, UUID.fromString(born.path("characters").path(1).path("id").asString()),
                UUID.fromString(born.path("characters").path(0).path("id").asString()), "雨夜。");
        api.stream("/scenes/" + scene + "/act", Map.of("action", "说", "text", "喂？"));

        assertThat(model.requests("character").getFirst().choice().model()).isEqualTo("other/model");
        assertThat(model.requests("round").getFirst().choice().model()).isEqualTo("deepseek/deepseek-v4.1-flash");
        assertThat(model.requests("round").getFirst().choice().reasoning()).as("逐轮决定默认关闭推理").isEqualTo("off");
        assertThat(model.requests("character").getFirst().choice().reasoning()).isEqualTo("off"); // 用户设置里写了 @off
        assertThat(api.put("/settings", Map.of("character", "x/y@turbo")).status()).isEqualTo(400);
    }

    @Test
    void oneUserCannotSeeOrTouchAnotherUsersStory() {
        ApiClient mine = login();
        JsonNode born = mine.post("/creation/confirm", Map.of("draft", Map.of(
                "world", Map.of("name", "客栈世界", "publicText", "公共。", "narratorText", "秘密。"),
                "characters", List.of(Map.of("name", "阿青", "profile", "档案一。"), Map.of("name", "阿远", "profile", "档案二。"))))).json();
        UUID qing = UUID.fromString(born.path("characters").path(0).path("id").asString());
        UUID yuan = UUID.fromString(born.path("characters").path(1).path("id").asString());
        UUID scene = startScene(mine, yuan, qing, "雨夜。");

        db.sql("INSERT INTO users (id, name, password_hash) VALUES (?, 'other', ?) ON CONFLICT (name) DO NOTHING")
                .params(UUID.randomUUID(), new BCryptPasswordEncoder().encode("pw2")).update();
        ApiClient theirs = new ApiClient(port);
        assertThat(theirs.post("/login", Map.of("name", "other", "password", "pw2")).status()).isEqualTo(200);

        assertThat(theirs.get("/worlds").json()).isEmpty();
        assertThat(theirs.get("/scenes").json()).isEmpty();
        assertThat(theirs.get("/scenes/" + scene).status()).isEqualTo(404);
        assertThat(theirs.get("/characters/" + qing).status()).isEqualTo(404);
        assertThat(theirs.post("/scenes/" + scene + "/act", Map.of("action", "说", "text", "你好")).status()).isEqualTo(404);
        assertThat(theirs.post("/scenes", Map.of("possessedId", yuan, "otherId", qing, "tone", "日常相处", "setup", "x")).status()).isEqualTo(404);
        assertThat(theirs.get("/usage").json().path("recent")).isEmpty();
        // 数据库层面：别人的父行不能被子行引用
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql(
                "INSERT INTO characters (id, user_id, home_world_id, name, profile) SELECT ?, u.id, w.id, 'x', 'y' FROM users u, worlds w WHERE u.name = 'other'")
                .param(UUID.randomUUID()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void validatesInput() {
        ApiClient api = login();
        Map<String, Object> draft = Map.of("draft", Map.of("world", Map.of("name", "同名世界", "publicText", "p", "narratorText", "n"),
                "characters", List.of(Map.of("name", "阿青", "profile", "x"))));
        assertThat(api.post("/creation/confirm", draft).status()).isEqualTo(200);
        ApiClient.Response again = api.post("/creation/confirm", draft);
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.json().path("error").asString()).contains("已经有叫“同名世界”的世界了");
        assertThat(api.post("/creation/confirm", Map.of("draft", Map.of("characters", List.of()))).status()).isEqualTo(400);
        assertThat(api.post("/creation/confirm", Map.of("draft", Map.of(
                "world", Map.of("name", "w", "publicText", "p", "narratorText", "n"),
                "characters", List.of(Map.of("name", "名字太长太长太长太长太长", "profile", "x"))))).status()).isEqualTo(400);
        assertThat(api.post("/scenes", Map.of("tone", "日常相处", "setup", "x")).status()).isEqualTo(400);
    }
}
