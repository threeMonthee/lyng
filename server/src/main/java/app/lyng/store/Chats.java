package app.lyng.store;

import static app.lyng.LyngApplication.USER;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 私聊：两个人物之间唯一、永久的一段，话只追加。 */
@Repository
public class Chats {

    public record Conversation(UUID id, UUID aId, UUID bId, UUID playedId, List<String> pending, String failure,
                               Instant createdAt, Instant lastAt) {

        public UUID other(UUID characterId) {
            return characterId.equals(aId) ? bId : aId;
        }

        public boolean has(UUID characterId) {
            return characterId.equals(aId) || characterId.equals(bId);
        }
    }

    public record Message(long id, UUID speakerId, String kind, String text, boolean byUser, String opportunity,
                          boolean unread, UUID runId, Instant createdAt) {}

    private static final String COLUMNS = "id, a_id, b_id, played_id, pending, failure, created_at, last_at";
    private static final String MESSAGE_COLUMNS = "id, speaker_id, kind, text, by_user, opportunity, unread, run_id, created_at";

    private final JdbcClient jdbc;

    public Chats(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 两个人物之间的私聊；没有就建一段。 */
    public Conversation open(UUID x, UUID y) {
        // 按 PostgreSQL 的 uuid 顺序（等同于字符串顺序；Java 的 UUID.compareTo 按有符号数比较，不一样）
        UUID a = x.toString().compareTo(y.toString()) < 0 ? x : y;
        UUID b = a.equals(x) ? y : x;
        jdbc.sql("INSERT INTO conversations (id, user_id, a_id, b_id) VALUES (?, ?, ?, ?) ON CONFLICT (a_id, b_id) DO NOTHING")
                .params(UUID.randomUUID(), USER, a, b).update();
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversations WHERE a_id = ? AND b_id = ? AND user_id = ?")
                .params(a, b, USER).query(Chats::conversation).single();
    }

    public Optional<Conversation> conversation(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversations WHERE id = ? AND user_id = ?")
                .params(id, USER).query(Chats::conversation).optional();
    }

    public Conversation require(UUID id) {
        return conversation(id).orElseThrow(() -> new NotFound("没有这段私聊"));
    }

    /** 有过话的私聊，最近的在前。 */
    public List<Conversation> active() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversations c WHERE user_id = ? AND EXISTS (SELECT 1 FROM messages m WHERE m.conversation_id = c.id) ORDER BY last_at DESC")
                .param(USER).query(Chats::conversation).list();
    }

    public List<Conversation> with(UUID characterId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversations WHERE user_id = ? AND (a_id = ? OR b_id = ?) ORDER BY last_at DESC")
                .params(USER, characterId, characterId).query(Chats::conversation).list();
    }

    public List<Message> messages(UUID conversationId, long beforeId, int limit) {
        return jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM (SELECT * FROM messages WHERE conversation_id = ? AND user_id = ? AND id < ? ORDER BY id DESC LIMIT ?) m ORDER BY id")
                .params(conversationId, USER, beforeId, limit).query(Message.class).list();
    }

    public List<Message> messagesBetween(UUID conversationId, long fromId, long toId) {
        return jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM messages WHERE conversation_id = ? AND user_id = ? AND id >= ? AND id <= ? ORDER BY id")
                .params(conversationId, USER, fromId, toId).query(Message.class).list();
    }

    public Optional<Message> last(UUID conversationId) {
        return jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM messages WHERE conversation_id = ? AND user_id = ? AND kind = 'say' ORDER BY id DESC LIMIT 1")
                .params(conversationId, USER).query(Message.class).optional();
    }

    public long lastId(UUID conversationId) {
        return jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM messages WHERE conversation_id = ? AND user_id = ?")
                .params(conversationId, USER).query(Long.class).single();
    }

    public int unread(UUID conversationId) {
        return jdbc.sql("SELECT count(*) FROM messages WHERE conversation_id = ? AND user_id = ? AND unread")
                .params(conversationId, USER).query(Integer.class).single();
    }

    public int unreadTotal() {
        return jdbc.sql("SELECT count(*) FROM messages WHERE user_id = ? AND unread").param(USER).query(Integer.class).single();
    }

    public long add(UUID conversationId, UUID speakerId, String kind, String text, boolean byUser, String opportunity,
                    boolean unread, UUID runId) {
        long id = jdbc.sql("""
                INSERT INTO messages (user_id, conversation_id, speaker_id, kind, text, by_user, opportunity, unread, run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(USER, conversationId, speakerId, kind, text, byUser, opportunity, unread, runId)
                .query(Long.class).single();
        jdbc.sql("UPDATE conversations SET last_at = now() WHERE id = ?").param(conversationId).update();
        return id;
    }

    public void markRead(UUID conversationId) {
        jdbc.sql("UPDATE messages SET unread = false WHERE conversation_id = ? AND user_id = ? AND unread")
                .params(conversationId, USER).update();
    }

    public void setPlayed(UUID conversationId, UUID playedId) {
        jdbc.sql("UPDATE conversations SET played_id = ? WHERE id = ? AND user_id = ?").params(playedId, conversationId, USER).update();
    }

    public void setPending(UUID conversationId, List<String> pending, String failure) {
        jdbc.sql("UPDATE conversations SET pending = ?::text[], failure = ? WHERE id = ? AND user_id = ?")
                .params(Scenes.textArray(pending), failure, conversationId, USER).update();
    }

    private static Conversation conversation(ResultSet rs, int row) throws SQLException {
        Array pending = rs.getArray("pending");
        return new Conversation(rs.getObject("id", UUID.class), rs.getObject("a_id", UUID.class),
                rs.getObject("b_id", UUID.class), rs.getObject("played_id", UUID.class),
                pending == null ? List.of() : Arrays.asList((String[]) pending.getArray()), rs.getString("failure"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_at").toInstant());
    }
}
