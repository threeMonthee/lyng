package app.lyng.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 百炼文本向量（x-chat-ai 实测过的接口与参数）。一批最多 20 条。 */
public final class DashScopeEmbedder implements Embedder {

    static final int BATCH = 20;

    private final EmbeddingProperties props;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public DashScopeEmbedder(EmbeddingProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public List<float[]> embed(List<String> texts, boolean query) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new ModelException(ModelException.Kind.FATAL, "环境变量 DASHSCOPE_API_KEY 未设置，记忆无法建立索引");
        }
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH) {
            out.addAll(batch(texts.subList(i, Math.min(texts.size(), i + BATCH)), query));
        }
        return out;
    }

    private List<float[]> batch(List<String> texts, boolean query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("dimension", props.dimension());
        parameters.put("text_type", query ? "query" : "document");
        parameters.put("output_type", "dense");
        if (query) {
            parameters.put("instruct", "Retrieve the character's own past experiences relevant to the present"
                    + " situation, preserving who said what and later developments.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("input", Map.of("texts", texts));
        body.put("parameters", parameters);
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(props.baseUrl() + "/api/v1/services/embeddings/text-embedding/text-embedding"))
                .header("Authorization", "Bearer " + props.apiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ModelException(ModelException.Kind.TRANSIENT, "向量服务网络错误：" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.Kind.FATAL, "请求被中断");
        }
        if (response.statusCode() != 200) {
            String text = response.body();
            throw new ModelException(response.statusCode() == 429 || response.statusCode() >= 500
                    ? ModelException.Kind.TRANSIENT : ModelException.Kind.FATAL,
                    "向量服务 HTTP " + response.statusCode() + "：" + (text.length() > 200 ? text.substring(0, 200) : text));
        }
        JsonNode embeddings = json.readTree(response.body()).path("output").path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != texts.size()) {
            throw new ModelException(ModelException.Kind.TRANSIENT, "向量服务返回的条数不对");
        }
        float[][] out = new float[texts.size()][];
        for (JsonNode e : embeddings) {
            JsonNode values = e.path("embedding");
            float[] v = new float[values.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) values.path(i).asDouble();
            }
            out[e.path("text_index").asInt()] = v;
        }
        return List.of(out);
    }
}
