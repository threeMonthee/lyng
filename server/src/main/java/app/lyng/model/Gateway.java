package app.lyng.model;

import app.lyng.model.ChatModel.Completion;
import app.lyng.model.ChatModel.Message;
import app.lyng.model.ChatModel.Purpose;
import app.lyng.model.ChatModel.Request;
import app.lyng.store.Store;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 所有模型调用的唯一入口：费用上限 → 调用 → 校验 → 记账。
 *
 * <p>限流、5xx、网络错误、空响应、截断、格式不合规自动重试一次；服务商拒绝、密钥或余额问题、费用上限不重试。
 * 仍失败就抛 CallFailure。一次调用没有成功，就没有过“人物的回应”，所以重试不是重抽人物的选择。
 */
@Service
public class Gateway {

    private static final int ATTEMPTS = 2;

    private final ChatModel chat;
    private final ModelProperties props;
    private final JdbcClient jdbc;

    public Gateway(ChatModel chat, ModelProperties props, JdbcClient jdbc) {
        this.chat = chat;
        this.props = props;
        this.jdbc = jdbc;
    }

    public <T> T call(Role role, Purpose purpose, List<Message> messages, Supplier<Attempt<T>> attempts) {
        Choice choice = props.choice(role);
        Request request = new Request(choice, messages, props.maxTokens(), purpose);
        String error = null;
        for (int n = 1; n <= ATTEMPTS; n++) {
            checkBudget();
            Attempt<T> attempt = attempts.get();
            Completion done;
            try {
                done = chat.complete(request, attempt::onDelta);
            } catch (ModelException e) {
                attempt.abandon();
                error = e.getMessage();
                record(purpose, choice, null, e.kind() == ModelException.Kind.REFUSED ? "refused" : "error", error);
                if (e.kind() == ModelException.Kind.REFUSED) {
                    throw new CallFailure("服务商拒绝了这次生成");
                }
                if (e.kind() == ModelException.Kind.FATAL) {
                    throw new CallFailure("请求失败：" + e.getMessage());
                }
                pause(n);
                continue;
            } catch (FormatError e) {
                // 流式解析中途发现不合规（例如写了别人的言行），这次生成到此为止
                attempt.abandon();
                error = "格式不合规：" + e.getMessage();
                record(purpose, choice, null, "error", error);
                continue;
            }
            if (done.cost() == null) {
                attempt.abandon();
                record(purpose, choice, done, "error", "接口没有返回费用");
                throw new CallFailure("接口没有返回费用，无法记账");
            }
            if ("content_filter".equals(done.finishReason())) {
                attempt.abandon();
                record(purpose, choice, done, "refused", "服务商内容过滤");
                throw new CallFailure("服务商拒绝了这次生成");
            }
            error = defect(done);
            if (error == null) {
                try {
                    T result = attempt.finish(done.text());
                    record(purpose, choice, done, "ok", null);
                    return result;
                } catch (FormatError e) {
                    error = "格式不合规：" + e.getMessage();
                }
            }
            attempt.abandon();
            record(purpose, choice, done, "error", error);
        }
        throw new CallFailure(error + "（已自动重试一次）");
    }

    private static String defect(Completion done) {
        if ("length".equals(done.finishReason())) {
            return "生成被截断";
        }
        if ("error".equals(done.finishReason())) {
            return "生成出错";
        }
        return done.text().isBlank() ? "空响应" : null;
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

    /** 已花费用达到上限就停。并发的最后一批调用最多超出一次调用的费用。 */
    private void checkBudget() {
        if (props.budgetUsd() == null) {
            return;
        }
        double spent = spent();
        if (spent >= props.budgetUsd()) {
            throw new CallFailure("已达费用上限 %.2f 美元（已花 %.4f）".formatted(props.budgetUsd(), spent));
        }
    }

    public double spent() {
        return jdbc.sql("SELECT COALESCE(SUM(cost), 0) FROM model_calls WHERE user_id = ?")
                .param(Store.USER).query(Double.class).single();
    }

    public Double budget() {
        return props.budgetUsd();
    }

    private void record(Purpose purpose, Choice choice, Completion done, String outcome, String error) {
        jdbc.sql("""
                INSERT INTO model_calls (user_id, scene_id, kind, subject, model, provider, outcome, error, cost,
                    latency_ms, first_token_ms, prompt_tokens, completion_tokens, reasoning_tokens, finish_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(Store.USER, purpose.sceneId(), purpose.kind(), purpose.subject(), choice.spec(),
                        done == null ? null : done.provider(), outcome, error, done == null ? null : done.cost(),
                        done == null ? null : done.latencyMs(), done == null ? null : done.firstTokenMs(),
                        done == null ? null : done.promptTokens(), done == null ? null : done.completionTokens(),
                        done == null ? null : done.reasoningTokens(), done == null ? null : done.finishReason())
                .update();
    }
}
