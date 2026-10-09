package app.lyng.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ModelCallRepository {

    private final JdbcClient jdbc;

    public ModelCallRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ModelCall c) {
        jdbc.sql("""
                INSERT INTO model_calls (user_id, scene_id, kind, subject, model, reasoning, provider, outcome, error,
                    cost, latency_ms, first_token_ms, prompt_tokens, completion_tokens, reasoning_tokens, finish_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(c.userId(), c.sceneId(), c.kind(), c.subject(), c.model(), c.reasoning(), c.provider(),
                        c.outcome(), c.error(), c.cost(), c.latencyMs(), c.firstTokenMs(), c.promptTokens(),
                        c.completionTokens(), c.reasoningTokens(), c.finishReason())
                .update();
    }

    public BigDecimal totalCost(UUID userId) {
        return jdbc.sql("SELECT COALESCE(SUM(cost), 0) FROM model_calls WHERE user_id = ?")
                .param(userId).query(BigDecimal.class).single();
    }

    public List<ModelCall> recent(UUID userId, int limit) {
        return jdbc.sql("SELECT * FROM model_calls WHERE user_id = ? ORDER BY id DESC LIMIT ?")
                .params(userId, limit)
                .query((rs, n) -> new ModelCall(
                        rs.getLong("id"), userId, rs.getObject("scene_id", UUID.class), rs.getString("kind"),
                        rs.getString("subject"), rs.getString("model"), rs.getString("reasoning"),
                        rs.getString("provider"), rs.getString("outcome"), rs.getString("error"),
                        rs.getBigDecimal("cost"), nullableLong(rs.getObject("latency_ms")),
                        nullableLong(rs.getObject("first_token_ms")), (Integer) rs.getObject("prompt_tokens"),
                        (Integer) rs.getObject("completion_tokens"), (Integer) rs.getObject("reasoning_tokens"),
                        rs.getString("finish_reason"), rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .list();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
