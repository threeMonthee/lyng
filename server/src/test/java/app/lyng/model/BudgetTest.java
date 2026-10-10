package app.lyng.model;

import static org.assertj.core.api.Assertions.assertThat;

import app.lyng.store.Store.Character;
import app.lyng.store.Store.Scene;
import app.lyng.store.Store.World;
import app.lyng.story.SceneRunner;
import app.lyng.support.IntegrationTest;
import app.lyng.support.Recorder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 费用硬上限：已花费用达到上限就不再调用，作为技术失败呈现。 */
@TestPropertySource(properties = "lyng.model.budget-usd=0.00015")
class BudgetTest extends IntegrationTest {

    @Autowired
    SceneRunner runner;

    @Test
    void stopsCallingOnceTheBudgetIsSpent() {
        World world = store.insertWorld("w", "公开", "秘密");
        Character a = store.insertCharacter(world.id(), "甲", "【小传】甲。");
        Character b = store.insertCharacter(world.id(), "乙", "【小传】乙。");
        Scene scene = runner.create(world.id(), List.of(a.id(), b.id()), a.id(), null, "日常相处", "开场。");
        runner.resume(scene.id(), new Recorder()); // 第 1 次调用：0.0001
        Recorder r = new Recorder();
        runner.act(scene.id(), "说", "你好。", r); // 第 2 次：0.0002；第 3 次被挡住
        assertThat(r.events).last().asString().contains("已达费用上限");
        assertThat(model.requests()).hasSize(2);
    }
}
