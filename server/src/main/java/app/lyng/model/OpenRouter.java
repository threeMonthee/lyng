package app.lyng.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenRouter 的 OpenAI 兼容接口，一律流式读取。首选服务商写成 provider.order 并允许回退；
 * 返回里记下实际服务商、费用与推理 token。
 */
public final class OpenRouter implements ChatModel {

    private final ModelProperties props;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OpenRouter(ModelProperties props, JsonMapper json) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("环境变量 OPENROUTER_API_KEY 未设置（本地放在仓库根的 .env）；只想看界面可以用 --fake");
        }
        this.props = props;
        this.json = json;
    }

    @Override
    public Completion complete(Request request, Consumer<String> onDelta) {
        long started = System.nanoTime();
        HttpResponse<InputStream> response = send(request);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw failure(response.statusCode(), new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
            return read(body, onDelta, started);
        } catch (IOException e) {
            throw new ModelException(ModelException.Kind.TRANSIENT, "读取响应失败：" + e.getMessage());
        }
    }

    private HttpResponse<InputStream> send(Request request) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/chat/completions"))
                .header("Authorization", "Bearer " + props.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(props.headerTimeoutSec()))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body(request))))
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ModelException(ModelException.Kind.TRANSIENT, "网络错误：" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.Kind.FATAL, "请求被中断");
        }
    }

    Map<String, Object> body(Request request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.choice().model());
        body.put("messages", request.messages());
        body.put("max_tokens", request.maxTokens());
        body.put("stream", true);
        body.put("usage", Map.of("include", true));
        String reasoning = request.choice().reasoning();
        if ("off".equals(reasoning)) {
            body.put("reasoning", Map.of("enabled", false));
        } else if (reasoning != null) {
            body.put("reasoning", Map.of("effort", reasoning));
        }
        if (props.provider() != null && !props.provider().isBlank()) {
            body.put("provider", Map.of("order", List.of(props.provider()), "allow_fallbacks", true));
        }
        return body;
    }

    private Completion read(InputStream body, Consumer<String> onDelta, long started) throws IOException {
        StringBuilder text = new StringBuilder();
        String finishReason = null;
        String provider = null;
        Double cost = null;
        JsonNode usage = null;
        long firstToken = -1;
        try (Watchdog watchdog = new Watchdog(body, Duration.ofSeconds(props.idleTimeoutSec()));
             BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            try {
                for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                    watchdog.touch();
                    if (!line.startsWith("data:")) {
                        continue; // 空行与“: OPENROUTER PROCESSING”之类的保活注释
                    }
                    String data = line.substring(5).strip();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    JsonNode chunk = parse(data);
                    if (chunk.hasNonNull("error") && chunk.path("choices").isEmpty()) {
                        throw failure(chunk.path("error").path("code").asInt(0), chunk.path("error").toString());
                    }
                    if (chunk.hasNonNull("provider")) {
                        provider = chunk.path("provider").asString();
                    }
                    if (chunk.hasNonNull("usage")) {
                        usage = chunk.path("usage");
                        cost = usage.hasNonNull("cost") ? usage.path("cost").asDouble() : null;
                    }
                    JsonNode choice = chunk.path("choices").path(0);
                    if (choice.hasNonNull("finish_reason")) {
                        finishReason = choice.path("finish_reason").asString();
                    }
                    String piece = choice.path("delta").path("content").asString("");
                    if (!piece.isEmpty()) {
                        if (firstToken < 0) {
                            firstToken = elapsedMs(started);
                        }
                        text.append(piece);
                        onDelta.accept(piece);
                    }
                }
            } catch (IOException e) {
                String why = watchdog.timedOut() ? "超过 " + props.idleTimeoutSec() + " 秒没有数据" : e.getMessage();
                throw new ModelException(ModelException.Kind.TRANSIENT, "流中断：" + why);
            }
        }
        return new Completion(text.toString(), finishReason, cost, provider, integer(usage, "prompt_tokens"),
                integer(usage, "completion_tokens"),
                usage == null ? null : integer(usage.path("completion_tokens_details"), "reasoning_tokens"),
                Math.max(firstToken, 0), elapsedMs(started));
    }

    private JsonNode parse(String data) {
        try {
            return json.readTree(data);
        } catch (JacksonException e) {
            throw new ModelException(ModelException.Kind.TRANSIENT, "无法解析的数据片段");
        }
    }

    private static Integer integer(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.path(field).asInt() : null;
    }

    private static ModelException failure(int status, String body) {
        String message = "HTTP " + status + "：" + (body.length() > 300 ? body.substring(0, 300) : body);
        if (status == 429 || (status >= 500 && status < 600)) {
            return new ModelException(ModelException.Kind.TRANSIENT, message);
        }
        String lower = body.toLowerCase();
        if (status == 403 && (lower.contains("moderation") || lower.contains("flagged"))) {
            return new ModelException(ModelException.Kind.REFUSED, message);
        }
        return new ModelException(ModelException.Kind.FATAL, message);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** 流太久没有任何数据（含保活注释）就关掉连接，让阻塞的读取以异常结束。 */
    private static final class Watchdog implements AutoCloseable {
        private final Thread thread;
        private volatile long lastActivity = System.nanoTime();
        private volatile boolean timedOut;

        Watchdog(InputStream stream, Duration idle) {
            thread = Thread.startVirtualThread(() -> {
                try {
                    while (true) {
                        Thread.sleep(1000);
                        if (System.nanoTime() - lastActivity > idle.toNanos()) {
                            timedOut = true;
                            stream.close();
                            return;
                        }
                    }
                } catch (InterruptedException | IOException e) {
                    // 正常结束或流已关闭
                }
            });
        }

        void touch() {
            lastActivity = System.nanoTime();
        }

        boolean timedOut() {
            return timedOut;
        }

        @Override
        public void close() {
            thread.interrupt();
        }
    }
}
