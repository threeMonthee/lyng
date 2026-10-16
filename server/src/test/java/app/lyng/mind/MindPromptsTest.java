package app.lyng.mind;

import static app.lyng.support.Fixtures.LU_XUEQI;
import static app.lyng.support.Fixtures.WORLD;
import static org.assertj.core.api.Assertions.assertThat;

import app.lyng.mind.MindPrompts.Concern;
import app.lyng.mind.MindPrompts.Context;
import app.lyng.mind.MindPrompts.LifeCue;
import app.lyng.mind.MindPrompts.Line;
import app.lyng.mind.MindPrompts.Person;
import app.lyng.mind.Recollection.Bundle;
import app.lyng.mind.Recollection.Excerpt;
import app.lyng.model.Msg;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 私聊里人物开口的两步：心里过一遍（照搬 x-chat-ai 的人物心智）与说出口。用快照，改了以后用 UPDATE_SNAPSHOTS=1
 * 重新生成并检查差异。
 */
class MindPromptsTest {

    private static final Path SNAPSHOTS = Path.of("src/test/resources/snapshots");
    private static final UUID XF = UUID.fromString("00000000-0000-0000-0000-00000000000f");
    private static final UUID BY = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID CARE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID MEMORY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant T = Instant.parse("2026-10-15T13:05:00Z");
    private static final String PROFILE = LU_XUEQI + "\n\n【说话的样子】\n\n话很少，问一句答一句；真在意的事反而会多问一句。";

    private static String dump(List<Msg> messages) {
        StringBuilder out = new StringBuilder();
        messages.forEach(m -> out.append("=== ").append(m.role()).append(" ===\n").append(m.content()).append("\n"));
        return out.toString();
    }

    private static void snapshot(String name, List<Msg> messages) throws IOException {
        Path path = SNAPSHOTS.resolve(name + ".txt");
        String actual = dump(messages);
        if (System.getenv("UPDATE_SNAPSHOTS") != null || !Files.exists(path)) {
            Files.writeString(path, actual, StandardCharsets.UTF_8);
            if (System.getenv("UPDATE_SNAPSHOTS") == null) {
                throw new AssertionError("新建了快照 " + name + "，请检查后重跑");
            }
        }
        assertThat(actual).as("快照 " + name).isEqualTo(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static Context context(String opportunity, List<Line> exchange, List<Line> current) {
        Person xf = new Person(XF, "张小凡", "十年前那个一次次站起来的少年。如今是鬼王宗的人，可我不信他变了。");
        List<Bundle> material = List.of(
                new Bundle(new Excerpt(7, "第1场", T.minusSeconds(86400 * 3), "张小凡（说）：我会回来的。", false),
                        List.of(new Excerpt(12, "你形成的记忆", T.minusSeconds(86400),
                                "渡口的约\n他说会回来。我等着。", false)),
                        false, null,
                        List.of(new Excerpt(15, "和碧瑶的私聊（10月14日）", T.minusSeconds(80000), "碧瑶（说）：他不会回来了。",
                                false))),
                new Bundle(new Excerpt(3, "和张小凡的私聊（10月15日）", T, "", true), List.of(), false, null,
                        List.of(new Excerpt(12, "你形成的记忆", T.minusSeconds(86400), "", false))));
        return new Context("陆雪琪", WORLD, PROFILE, Prompts.MILD, null, opportunity, T.plusSeconds(3 * 3600 + 60), xf,
                List.of(new Concern(CARE, XF, "渡口的约", "他说会回来。我想知道他还记不记得。", "active")),
                List.of(new Person(BY, "碧瑶", null)), 2,
                List.of(new LifeCue("concern", CARE, XF, "渡口的约"), new LifeCue("memory", MEMORY, null, "渡口的约")),
                material, exchange, current);
    }

    private static final List<Line> EXCHANGE = List.of(
            new Line(1, false, "say", "睡了吗？", T),
            new Line(2, true, "say", "没有。", T.plusSeconds(60)),
            new Line(3, true, "inner", "这么晚了。", T.plusSeconds(60)));

    @Test
    void thinkingItOverWhenSomethingNewArrives() throws IOException {
        List<Msg> messages = MindPrompts.mind(context("MESSAGE", EXCHANGE,
                List.of(new Line(4, false, "say", "明天我去渡口。", T.plusSeconds(3 * 3600)),
                        new Line(5, false, "say", "你来吗？", T.plusSeconds(3 * 3600)))));
        snapshot("mind_message", messages);
        assertThat(messages.getLast().content()).isEqualTo("明天我去渡口。\n你来吗？");
        String system = messages.getFirst().content();
        assertThat(system).contains("经历id=4、5。", "自然表达倾向：话很少，问一句答一句", "\"kind\":\"心声\",\"content\":\"这么晚了。\"")
                .doesNotContain("明天我去渡口");
        assertThat(system.substring(system.indexOf("人生起点"), system.indexOf("自然表达倾向"))).doesNotContain("说话的样子");
    }

    @Test
    void thinkingItOverWithoutAnythingNew() throws IOException {
        snapshot("mind_continue", MindPrompts.mind(context("CONTINUE", EXCHANGE, List.of())));
        List<Msg> online = MindPrompts.mind(context("ONLINE", EXCHANGE, List.of()));
        assertThat(online.getLast().content()).isEqualTo("（你们有一阵没说话了。）");
        assertThat(online.getFirst().content()).contains("这次没有新话语", "\"机会\":\"ONLINE\"");
    }

    @Test
    void sayingItSeesTheRelationshipTheMoodAndTheRecentConversation() throws IOException {
        List<Msg> messages = MindPrompts.express("陆雪琪", PROFILE, "张小凡", "十年前那个一次次站起来的少年。", "他还记得。",
                List.of("张小凡：明天我去渡口。", "张小凡：你来吗？"), "答应他，明天去。");
        snapshot("express", messages);
        assertThat(messages.getLast().content()).contains("\"此刻心里的念头（只用来拿捏语气，不要说出来）\":\"他还记得。\"",
                "\"你们最近的对话\":[\"张小凡：明天我去渡口。\",\"张小凡：你来吗？\"]");
    }

    @Test
    void aProfileWithoutASpeakingStyleSaysSo() {
        String[] split = MindPrompts.originAndStyle(LU_XUEQI);
        assertThat(split[0]).isEqualTo(LU_XUEQI.strip());
        assertThat(split[1]).contains("没有单独写");
    }
}
