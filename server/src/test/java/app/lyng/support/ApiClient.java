package app.lyng.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 测试里的 HTTP 客户端：带 cookie，能读 JSON 和 SSE，用法接近真实的网页客户端。 */
public final class ApiClient {

    public record Response(int status, JsonNode json) {}

    public record Sse(String event, JsonNode data) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final String base;

    public ApiClient(int port) {
        this.base = "http://localhost:" + port + "/api";
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET().build());
    }

    public Response post(String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build());
    }

    public Response put(String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build());
    }

    /** 一个会跑模型的动作：读完整个 SSE 流（到服务端关闭为止）。 */
    public List<Sse> stream(String path, Object body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        try {
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + "：" + new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
            }
            return readEvents(response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<Sse> readEvents(java.io.InputStream in) throws IOException {
        List<Sse> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                } else if (line.isEmpty() && name != null) {
                    events.add(new Sse(name, JSON.readTree(data.toString())));
                    name = null;
                    data.setLength(0);
                }
            }
        }
        return events;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body();
            return new Response(response.statusCode(), body == null || body.isBlank() ? null : JSON.readTree(body));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
