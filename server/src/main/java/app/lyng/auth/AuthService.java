package app.lyng.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录与会话。会话令牌是随机串，库里只存它的 SHA-256；cookie 里是令牌本身。
 * 第一版只有一个本地账号；表结构与查询已经按多用户写。
 */
@Service
public class AuthService implements ApplicationRunner {

    public static final Duration SESSION_TTL = Duration.ofDays(30);

    private final JdbcClient jdbc;
    private final AuthProperties props;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcClient jdbc, AuthProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** 启动时把环境里的账号口令同步进库；环境是口令的唯一来源。 */
    @Override
    public void run(ApplicationArguments args) {
        if (props.password() == null || props.password().isBlank()) {
            throw new IllegalStateException("环境变量 LYNG_PASSWORD 未设置：在仓库根 .env 里加一行 LYNG_PASSWORD=你的口令");
        }
        String hash = encoder.encode(props.password());
        jdbc.sql("""
                INSERT INTO users (id, name, password_hash) VALUES (?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET password_hash = EXCLUDED.password_hash""")
                .params(UUID.randomUUID(), props.user(), hash).update();
    }

    /** 口令正确就开一个会话，返回令牌；否则返回 empty。 */
    public Optional<String> login(String name, String password) {
        if (name == null || password == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT id, password_hash FROM users WHERE name = ?").param(name)
                .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("password_hash")})
                .optional()
                .filter(row -> encoder.matches(password, (String) row[1]))
                .map(row -> openSession((UUID) row[0]));
    }

    private String openSession(UUID userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, now() + ?::interval)")
                .params(hash(token), userId, SESSION_TTL.toSeconds() + " seconds").update();
        return token;
    }

    public Optional<UUID> userOf(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT user_id FROM sessions WHERE token_hash = ? AND expires_at > now()").param(hash(token))
                .query(UUID.class).optional();
    }

    public String nameOf(UUID userId) {
        return jdbc.sql("SELECT name FROM users WHERE id = ?").param(userId).query(String.class).single();
    }

    public void logout(String token) {
        if (token != null) {
            jdbc.sql("DELETE FROM sessions WHERE token_hash = ?").param(hash(token)).update();
        }
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
