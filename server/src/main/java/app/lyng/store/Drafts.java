package app.lyng.store;

import static app.lyng.LyngApplication.USER;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 一念生的草稿。questions / answers / content / check_result 是 JSON 原文，由 create 包解释。 */
@Repository
public class Drafts {

    public record Draft(UUID id, UUID worldId, String request, String status, String questions, String answers,
                        String content, String checkResult, String failure, Instant createdAt, Instant updatedAt) {}

    private static final String COLUMNS = """
            id, world_id, request, status, questions::text AS questions, answers::text AS answers,
            content::text AS content, check_result::text AS check_result, failure, created_at, updated_at""";

    private final JdbcClient jdbc;

    public Drafts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(UUID worldId, String request) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO drafts (id, user_id, world_id, request, status) VALUES (?, ?, ?, ?, 'asking')")
                .params(id, USER, worldId, request).update();
        return id;
    }

    public Optional<Draft> draft(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM drafts WHERE id = ? AND user_id = ?").params(id, USER)
                .query(Draft.class).optional();
    }

    public Draft require(UUID id) {
        return draft(id).orElseThrow(() -> new NotFound("没有这份草稿"));
    }

    public List<Draft> unfinished() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM drafts WHERE user_id = ? AND status IN ('asking', 'writing', 'ready', 'failed') ORDER BY updated_at DESC")
                .param(USER).query(Draft.class).list();
    }

    public void update(UUID id, String status, String questions, String answers, String content, String checkResult,
                       String failure) {
        jdbc.sql("""
                UPDATE drafts SET status = ?, questions = ?::jsonb, answers = ?::jsonb, content = ?::jsonb,
                    check_result = ?::jsonb, failure = ?, updated_at = now()
                WHERE id = ? AND user_id = ?""")
                .params(status, questions, answers, content, checkResult, failure, id, USER).update();
    }

    public void interrupted(String failure) {
        jdbc.sql("UPDATE drafts SET status = 'failed', failure = ?, updated_at = now() WHERE user_id = ? AND status = 'writing'")
                .params(failure, USER).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM drafts WHERE id = ? AND user_id = ? AND status <> 'born'").params(id, USER).update();
    }
}
