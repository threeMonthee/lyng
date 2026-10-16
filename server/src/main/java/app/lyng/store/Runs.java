package app.lyng.store;

import static app.lyng.LyngApplication.USER;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 幕后与用量：只读。写入在 Gateway。 */
@Repository
public class Runs {

    public record Summary(UUID id, String purpose, String title, UUID characterId, UUID sceneId, UUID conversationId,
                          UUID draftId, String status, String failure, int attempts, BigDecimal cost,
                          Instant startedAt, Instant finishedAt) {}

    public record Detail(UUID id, String purpose, String title, UUID characterId, UUID sceneId, UUID conversationId,
                         UUID draftId, String status, String request, String output, String reasoning, String tools,
                         String outcome, String failure, int attempts, BigDecimal cost, Instant startedAt,
                         Instant finishedAt) {}

    public record Call(int attempt, int step, String model, String provider, String outcome, String error,
                       BigDecimal cost, Integer latencyMs, Integer firstTokenMs, Integer promptTokens,
                       Integer completionTokens, Integer reasoningTokens, String finishReason, Instant createdAt) {}

    private static final String SUMMARY = """
            id, purpose, title, character_id, scene_id, conversation_id, draft_id, status, failure, attempts, cost,
            started_at, finished_at""";

    private final JdbcClient jdbc;

    public Runs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Summary> list(UUID characterId, UUID sceneId, UUID conversationId, int limit, int offset) {
        return jdbc.sql("SELECT " + SUMMARY + " " + """
                 FROM runs WHERE user_id = ?
                  AND (CAST(? AS uuid) IS NULL OR character_id = ?)
                  AND (CAST(? AS uuid) IS NULL OR scene_id = ?)
                  AND (CAST(? AS uuid) IS NULL OR conversation_id = ?)
                ORDER BY started_at DESC LIMIT ? OFFSET ?""")
                .params(USER, characterId, characterId, sceneId, sceneId, conversationId, conversationId, limit, offset)
                .query(Summary.class).list();
    }

    public Optional<Detail> detail(UUID id) {
        return jdbc.sql("""
                SELECT id, purpose, title, character_id, scene_id, conversation_id, draft_id, status, request::text AS request,
                       output, reasoning, tools::text AS tools, outcome::text AS outcome, failure, attempts, cost,
                       started_at, finished_at
                FROM runs WHERE id = ? AND user_id = ?""")
                .params(id, USER).query(Detail.class).optional();
    }

    public List<Call> calls(UUID runId) {
        return jdbc.sql("""
                SELECT attempt, step, model, provider, outcome, error, cost, latency_ms, first_token_ms, prompt_tokens,
                       completion_tokens, reasoning_tokens, finish_reason, created_at
                FROM model_calls WHERE run_id = ? AND user_id = ? ORDER BY id""")
                .params(runId, USER).query(Call.class).list();
    }

    public record Usage(String purpose, long calls, long failures, BigDecimal cost, Long medianMs) {}

    public List<Usage> usage() {
        return jdbc.sql("""
                SELECT purpose, count(*) AS calls, count(*) FILTER (WHERE outcome IN ('error', 'refused')) AS failures,
                       COALESCE(SUM(cost), 0) AS cost,
                       CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) AS BIGINT) AS median_ms
                FROM model_calls WHERE user_id = ? GROUP BY purpose ORDER BY SUM(cost) DESC NULLS LAST""")
                .param(USER).query(Usage.class).list();
    }

    public record Day(String day, BigDecimal cost, long calls) {}

    public List<Day> days(int limit) {
        return jdbc.sql("""
                SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS day, COALESCE(SUM(cost), 0) AS cost,
                       count(*) AS calls
                FROM model_calls WHERE user_id = ? GROUP BY 1 ORDER BY 1 DESC LIMIT ?""")
                .params(USER, limit).query(Day.class).list();
    }
}
