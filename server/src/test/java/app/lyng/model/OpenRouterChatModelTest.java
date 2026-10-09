package app.lyng.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 用本机的假服务器验证请求长什么样、流怎么读、错误怎么分类；不发任何真实请求。 */
class OpenRouterChatModelTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
    private volatile int status = 200;
    private volatile String body = "";
    private volatile long stallMs;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
                out.flush();
                if (stallMs > 0) {
                    try {
                        Thread.sleep(stallMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (IOException ignored) {
                // 客户端已经断开
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenRouterChatModel client(String provider, int idleSec) {
        ModelProperties props = new ModelProperties("openrouter", "test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "m/x", null, provider, 100, 0, 5, idleSec, null);
        return new OpenRouterChatModel(props, JSON);
    }

    private static ChatRequest request(String spec) {
        return new ChatRequest(ModelChoice.parse(spec), List.of(ChatMessage.system("s"), ChatMessage.user("u")), 100,
                new CallPurpose("character", "阿青", null));
    }

    private static String sse(String... chunks) {
        StringBuilder out = new StringBuilder(": OPENROUTER PROCESSING\n\n");
        for (String chunk : chunks) {
            out.append("data: ").append(chunk).append("\n\n");
        }
        return out.append("data: [DONE]\n\n").toString();
    }

    @Test
    void readsTheStreamWithProviderCostAndReasoningTokens() {
        body = sse(
                "{\"provider\":\"DeepSeek\",\"choices\":[{\"delta\":{\"content\":\"你\"}}]}",
                "{\"provider\":\"DeepSeek\",\"choices\":[{\"delta\":{\"reasoning\":\"嗯\"}}]}",
                "{\"provider\":\"DeepSeek\",\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}",
                "{\"provider\":\"DeepSeek\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":7,\"cost\":0.0003,"
                        + "\"completion_tokens_details\":{\"reasoning_tokens\":4}}}");
        List<String> pieces = new ArrayList<>();

        Completion c = client("DeepSeek", 5).complete(request("m/x"), pieces::add);

        assertThat(pieces).containsExactly("你", "好");
        assertThat(c.text()).isEqualTo("你好");
        assertThat(c.finishReason()).isEqualTo("stop");
        assertThat(c.provider()).isEqualTo("DeepSeek");
        assertThat(c.cost()).isEqualTo(0.0003);
        assertThat(c.usage()).isEqualTo(new Usage(10, 7, 4));
    }

    @Test
    void sendsThePreferredProviderWithFallbackAllowedAndTheReasoningSetting() {
        body = sse("{\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}");
        client("DeepSeek", 5).complete(request("m/x"), null);
        client("DeepSeek", 5).complete(request("m/x@off"), null);
        client("DeepSeek", 5).complete(request("m/x@low"), null);
        client(null, 5).complete(request("m/x"), null);

        JsonNode plain = JSON.readTree(requestBodies.get(0));
        assertThat(plain.path("model").asString()).isEqualTo("m/x");
        assertThat(plain.path("stream").asBoolean()).isTrue();
        assertThat(plain.path("usage").path("include").asBoolean()).isTrue();
        assertThat(plain.path("provider").path("order").path(0).asString()).isEqualTo("DeepSeek");
        assertThat(plain.path("provider").path("allow_fallbacks").asBoolean()).isTrue();
        assertThat(plain.has("reasoning")).as("不写推理设置就用模型默认（开推理）").isFalse();
        assertThat(JSON.readTree(requestBodies.get(1)).path("reasoning").path("enabled").asBoolean(true)).isFalse();
        assertThat(JSON.readTree(requestBodies.get(2)).path("reasoning").path("effort").asString()).isEqualTo("low");
        assertThat(JSON.readTree(requestBodies.get(3)).has("provider")).isFalse();
    }

    @Test
    void missingCostIsReportedAsNull() {
        body = sse("{\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}");
        assertThat(client(null, 5).complete(request("m/x"), null).cost()).isNull();
    }

    @Test
    void classifiesHttpErrors() {
        assertThat(kindOf(429, "{\"error\":{\"message\":\"rate limited\"}}")).isEqualTo(ModelException.Kind.TRANSIENT);
        assertThat(kindOf(502, "{}")).isEqualTo(ModelException.Kind.TRANSIENT);
        assertThat(kindOf(401, "{\"error\":{\"message\":\"no auth\"}}")).isEqualTo(ModelException.Kind.FATAL);
        assertThat(kindOf(402, "{}")).isEqualTo(ModelException.Kind.FATAL);
        assertThat(kindOf(403, "{\"error\":{\"message\":\"input flagged by moderation\"}}")).isEqualTo(ModelException.Kind.REFUSED);
        assertThat(kindOf(403, "{\"error\":{\"message\":\"forbidden\"}}")).isEqualTo(ModelException.Kind.FATAL);
    }

    private ModelException.Kind kindOf(int httpStatus, String payload) {
        status = httpStatus;
        body = payload;
        try {
            client(null, 5).complete(request("m/x"), null);
        } catch (ModelException e) {
            assertThat(e.status()).isEqualTo(httpStatus);
            return e.kind();
        }
        throw new AssertionError("应当抛 ModelException");
    }

    @Test
    void aMidStreamErrorIsClassified() {
        body = sse("{\"error\":{\"code\":503,\"message\":\"upstream\"},\"choices\":[]}");
        assertThatThrownBy(() -> client(null, 5).complete(request("m/x"), null))
                .isInstanceOfSatisfying(ModelException.class, e -> assertThat(e.kind()).isEqualTo(ModelException.Kind.TRANSIENT));
    }

    @Test
    void aFormatErrorRaisedByTheConsumerAbortsTheStream() {
        body = sse("{\"choices\":[{\"delta\":{\"content\":\"坏\"}}]}", "{\"choices\":[{\"delta\":{\"content\":\"了\"}}]}");
        assertThatThrownBy(() -> client(null, 5).complete(request("m/x"), piece -> {
            throw new FormatError("不合规");
        })).isInstanceOf(FormatError.class);
    }

    @Test
    void aStreamThatGoesQuietIsCutOffAsATransientFailure() {
        body = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n";
        stallMs = 5000;
        long started = System.nanoTime();
        assertThatThrownBy(() -> client(null, 1).complete(request("m/x"), null))
                .isInstanceOfSatisfying(ModelException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ModelException.Kind.TRANSIENT);
                    assertThat(e.getMessage()).contains("没有数据");
                });
        assertThat((System.nanoTime() - started) / 1_000_000_000).isLessThan(5);
    }
}
