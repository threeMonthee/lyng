package app.lyng.support;

import static org.awaitility.Awaitility.await;

import app.lyng.chat.ChatService;
import app.lyng.create.CreationService;
import app.lyng.mind.Consolidator;
import app.lyng.mind.Markup;
import app.lyng.mind.Events;
import app.lyng.model.ChatModel;
import app.lyng.store.Worlds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** 连本机 PostgreSQL 的 lyng_test 库；每个测试前清空数据，测试后等后台的整理与主动开口结束。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTest.Models.class)
public abstract class IntegrationTest {

    @TestConfiguration
    static class Models {
        @Bean
        @Primary
        ScriptedModel scriptedModel() {
            return new ScriptedModel();
        }
    }

    @Autowired
    protected ScriptedModel model;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected Worlds worlds;
    @Autowired
    protected CreationService creation;
    @Autowired
    protected Consolidator consolidator;
    @Autowired
    protected ChatService chatService;

    /** 诛仙世界里的三个人物。 */
    protected UUID world;
    protected UUID lu;
    protected UUID xf;
    protected UUID by;

    @BeforeEach
    void cleanAndSeed() {
        settle();
        jdbc.sql("""
                TRUNCATE settings, worlds, characters, selves, views, concerns, memories, scenes, scene_cast, lines,
                    conversations, messages, traces, developments, consolidations, runs, model_calls, drafts CASCADE""").update();
        model.reset();
        var born = creation.bear(null, Markup.parseDraft(Fixtures.draft(), true, List.of()));
        world = born.worldId();
        lu = born.characterIds().get(0);
        xf = born.characterIds().get(1);
        by = born.characterIds().get(2);
    }

    @AfterEach
    void settleAfter() {
        settle();
    }

    /** 等后台的整理与主动开口都结束。 */
    protected void settle() {
        await().atMost(Duration.ofSeconds(20)).until(() -> consolidator.idle() && chatService.idle());
    }

    protected String name(UUID id) {
        return worlds.requireCharacter(id).name();
    }

    /** 收集流里的事件。 */
    public static final class Recorder implements Events {
        public final List<String> names = new ArrayList<>();
        public final List<Map<String, Object>> data = new ArrayList<>();

        @Override
        public synchronized void emit(String event, Map<String, Object> payload) {
            names.add(event);
            data.add(payload);
        }

        public Map<String, Object> first(String event) {
            int i = names.indexOf(event);
            return i < 0 ? null : data.get(i);
        }
    }
}
