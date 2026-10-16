package app.lyng.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 对着本机的假接口读 OpenRouter 的流：正文、思考、分片的工具调用、费用与服务商、各种失败。 */
class OpenRouterModelTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();

    private OpenRouterModel serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
        return new OpenRouterModel(new ModelProperties("openrouter", "test-key", base, "Parasail", "m", null, null, null,
                null, null, null, 1000, 0, 5, 5, null, 0), json);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static ModelRequest request(String reasoning, List<Tool> tools) {
        return new ModelRequest(Purpose.CHARACTER, "deepseek/x", reasoning, List.of(Msg.system("你是谁"), Msg.user("轮到你")),
                tools, 1000, Map.of("name", "不发给服务商"));
    }

    private static String sse(String... chunks) {
        StringBuilder out = new StringBuilder(": OPENROUTER PROCESSING\n\n");
        for (String c : chunks) {
            out.append("data: ").append(c).append("\n\n");
        }
        return out.append("data: [DONE]\n\n").toString();
    }

    @Test
    void readsTextReasoningCostAndProvider() throws IOException {
        OpenRouterModel model = serve(200, sse(
                "{\"provider\":\"Parasail\",\"choices\":[{\"delta\":{\"reasoning\":\"想一想\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"陆雪琪（说）：\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"走吧。\"},\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30,\"cost\":0.00042,"
                        + "\"completion_tokens_details\":{\"reasoning_tokens\":12}}}"));
        List<String> pieces = new ArrayList<>();
        Completion done = model.complete(request(null, List.of()), new StreamListener() {
            @Override
            public void content(String delta) {
                pieces.add(delta);
            }
        });
        assertThat(pieces).containsExactly("陆雪琪（说）：", "走吧。");
        assertThat(done.text()).isEqualTo("陆雪琪（说）：走吧。");
        assertThat(done.reasoning()).isEqualTo("想一想");
        assertThat(done.cost()).isEqualTo(0.00042);
        assertThat(done.provider()).isEqualTo("Parasail");
        assertThat(done.reasoningTokens()).isEqualTo(12);
        assertThat(done.finishReason()).isEqualTo("stop");
    }

    @Test
    void sendsTheProviderOrderAndReasoningSwitchButNotTheMeta() throws IOException {
        OpenRouterModel model = serve(200, sse("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"cost\":0}}"));
        model.complete(request("off", List.of(new Tool("recall", "翻经历", Map.of("type", "object")))), StreamListener.NONE);
        JsonNode body = json.readTree(received.get());
        assertThat(body.path("provider").path("order").get(0).asString()).isEqualTo("Parasail");
        assertThat(body.path("provider").path("allow_fallbacks").asBoolean()).isTrue();
        assertThat(body.path("reasoning").path("enabled").asBoolean(true)).isFalse();
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("usage").path("include").asBoolean()).isTrue();
        assertThat(body.path("tools").get(0).path("function").path("name").asString()).isEqualTo("recall");
        assertThat(received.get()).doesNotContain("不发给服务商");
    }

    @Test
    void assemblesToolCallsSplitAcrossChunks() throws IOException {
        OpenRouterModel model = serve(200, sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"recall\",\"arguments\":\"{\\\"que\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ry\\\":\\\"骨铃\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}",
                "{\"choices\":[],\"usage\":{\"cost\":0.0001}}"));
        Completion done = model.complete(request(null, List.of()), StreamListener.NONE);
        assertThat(done.toolCalls()).containsExactly(new ToolCall("c1", "recall", "{\"query\":\"骨铃\"}"));
    }

    @Test
    void rateLimitsAndServerErrorsAreTransientKeysAreNot() throws IOException {
        assertThatThrownBy(() -> serve(429, "{\"error\":\"slow down\"}").complete(request(null, List.of()), StreamListener.NONE))
                .isInstanceOfSatisfying(ModelException.class, e -> assertThat(e.kind()).isEqualTo(ModelException.Kind.TRANSIENT));
        stop();
        assertThatThrownBy(() -> serve(401, "{\"error\":\"bad key\"}").complete(request(null, List.of()), StreamListener.NONE))
                .isInstanceOfSatisfying(ModelException.class, e -> assertThat(e.kind()).isEqualTo(ModelException.Kind.FATAL));
        stop();
        assertThatThrownBy(() -> serve(403, "{\"error\":\"flagged by moderation\"}").complete(request(null, List.of()),
                StreamListener.NONE))
                .isInstanceOfSatisfying(ModelException.class, e -> assertThat(e.kind()).isEqualTo(ModelException.Kind.REFUSED));
    }

    @Test
    void anErrorInsideTheStreamIsAFailure() throws IOException {
        OpenRouterModel model = serve(200, sse("{\"choices\":[{\"delta\":{\"content\":\"陆\"}}]}",
                "{\"error\":{\"code\":502,\"message\":\"upstream\"}}"));
        assertThatThrownBy(() -> model.complete(request(null, List.of()), StreamListener.NONE))
                .isInstanceOfSatisfying(ModelException.class, e -> assertThat(e.kind()).isEqualTo(ModelException.Kind.TRANSIENT));
    }

    @Test
    void withoutAKeyTheServiceRefusesToStart() {
        assertThatThrownBy(() -> new OpenRouterModel(new ModelProperties("openrouter", " ", "http://x", null, "m", null, null,
                null, null, null, null, 1000, 0, 5, 5, null, 0), json)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENROUTER_API_KEY");
    }
}
