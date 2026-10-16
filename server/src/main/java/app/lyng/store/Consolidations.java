package app.lyng.store;

import static app.lyng.LyngApplication.USER;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 待整理的内心：某个人物的一段来源（一场戏，或一段私聊里 fromRef..toRef 的话）。 */
@Repository
public class Consolidations {

    public record Item(UUID id, UUID characterId, String sourceKind, UUID sourceId, long fromRef, long toRef,
                       String status, String failure, UUID runId, Instant createdAt) {}

    private static final String COLUMNS = "id, character_id, source_kind, source_id, from_ref, to_ref, status, failure, run_id, created_at";

    private final JdbcClient jdbc;

    public Consolidations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void add(UUID characterId, String sourceKind, UUID sourceId, long fromRef, long toRef) {
        jdbc.sql("""
                INSERT INTO consolidations (id, user_id, character_id, source_kind, source_id, from_ref, to_ref, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'pending')""")
                .params(UUID.randomUUID(), USER, characterId, sourceKind, sourceId, fromRef, toRef).update();
    }

    static final int TRIES = 3;

    /** 还没整理好的（待做，或失败不到三次的），先来的在前。 */
    public List<Item> unfinished(UUID characterId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM consolidations WHERE character_id = ? AND user_id = ? AND status <> 'done' AND tries < " + TRIES + " ORDER BY created_at")
                .params(characterId, USER).query(Item.class).list();
    }

    public List<UUID> charactersWithUnfinished() {
        return jdbc.sql("SELECT DISTINCT character_id FROM consolidations WHERE user_id = ? AND status <> 'done' AND tries < " + TRIES)
                .param(USER).query(UUID.class).list();
    }

    /** 某段私聊已经排进整理的最后一条话（没有为 0）。 */
    public long coveredUpTo(UUID characterId, UUID conversationId) {
        return jdbc.sql("SELECT COALESCE(MAX(to_ref), 0) FROM consolidations WHERE character_id = ? AND source_id = ? AND user_id = ?")
                .params(characterId, conversationId, USER).query(Long.class).single();
    }

    public void done(UUID id, UUID runId) {
        jdbc.sql("UPDATE consolidations SET status = 'done', failure = NULL, run_id = ?, done_at = now() WHERE id = ?")
                .params(runId, id).update();
    }

    public void failed(UUID id, String failure, UUID runId) {
        jdbc.sql("UPDATE consolidations SET status = 'failed', tries = tries + 1, failure = ?, run_id = ? WHERE id = ?")
                .params(failure, runId, id).update();
    }
}
