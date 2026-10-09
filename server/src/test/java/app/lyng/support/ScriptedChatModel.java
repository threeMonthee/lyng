package app.lyng.support;

import app.lyng.model.ChatModel;
import app.lyng.model.ChatRequest;
import app.lyng.model.Completion;
import app.lyng.model.FakeChatModel;
import app.lyng.model.ModelException;
import app.lyng.model.Usage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 测试用的确定性模型替身：默认回复同 FakeChatModel；可以按调用用空响应、截断、坏格式、请求错误等覆盖，
 * 并记录每一次收到的请求（含发给模型的完整消息）。
 */
public class ScriptedChatModel implements ChatModel {

    /** 脚本给出的回复。error 非空表示这次请求没拿到响应。 */
    public record Reply(String text, String finishReason, ModelException error) {

        public static Reply text(String text) {
            return new Reply(text, "stop", null);
        }

        public static Reply truncated(String text) {
            return new Reply(text, "length", null);
        }

        public static Reply fail(ModelException.Kind kind, int status) {
            return new Reply(null, null, new ModelException(kind, "HTTP " + status, status));
        }
    }

    private final FakeChatModel fallback = new FakeChatModel(5, 0);
    private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<ChatRequest, Reply> script = request -> null;

    public void script(Function<ChatRequest, Reply> script) {
        this.script = script;
    }

    public void reset() {
        script = request -> null;
        requests.clear();
    }

    public void clearRequests() {
        requests.clear();
    }

    public List<ChatRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public List<ChatRequest> requests(String kind) {
        return requests().stream().filter(r -> r.purpose().kind().equals(kind)).toList();
    }

    /** 某一类调用发给模型的全部文字（system 与 user 拼在一起），便于断言上下文里有什么。 */
    public String promptOf(ChatRequest request) {
        return String.join("\n", request.messages().stream().map(m -> m.content()).toList());
    }

    @Override
    public Completion complete(ChatRequest request, Consumer<String> onDelta) {
        requests.add(request);
        Reply reply = script.apply(request);
        if (reply == null) {
            return fallback.complete(request, onDelta);
        }
        if (reply.error() != null) {
            throw reply.error();
        }
        for (int i = 0; i < reply.text().length(); i += 4) {
            onDelta.accept(reply.text().substring(i, Math.min(reply.text().length(), i + 4)));
        }
        return new Completion(reply.text(), reply.finishReason(), 0.0001, new Usage(0, 0, 0), "脚本", 0, 0);
    }
}
