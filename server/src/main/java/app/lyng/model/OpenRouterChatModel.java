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
 * OpenRouter 的 OpenAI 兼容接口，一律流式读取（不要求调用方关心是否流式）。
 * 首选服务商写成 provider.order 并允许回退；返回里记下实际服务商和费用。
 */
public final class OpenRouterChatModel implements ChatModel {

    private final ModelProperties props;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OpenRouterChatModel(ModelProperties props, JsonMapper json) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("环境变量 OPENROUTER_API_KEY 未设置（本地放仓库根 .env）");
        }
        this.props = props;
        this.json = json;
    }

    @Override
    public Completion complete(ChatRequest request, Consumer<String> onDelta) {
        long started = System.nanoTime();
        HttpResponse<InputStream> response = send(request);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw failureOf(response.statusCode(), new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
            return readStream(body, onDelta, started);
        } catch (IOException e) {
            throw new ModelException(ModelException.Kind.TRANSIENT, "读取响应失败：" + e.getMessage(), null);
        }
    }

    private HttpResponse<InputStream> send(ChatRequest request) {
        HttpRequest http = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/chat/completions"))
                .header("Authorization", "Bearer " + props.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(props.headerTimeoutSec()))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(requestBody(request))))
                .build();
        try {
            return this.http.send(http, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ModelException(ModelException.Kind.TRANSIENT, "网络错误：" + e.getClass().getSimpleName(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.Kind.FATAL, "请求被中断", null);
        }
    }

    Map<String, Object> requestBody(ChatRequest request) {
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
        String provider = props.preferredProvider();
        if (provider != null && !provider.isBlank()) {
            body.put("provider", Map.of("order", List.of(provider), "allow_fallbacks", true));
        }
        return body;
    }

    private Completion readStream(InputStream body, Consumer<String> onDelta, long started) throws IOException {
        StringBuilder text = new StringBuilder();
        String finishReason = null;
        String provider = null;
        Double cost = null;
        Usage usage = Usage.NONE;
        long firstTokenMs = -1;
        try (IdleWatchdog watchdog = new IdleWatchdog(body, Duration.ofSeconds(props.idleTimeoutSec()));
             BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            try {
                for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                    watchdog.touch();
                    if (!line.startsWith("data:")) {
                        continue; // 空行与 ": OPENROUTER PROCESSING" 之类的保活注释
                    }
                    String data = line.substring(5).strip();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    JsonNode chunk = parseChunk(data);
                    if (chunk.has("error") && !chunk.path("error").isNull() && chunk.path("choices").isEmpty()) {
                        throw failureOf(chunk.path("error").path("code").asInt(0), chunk.path("error").toString());
                    }
                    if (chunk.hasNonNull("provider")) {
                        provider = chunk.path("provider").asString();
                    }
                    if (chunk.hasNonNull("usage")) {
                        cost = chunk.path("usage").hasNonNull("cost") ? chunk.path("usage").path("cost").asDouble() : null;
                        usage = usageOf(chunk.path("usage"));
                    }
                    JsonNode choice = chunk.path("choices").path(0);
                    if (choice.hasNonNull("finish_reason")) {
                        finishReason = choice.path("finish_reason").asString();
                    }
                    String piece = choice.path("delta").path("content").asString("");
                    if (!piece.isEmpty()) {
                        if (firstTokenMs < 0) {
                            firstTokenMs = elapsedMs(started);
                        }
                        text.append(piece);
                        if (onDelta != null) {
                            onDelta.accept(piece);
                        }
                    }
                }
            } catch (IOException e) {
                String why = watchdog.timedOut() ? "超过 " + props.idleTimeoutSec() + " 秒没有数据" : e.getMessage();
                throw new ModelException(ModelException.Kind.TRANSIENT, "流中断：" + why, null);
            }
        }
        return new Completion(text.toString(), finishReason, cost, usage, provider, Math.max(firstTokenMs, 0), elapsedMs(started));
    }

    private JsonNode parseChunk(String data) {
        try {
            return json.readTree(data);
        } catch (JacksonException e) {
            throw new ModelException(ModelException.Kind.TRANSIENT, "无法解析的数据片段", null);
        }
    }

    private static Usage usageOf(JsonNode usage) {
        return new Usage(
                usage.hasNonNull("prompt_tokens") ? usage.path("prompt_tokens").asInt() : null,
                usage.hasNonNull("completion_tokens") ? usage.path("completion_tokens").asInt() : null,
                usage.path("completion_tokens_details").hasNonNull("reasoning_tokens")
                        ? usage.path("completion_tokens_details").path("reasoning_tokens").asInt() : null);
    }

    private ModelException failureOf(int status, String body) {
        String detail = body.length() > 500 ? body.substring(0, 500) : body;
        String message = "HTTP " + status + "：" + detail;
        if (status == 429 || (status >= 500 && status < 600)) {
            return new ModelException(ModelException.Kind.TRANSIENT, message, status);
        }
        if (status == 403 && looksLikeModeration(detail)) {
            return new ModelException(ModelException.Kind.REFUSED, message, status);
        }
        return new ModelException(ModelException.Kind.FATAL, message, status);
    }

    private static boolean looksLikeModeration(String body) {
        String lower = body.toLowerCase();
        return lower.contains("moderation") || lower.contains("flagged") || lower.contains("reasons");
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** 流太久没有任何数据（含保活注释）就关掉连接，让阻塞的读取以异常结束。 */
    private static final class IdleWatchdog implements AutoCloseable {
        private final Thread thread;
        private volatile long lastActivity = System.nanoTime();
        private volatile boolean timedOut;

        IdleWatchdog(InputStream stream, Duration idle) {
            this.thread = Thread.startVirtualThread(() -> {
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
                    // 正常结束或已关闭
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
