package app.lyng.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 集成测试基类：嵌入式 PostgreSQL（不依赖 Docker）+ 完整的 Spring 应用 + 脚本化的模型替身。
 * 所有集成测试共用一个应用上下文和一个数据库；每个测试前清空业务数据（保留账号）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=optional:file:./does-not-exist",
            "lyng.model.backend=fake",
            "lyng.model.backoff-ms=0",
            "lyng.auth.user=me",
            "lyng.auth.password=test-password"
        })
@Import(TestModelConfig.class)
public abstract class IntegrationTest {

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected ScriptedChatModel model;

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动嵌入式 PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @BeforeEach
    void cleanDatabase() {
        model.reset();
        jdbc.sql("""
                TRUNCATE model_calls, recollections, scene_events, scene_characters, scenes, characters, worlds,
                         settings, sessions RESTART IDENTITY CASCADE""").update();
    }
}
