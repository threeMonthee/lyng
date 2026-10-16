package app.lyng.store;

import static app.lyng.LyngApplication.USER;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 全局设置：内容尺度与费用上限。 */
@Repository
public class Settings {

    public record Values(String scale, BigDecimal budgetUsd, Instant lastPresence) {}

    private final JdbcClient jdbc;

    public Settings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Values get() {
        jdbc.sql("INSERT INTO settings (user_id) VALUES (?) ON CONFLICT DO NOTHING").param(USER).update();
        return jdbc.sql("SELECT scale, budget_usd, last_presence FROM settings WHERE user_id = ?").param(USER)
                .query(Values.class).single();
    }

    public void update(String scale, BigDecimal budgetUsd) {
        get();
        jdbc.sql("UPDATE settings SET scale = ?, budget_usd = ? WHERE user_id = ?").params(scale, budgetUsd, USER).update();
    }

    public void touchPresence() {
        get();
        jdbc.sql("UPDATE settings SET last_presence = now() WHERE user_id = ?").param(USER).update();
    }
}
