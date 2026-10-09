package app.lyng.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 所有模型调用的唯一入口：预算检查 → 调用 → 校验 → 记账。
 *
 * <p>技术失败（限流、5xx、网络、空响应、截断、格式不合规）自动重试一次，仍失败就抛 CallFailure，
 * 由调用方呈现成技术失败。人物的选择不在这里重抽：一次调用没有成功，就没有过“人物的回应”。
 */
@Service
public class ModelGateway {

    private static final int ATTEMPTS = 2;

    private final ChatModel chat;
    private final ModelSettings settings;
    private final ModelCallRepository calls;
    private final ModelProperties props;

    public ModelGateway(ChatModel chat, ModelSettings settings, ModelCallRepository calls, ModelProperties props) {
        this.chat = chat;
        this.settings = settings;
        this.calls = calls;
        this.props = props;
    }

    public <T> T call(UUID userId, ModelRole role, CallPurpose purpose, List<ChatMessage> messages,
                      Supplier<Attempt<T>> attempts) {
        ModelChoice choice = settings.choice(userId, role);
        ChatRequest request = new ChatRequest(choice, messages, props.maxTokens(), purpose);
        String lastError = null;
        for (int n = 1; n <= ATTEMPTS; n++) {
            checkBudget(userId);
            Attempt<T> attempt = attempts.get();
            Completion completion;
            try {
                completion = chat.complete(request, attempt::onDelta);
            } catch (ModelException e) {
                attempt.abandon();
                lastError = describe(e);
                record(userId, purpose, choice, null, e.kind() == ModelException.Kind.REFUSED ? "refused" : "error", lastError);
                switch (e.kind()) {
                    case REFUSED -> throw new CallFailure(CallFailure.Kind.REFUSED, "服务商拒绝：" + e.getMessage());
                    case FATAL -> throw new CallFailure(CallFailure.Kind.STOPPED, lastError);
                    case TRANSIENT -> pauseBeforeRetry(n);
                }
                continue;
            } catch (FormatError e) {
                attempt.abandon();
                lastError = "格式不合规：" + e.getMessage();
                record(userId, purpose, choice, null, "error", lastError);
                continue;
            }
            if (completion.cost() == null) {
                record(userId, purpose, choice, completion, "error", "接口未返回费用");
                throw new CallFailure(CallFailure.Kind.STOPPED, "接口未返回费用，无法记账");
            }
            if ("content_filter".equals(completion.finishReason())) {
                record(userId, purpose, choice, completion, "refused", "服务商内容过滤");
                throw new CallFailure(CallFailure.Kind.REFUSED, "服务商拒绝：内容过滤");
            }
            lastError = defect(completion);
            if (lastError == null) {
                try {
                    T result = attempt.finish(completion.text());
                    record(userId, purpose, choice, completion, "ok", null);
                    return result;
                } catch (FormatError e) {
                    lastError = "格式不合规：" + e.getMessage();
                }
            }
            attempt.abandon();
            record(userId, purpose, choice, completion, "error", lastError);
        }
        throw new CallFailure(CallFailure.Kind.TECHNICAL, lastError + "（已重试一次）");
    }

    private void checkBudget(UUID userId) {
        if (props.budgetUsd() == null) {
            return;
        }
        BigDecimal spent = calls.totalCost(userId);
        if (spent.doubleValue() >= props.budgetUsd()) {
            throw new CallFailure(CallFailure.Kind.STOPPED,
                    "已达费用上限 " + props.budgetUsd() + " 美元（已花 " + spent.toPlainString() + "）");
        }
    }

    private void pauseBeforeRetry(int attempt) {
        if (attempt >= ATTEMPTS || props.backoffMs() <= 0) {
            return;
        }
        try {
            Thread.sleep(props.backoffMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String defect(Completion completion) {
        if ("length".equals(completion.finishReason())) {
            return "截断";
        }
        if ("error".equals(completion.finishReason())) {
            return "生成出错";
        }
        return completion.text().isBlank() ? "空响应" : null;
    }

    private static String describe(ModelException e) {
        return e.status() != null ? "请求失败：HTTP " + e.status() : "请求失败：" + e.getMessage();
    }

    private void record(UUID userId, CallPurpose purpose, ModelChoice choice, Completion c, String outcome, String error) {
        Usage usage = c == null ? Usage.NONE : c.usage();
        calls.insert(new ModelCall(null, userId, purpose.sceneId(), purpose.kind(), purpose.subject(), choice.model(),
                choice.reasoning(), c == null ? null : c.provider(), outcome, error,
                c == null || c.cost() == null ? null : BigDecimal.valueOf(c.cost()),
                c == null ? null : c.latencyMs(), c == null ? null : c.firstTokenMs(),
                usage.promptTokens(), usage.completionTokens(), usage.reasoningTokens(),
                c == null ? null : c.finishReason(), null));
    }
}
