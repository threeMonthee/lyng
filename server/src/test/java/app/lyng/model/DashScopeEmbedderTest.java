package app.lyng.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 向量接口：分批、按 text_index 归位、查询与文档的参数不同。 */
class DashScopeEmbedderTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;

    private DashScopeEmbedder serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/embeddings/text-embedding/text-embedding", exchange -> {
            JsonNode body = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(body);
            int n = body.path("input").path("texts").size();
            StringBuilder out = new StringBuilder("{\"output\":{\"embeddings\":[");
            for (int i = n - 1; i >= 0; i--) {
                String text = body.path("input").path("texts").get(i).asString();
                out.append("{\"text_index\":").append(i).append(",\"embedding\":[").append(text.length()).append(",1]}");
                out.append(i > 0 ? "," : "");
            }
            byte[] bytes = out.append("]}}").toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream o = exchange.getResponseBody()) {
                o.write(bytes);
            }
        });
        server.start();
        return new DashScopeEmbedder(new EmbeddingProperties("dashscope", "k",
                "http://127.0.0.1:" + server.getAddress().getPort(), "emb", 1024), json);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void splitsIntoBatchesAndPutsEachVectorBackInPlace() throws IOException {
        List<String> texts = IntStream.range(0, 25).mapToObj("x"::repeat).toList();
        List<float[]> vectors = serve().embed(texts, false);
        assertThat(requests).hasSize(2);
        assertThat(vectors).hasSize(25);
        assertThat(vectors.get(0)[0]).isZero();
        assertThat(vectors.get(24)[0]).isEqualTo(24f);
        assertThat(requests.getFirst().path("parameters").path("text_type").asString()).isEqualTo("document");
        assertThat(requests.getFirst().path("parameters").has("instruct")).isFalse();
    }

    @Test
    void queriesCarryAnInstruction() throws IOException {
        serve().embed(List.of("骨铃"), true);
        assertThat(requests.getFirst().path("parameters").path("text_type").asString()).isEqualTo("query");
        assertThat(requests.getFirst().path("parameters").path("instruct").asString()).isNotBlank();
        assertThat(requests.getFirst().path("parameters").path("dimension").asInt()).isEqualTo(1024);
    }

    @Test
    void withoutAKeyItSaysWhatIsMissing() {
        DashScopeEmbedder e = new DashScopeEmbedder(new EmbeddingProperties("dashscope", "", "http://x", "emb", 1024), json);
        assertThatThrownBy(() -> e.embed(List.of("x"), false)).hasMessageContaining("DASHSCOPE_API_KEY");
    }
}
