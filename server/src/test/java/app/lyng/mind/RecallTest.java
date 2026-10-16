package app.lyng.mind;

import static org.assertj.core.api.Assertions.assertThat;

import app.lyng.mind.Prompts.Recalled;
import app.lyng.store.Inner;
import app.lyng.store.Traces;
import app.lyng.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RecallTest extends IntegrationTest {

    @Autowired
    Recall recall;
    @Autowired
    Inner inner;
    @Autowired
    Traces traces;

    private final UUID earlier = UUID.randomUUID();
    private final UUID now = UUID.randomUUID();

    private void memories() {
        inner.addMemory(lu, "他把骨铃放在石桌上，说不是他。", "我错怪了他。", List.of(xf), "scene", earlier, "第1场", null);
        for (int i = 0; i < 4; i++) {
            inner.addMemory(lu, "第" + i + "次下山采药，山路很滑。", "没什么特别。", List.of(), "scene", earlier, "第1场", null);
        }
    }

    @Test
    void aSimilarMemoryComesBackEvenWhenItIsNotRecent() {
        memories();
        List<Inner.Memory> recent = recall.recentMemories(lu);
        assertThat(recent).hasSize(3).noneMatch(m -> m.event().contains("骨铃"));
        List<Recalled> found = recall.around(lu, "张小凡（行）：把骨铃放在石桌上。", recent.stream().map(Inner.Memory::id).toList(),
                now, 0);
        assertThat(found.getFirst().source()).isEqualTo("第1场");
        assertThat(found.getFirst().text()).isEqualTo("他把骨铃放在石桌上，说不是他。——我错怪了他。");
        assertThat(found).noneMatch(r -> recent.stream().anyMatch(m -> r.text().startsWith(m.event())));
    }

    @Test
    void anOldLineComesBackWithWhatWasSaidAroundIt() {
        String[] lines = {"叙事：渡口，傍晚。", "张小凡（说）：我来送你。", "陆雪琪（说）：不必。", "陆雪琪（心声）：他又瘦了。",
            "张小凡（说）：那骨铃我收着了。", "叙事：船家解开了缆绳。", "陆雪琪（说）：保重。"};
        for (int i = 0; i < lines.length; i++) {
            traces.add(lu, "scene", earlier, "第1场", i + 1, lines[i]);
        }
        traces.add(lu, "scene", now, "第2场", 100, "张小凡（说）：那骨铃我收着了。");
        List<Recalled> found = recall.around(lu, "张小凡（说）：骨铃还在我这里。", List.of(), now, 0);
        assertThat(found).singleElement().satisfies(r -> {
            assertThat(r.source()).isEqualTo("第1场");
            assertThat(r.text()).contains("陆雪琪（说）：不必。", "张小凡（说）：那骨铃我收着了。", "陆雪琪（说）：保重。");
        });
    }

    @Test
    void whatIsAlreadyInFrontOfThemIsNotRecalledAgain() {
        traces.add(lu, "chat", now, "和张小凡的私聊", 1, "张小凡（说）：骨铃还在我这里。");
        traces.add(lu, "chat", now, "和张小凡的私聊", 2, "陆雪琪（说）：嗯。");
        assertThat(recall.around(lu, "骨铃", List.of(), now, 1)).isEmpty();
        List<Recalled> older = recall.around(lu, "骨铃", List.of(), now, 2);
        assertThat(older).singleElement().satisfies(r -> assertThat(r.source()).startsWith("和张小凡的私聊（").endsWith("日）"));
    }

    @Test
    void theRecallToolSaysHonestlyWhenThereIsNothing() {
        assertThat(recall.search(lu, "骨铃", now, 0)).isEqualTo("没有翻到相关的经历。");
        memories();
        assertThat(recall.search(lu, "骨铃", now, 0)).startsWith("· 第1场：他把骨铃放在石桌上");
        assertThat(recall.search(lu, " ", now, 0)).isEqualTo("要写清想找什么。");
    }
}
