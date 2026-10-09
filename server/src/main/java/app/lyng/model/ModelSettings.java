package app.lyng.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 每个角色用哪个模型：用户在设置里写入的覆盖项优先，其次是配置里的默认。随时可换，不用重启。 */
@Service
public class ModelSettings {

    private static final String PREFIX = "model.";

    private final JdbcClient jdbc;
    private final ModelProperties props;

    public ModelSettings(JdbcClient jdbc, ModelProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public ModelChoice choice(UUID userId, ModelRole role) {
        return jdbc.sql("SELECT value FROM settings WHERE user_id = ? AND key = ?")
                .params(userId, PREFIX + role.key())
                .query(String.class).optional()
                .map(ModelChoice::parse)
                .orElseGet(() -> defaultFor(role));
    }

    private ModelChoice defaultFor(ModelRole role) {
        ModelChoice base = props.defaultChoice();
        return role.defaultReasoning() == null ? base : new ModelChoice(base.model(), role.defaultReasoning());
    }

    public Map<ModelRole, ModelChoice> all(UUID userId) {
        Map<ModelRole, ModelChoice> result = new EnumMap<>(ModelRole.class);
        for (ModelRole role : ModelRole.values()) {
            result.put(role, choice(userId, role));
        }
        return result;
    }

    /** 空白的值表示恢复默认。写法错误抛 IllegalArgumentException。 */
    public void save(UUID userId, ModelRole role, String spec) {
        if (spec == null || spec.isBlank()) {
            jdbc.sql("DELETE FROM settings WHERE user_id = ? AND key = ?").params(userId, PREFIX + role.key()).update();
            return;
        }
        ModelChoice choice = ModelChoice.parse(spec);
        jdbc.sql("""
                INSERT INTO settings (user_id, key, value) VALUES (?, ?, ?)
                ON CONFLICT (user_id, key) DO UPDATE SET value = EXCLUDED.value""")
                .params(userId, PREFIX + role.key(), choice.spec()).update();
    }
}
