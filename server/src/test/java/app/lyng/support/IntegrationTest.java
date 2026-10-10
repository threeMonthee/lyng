package app.lyng.support;

import app.lyng.store.Store;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 集成测试基类：完整的 Spring 应用 + 临时 SQLite 文件 + 脚本化的模型替身。
 * 所有集成测试共用一个应用上下文和一个数据库；每个测试前清空数据。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=optional:file:./does-not-exist",
            "lyng.model.backend=fake",
            "lyng.model.backoff-ms=0",
            "lyng.model.budget-usd="
        })
@Import(IntegrationTest.Models.class)
public abstract class IntegrationTest {

    private static final Path DB = tempDb();

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected ScriptedModel model;

    @Autowired
    protected Store store;

    @TestConfiguration
    static class Models {
        @Bean
        @Primary
        ScriptedModel scriptedModel() {
            return new ScriptedModel();
        }
    }

    private static Path tempDb() {
        try {
            Path dir = Files.createTempDirectory("lyng-test");
            return dir.resolve("lyng.db");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB + "?journal_mode=WAL&foreign_keys=on&busy_timeout=5000");
    }

    @BeforeEach
    void clean() {
        model.reset();
        for (String table : new String[] {"model_calls", "memories", "lines", "scenes", "characters", "worlds"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    /** 等异步的回想写完。 */
    protected void awaitMemories(int count) {
        long deadline = System.currentTimeMillis() + 5000;
        while (jdbc.sql("SELECT COUNT(*) FROM memories").query(Integer.class).single() < count) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("回想没有在 5 秒内写完");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
