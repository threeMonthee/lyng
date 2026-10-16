package app.lyng.support;

import app.lyng.model.ChatModel;
import app.lyng.model.Completion;
import app.lyng.model.FakeModel;
import app.lyng.model.ModelException;
import app.lyng.model.ModelRequest;
import app.lyng.model.Purpose;
import app.lyng.model.StreamListener;
import app.lyng.model.ToolCall;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 测试用的模型：按用途排好的回答依次交出（流式，逐字），可以注入失败、截断、工具调用。
 * 某个用途没有排好的回答时，交给确定性替身，让后台的整理等照常进行。所有请求都记下来供断言。
 */
public class ScriptedModel implements ChatModel {

    private final FakeModel fallback = new FakeModel(0);
    private record Step(Predicate<ModelRequest> when, Function<ModelRequest, Completion> answer) {}

    private final Map<Purpose, Deque<Step>> scripts = new ConcurrentHashMap<>();
    private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

    public ScriptedModel reply(Purpose purpose, String... texts) {
        for (String text : texts) {
            add(purpose, r -> completion(text, "stop", 0.001));
        }
        return this;
    }

    /** 回答按请求现写（例如要引用请求里给出的 id）。 */
    public ScriptedModel replyWith(Purpose purpose, Function<ModelRequest, String> text) {
        add(purpose, r -> completion(text.apply(r), "stop", 0.001));
        return this;
    }

    /** 工具调用的参数按请求现写。 */
    public ScriptedModel toolCallWith(Purpose purpose, String name, Function<ModelRequest, String> arguments) {
        add(purpose, r -> new Completion("", null, List.of(new ToolCall("call-" + requests.size(), name,
                arguments.apply(r))), "tool_calls", 0.001, "scripted", 10, 5, 0, 1, 2));
        return this;
    }

    /** 只回答满足条件的请求（例如后台并行整理时按人物名字分派）。 */
    public ScriptedModel replyWhen(Purpose purpose, Predicate<ModelRequest> when, String text) {
        add(purpose, when, r -> completion(text, "stop", 0.001));
        return this;
    }

    /** 请求 meta 里的人物名字。 */
    public static Predicate<ModelRequest> forName(String name) {
        return r -> r.meta() != null && name.equals(r.meta().get("name"));
    }

    public ScriptedModel fail(Purpose purpose, ModelException.Kind kind, String message) {
        add(purpose, r -> {
            throw new ModelException(kind, message);
        });
        return this;
    }

    public ScriptedModel truncated(Purpose purpose, String text) {
        add(purpose, r -> completion(text, "length", 0.001));
        return this;
    }

    public ScriptedModel withoutCost(Purpose purpose, String text) {
        add(purpose, r -> completion(text, "stop", null));
        return this;
    }

    public ScriptedModel toolCall(Purpose purpose, String name, String arguments) {
        add(purpose, r -> new Completion("", null, List.of(new ToolCall("call-" + requests.size(), name, arguments)),
                "tool_calls", 0.001, "scripted", 10, 5, 0, 1, 2));
        return this;
    }

    public synchronized void reset() {
        scripts.clear();
        requests.clear();
    }

    public List<ModelRequest> requests(Purpose purpose) {
        return requests.stream().filter(r -> r.purpose() == purpose).toList();
    }

    public List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    public int remaining(Purpose purpose) {
        Deque<?> queue = scripts.get(purpose);
        return queue == null ? 0 : queue.size();
    }

    private void add(Purpose purpose, Function<ModelRequest, Completion> answer) {
        add(purpose, r -> true, answer);
    }

    private synchronized void add(Purpose purpose, Predicate<ModelRequest> when, Function<ModelRequest, Completion> answer) {
        scripts.computeIfAbsent(purpose, p -> new ArrayDeque<>()).add(new Step(when, answer));
    }

    @Override
    public Completion complete(ModelRequest request, StreamListener listener) {
        requests.add(request);
        Step step = null;
        synchronized (this) {
            Deque<Step> queue = scripts.get(request.purpose());
            if (queue != null) {
                for (Step s : queue) {
                    if (s.when().test(request)) {
                        step = s;
                        break;
                    }
                }
                if (step != null) {
                    queue.remove(step);
                }
            }
        }
        if (step == null) {
            return fallback.complete(request, listener);
        }
        Completion done = step.answer().apply(request);
        if (done.text() != null && done.toolCalls().isEmpty()) {
            for (int i = 0; i < done.text().length(); i++) {
                listener.content(done.text().substring(i, i + 1));
            }
        }
        return done;
    }

    private static Completion completion(String text, String finish, Double cost) {
        return new Completion(text, "（思考）", List.of(), finish, cost, "scripted", 100, text.length(), 3, 1, 2);
    }
}
