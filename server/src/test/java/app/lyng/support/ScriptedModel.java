package app.lyng.support;

import app.lyng.model.ChatModel;
import app.lyng.model.FakeModel;
import app.lyng.model.ModelException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 测试用的模型替身：默认回复同 FakeModel；可以按请求覆盖成空响应、截断、坏格式、请求错误等，
 * 并记录每一次收到的请求（含发给模型的完整消息）。
 */
public class ScriptedModel implements ChatModel {

    /** 脚本给出的回复。error 非空表示这次请求没拿到响应。 */
    public record Reply(String text, String finishReason, ModelException error) {

        public static Reply text(String text) {
            return new Reply(text, "stop", null);
        }

        public static Reply truncated(String text) {
            return new Reply(text, "length", null);
        }

        public static Reply fail(ModelException.Kind kind) {
            return new Reply(null, null, new ModelException(kind, "HTTP 5xx（脚本）"));
        }
    }

    private final FakeModel fallback = new FakeModel(0);
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Request, Reply> script = request -> null;

    public void script(Function<Request, Reply> script) {
        this.script = script;
    }

    public void reset() {
        script = request -> null;
        requests.clear();
    }

    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public List<Request> requests(String kind) {
        return requests().stream().filter(r -> r.purpose().kind().equals(kind)).toList();
    }

    public List<String> kinds() {
        return requests().stream().map(r -> r.purpose().kind() + (r.purpose().subject() == null ? "" : ":" + r.purpose().subject())).toList();
    }

    /** 一次请求发给模型的全部文字。 */
    public static String prompt(Request request) {
        return String.join("\n", request.messages().stream().map(Message::content).toList());
    }

    public String lastPrompt(String kind) {
        return prompt(requests(kind).getLast());
    }

    @Override
    public Completion complete(Request request, Consumer<String> onDelta) {
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
        return new Completion(reply.text(), reply.finishReason(), 0.0001, "脚本", 0, 0, 0, 0, 0);
    }
}
