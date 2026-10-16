package app.lyng.model;

import app.lyng.LyngApplication;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 所有模型调用的唯一入口。一次“运行”= 一件要模型做的事（某人开口、一次整理……），可以有两次尝试，
 * 每次尝试里可以有几轮工具调用。运行与每次请求都落库：幕后观察和记账看的是同一份记录。
 *
 * <p>限流、5xx、网络错误、空响应、截断、格式不合规自动重试一次；服务商拒绝、密钥或余额问题、费用上限不重试。
 * 一次运行没有成功，就没有过“人物的回应”，所以重试不是重抽人物的选择。
 */
@Service
public class Gateway {

    static final int ATTEMPTS = 2;
    static final int TOOL_ROUNDS = 4;

    /** 运行关联的对象，用于幕后按人物、戏、私聊、草稿查找。 */
    public record Links(UUID characterId, UUID sceneId, UUID conversationId, UUID draftId) {
        public static final Links NONE = new Links(null, null, null, null);
    }

    public record Spec(Purpose purpose, String title, Links links, List<Msg> messages, List<Tool> tools,
                       Function<ToolCall, String> toolRunner, Map<String, Object> meta) {

        public static Spec of(Purpose purpose, String title, Links links, List<Msg> messages, Map<String, Object> meta) {
            return new Spec(purpose, title, links, messages, List.of(), null, meta);
        }
    }

    /**
     * 一次尝试的接收方：流式内容逐段进来（可抛 FormatError 让这次尝试作废），最后整段校验并解析。
     * abandon 表示目前收到的内容作废（尝试失败，或这一轮是工具调用），之后可能还会继续收内容。
     */
    public interface Attempt<T> {
        default void content(String delta) {}

        default void reasoning(String delta) {}

        default void abandon() {}

        T finish(String text);
    }

    public record Result<T>(UUID runId, T value) {}

    private final ChatModel chat;
    private final ModelProperties props;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Gateway(ChatModel chat, ModelProperties props, JdbcClient jdbc, JsonMapper json) {
        this.chat = chat;
        this.props = props;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 不需要流式的调用：整段解析。 */
    public <T> Result<T> run(Spec spec, Function<String, T> parser) {
        return run(spec, () -> parser::apply);
    }

    public <T> Result<T> run(Spec spec, Supplier<Attempt<T>> attempts) {
        ModelProperties.Choice choice = props.choice(spec.purpose());
        UUID runId = UUID.randomUUID();
        start(runId, spec, choice);
        String error = null;
        StringBuilder reasoning = new StringBuilder();
        List<Map<String, Object>> toolLog = new ArrayList<>();
        int attemptNo = 0;
        for (int n = 1; n <= ATTEMPTS; n++) {
            attemptNo = n;
            Attempt<T> attempt = attempts.get();
            List<Msg> convo = new ArrayList<>(spec.messages());
            try {
                for (int step = 0; ; step++) {
                    checkBudget(runId);
                    boolean toolsAllowed = spec.tools() != null && !spec.tools().isEmpty() && step < TOOL_ROUNDS;
                    ModelRequest request = new ModelRequest(spec.purpose(), choice.model(), choice.reasoning(),
                            List.copyOf(convo), toolsAllowed ? spec.tools() : List.of(), props.maxTokens(), spec.meta());
                    Completion done;
                    try {
                        done = chat.complete(request, new StreamListener() {
                            @Override
                            public void content(String delta) {
                                attempt.content(delta);
                            }

                            @Override
                            public void reasoning(String delta) {
                                attempt.reasoning(delta);
                            }
                        });
                    } catch (ModelException e) {
                        record(runId, spec.purpose(), choice, n, step, null,
                                e.kind() == ModelException.Kind.REFUSED ? "refused" : "error", e.getMessage());
                        throw e;
                    } catch (FormatError e) {
                        record(runId, spec.purpose(), choice, n, step, null, "error", "格式不合规：" + e.getMessage());
                        throw e;
                    }
                    if (done.reasoning() != null) {
                        reasoning.append(done.reasoning()).append('\n');
                    }
                    if (done.cost() == null) {
                        record(runId, spec.purpose(), choice, n, step, done, "error", "接口没有返回费用");
                        throw new ModelException(ModelException.Kind.FATAL, "接口没有返回费用，无法记账");
                    }
                    if ("content_filter".equals(done.finishReason())) {
                        record(runId, spec.purpose(), choice, n, step, done, "refused", "服务商内容过滤");
                        throw new ModelException(ModelException.Kind.REFUSED, "服务商拒绝了这次生成");
                    }
                    if (toolsAllowed && !done.toolCalls().isEmpty()) {
                        record(runId, spec.purpose(), choice, n, step, done, "tool", null);
                        attempt.abandon();
                        convo.add(Msg.assistant(done.text(), done.toolCalls()));
                        for (ToolCall call : done.toolCalls()) {
                            String result = runTool(spec, call);
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("attempt", n);
                            entry.put("name", call.name());
                            entry.put("arguments", call.arguments());
                            entry.put("result", result);
                            toolLog.add(entry);
                            convo.add(Msg.tool(call.id(), result));
                        }
                        continue;
                    }
                    String defect = defect(done);
                    if (defect != null) {
                        record(runId, spec.purpose(), choice, n, step, done, "error", defect);
                        throw new ModelException(ModelException.Kind.TRANSIENT, defect);
                    }
                    T value;
                    try {
                        value = attempt.finish(done.text());
                    } catch (FormatError e) {
                        record(runId, spec.purpose(), choice, n, step, done, "error", "格式不合规：" + e.getMessage());
                        throw e;
                    }
                    record(runId, spec.purpose(), choice, n, step, done, "ok", null);
                    finish(runId, "ok", done.text(), reasoning, toolLog, null, n);
                    return new Result<>(runId, value);
                }
            } catch (ModelException e) {
                attempt.abandon();
                error = e.getMessage();
                if (e.kind() != ModelException.Kind.TRANSIENT) {
                    String message = e.kind() == ModelException.Kind.REFUSED ? "服务商拒绝了这次生成" : "请求失败：" + e.getMessage();
                    finish(runId, "failed", null, reasoning, toolLog, message, n);
                    throw new CallFailure(message, runId);
                }
                pause(n);
            } catch (FormatError e) {
                attempt.abandon();
                error = "格式不合规：" + e.getMessage();
            } catch (BudgetExceeded e) {
                attempt.abandon();
                finish(runId, "failed", null, reasoning, toolLog, e.getMessage(), n);
                throw new CallFailure(e.getMessage(), runId);
            }
        }
        String message = error + "（已自动重试一次）";
        finish(runId, "failed", null, reasoning, toolLog, message, attemptNo);
        throw new CallFailure(message, runId);
    }

    /** 调用方把这次运行“保存了什么”记下来，幕后能看到。 */
    public void outcome(UUID runId, Object outcome) {
        jdbc.sql("UPDATE runs SET outcome = ?::jsonb WHERE id = ? AND user_id = ?")
                .params(json.writeValueAsString(outcome), runId, LyngApplication.USER).update();
    }

    private String runTool(Spec spec, ToolCall call) {
        if (spec.toolRunner() == null) {
            return "没有这个工具。";
        }
        try {
            return spec.toolRunner().apply(call);
        } catch (RuntimeException e) {
            return "这次没有翻到：" + e.getMessage();
        }
    }

    private static String defect(Completion done) {
        if ("length".equals(done.finishReason())) {
            return "生成被截断";
        }
        if ("error".equals(done.finishReason())) {
            return "生成出错";
        }
        return done.text() == null || done.text().isBlank() ? "空响应" : null;
    }

    private void pause(int attempt) {
        if (attempt < ATTEMPTS && props.backoffMs() > 0) {
            try {
                Thread.sleep(props.backoffMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded(String message) {
            super(message);
        }
    }

    /** 已花费用达到上限就停。并发时最多超出正在进行的那几次调用的费用。 */
    private void checkBudget(UUID runId) {
        Double budget = budget();
        if (budget == null) {
            return;
        }
        double spent = spent();
        if (spent >= budget) {
            throw new BudgetExceeded("已达费用上限 %.2f 美元（已花 %.4f），可在设置里调整".formatted(budget, spent));
        }
    }

    public double spent() {
        return jdbc.sql("SELECT COALESCE(SUM(cost), 0) FROM model_calls WHERE user_id = ?")
                .param(LyngApplication.USER).query(Double.class).single();
    }

    /** 设置里的上限优先；没设置时用环境变量 LYNG_BUDGET_USD；都没有就不设上限。 */
    public Double budget() {
        List<BigDecimal> set = jdbc.sql("SELECT budget_usd FROM settings WHERE user_id = ?").param(LyngApplication.USER)
                .query((rs, row) -> rs.getBigDecimal(1)).list();
        if (set.isEmpty() || set.getFirst() == null) {
            return props.budgetUsd();
        }
        return set.getFirst().doubleValue();
    }

    private void start(UUID runId, Spec spec, ModelProperties.Choice choice) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", choice.spec());
        request.put("messages", spec.messages());
        request.put("tools", spec.tools() == null ? List.of() : spec.tools().stream().map(Tool::name).toList());
        Links l = spec.links() == null ? Links.NONE : spec.links();
        jdbc.sql("""
                INSERT INTO runs (id, user_id, purpose, title, character_id, scene_id, conversation_id, draft_id,
                    status, request)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'running', ?::jsonb)""")
                .params(runId, LyngApplication.USER, spec.purpose().name(), spec.title(), l.characterId(), l.sceneId(),
                        l.conversationId(), l.draftId(), json.writeValueAsString(request))
                .update();
    }

    private void finish(UUID runId, String status, String output, StringBuilder reasoning,
                        List<Map<String, Object>> toolLog, String failure, int attempts) {
        jdbc.sql("""
                UPDATE runs SET status = ?, output = ?, reasoning = ?, tools = ?::jsonb, failure = ?, attempts = ?,
                    cost = (SELECT COALESCE(SUM(cost), 0) FROM model_calls WHERE run_id = ?), finished_at = now()
                WHERE id = ?""")
                .params(status, output, reasoning.isEmpty() ? null : reasoning.toString().strip(),
                        json.writeValueAsString(toolLog), failure, attempts, runId, runId)
                .update();
    }

    private void record(UUID runId, Purpose purpose, ModelProperties.Choice choice, int attempt, int step,
                        Completion done, String outcome, String error) {
        jdbc.sql("""
                INSERT INTO model_calls (user_id, run_id, attempt, step, purpose, model, provider, outcome, error, cost,
                    latency_ms, first_token_ms, prompt_tokens, completion_tokens, reasoning_tokens, finish_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(LyngApplication.USER, runId, attempt, step, purpose.name(), choice.spec(),
                        done == null ? null : done.provider(), outcome, error, done == null ? null : done.cost(),
                        done == null ? null : (int) done.latencyMs(), done == null ? null : (int) done.firstTokenMs(),
                        done == null ? null : done.promptTokens(), done == null ? null : done.completionTokens(),
                        done == null ? null : done.reasoningTokens(), done == null ? null : done.finishReason())
                .update();
    }
}
