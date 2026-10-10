package app.lyng.story;

import static app.lyng.support.Fixtures.BI_YAO;
import static app.lyng.support.Fixtures.LU_XUEQI;
import static app.lyng.support.Fixtures.OPENING_A1;
import static app.lyng.support.Fixtures.OPENING_A2;
import static app.lyng.support.Fixtures.OPENING_B3;
import static app.lyng.support.Fixtures.WORLD;
import static app.lyng.support.Fixtures.WORLD_NARRATOR;
import static app.lyng.support.Fixtures.ZHANG_XIAOFAN;
import static org.assertj.core.api.Assertions.assertThat;

import app.lyng.model.ChatModel.Message;
import app.lyng.store.Store.Line;
import app.lyng.story.Prompts.Past;
import app.lyng.story.Prompts.Persona;
import app.lyng.story.Prompts.Stage;
import app.lyng.story.Prompts.View;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Prompt 快照测试。改了 Prompt 之后用 UPDATE_SNAPSHOTS=1 重新生成并检查差异。
 * 叙事者的 Prompt 另有“平行测试”：与验证原型 Python 版的快照逐字节一致（resources/snapshots/parity，不随脚本更新）。
 */
class PromptSnapshotTest {

    private static final Path SNAPSHOTS = Path.of("src/test/resources/snapshots");
    private static final String XF = "xf";
    private static final String LU = "lu";
    private static final String BY = "by";
    private static final Map<String, String> NAMES = Map.of(XF, "张小凡", LU, "陆雪琪", BY, "碧瑶");

    /** 原型 A2 的一段经过（原型里还有一次技术失败；失败不进 lines，本来就不会出现在任何人的感知里）。 */
    private static final List<Line> A2_LINES = List.of(
            line(0, "narration", null, "长街上人来人往。"),
            line(1, "wait", XF, ""),
            line(1, "do", BY, "碧瑶挽住了张小凡的胳膊。"),
            line(1, "say", BY, "哟，这不是陆师姐么？"),
            line(1, "inner", BY, "碧瑶的秘密心声"),
            line(1, "do", LU, "陆雪琪停住了脚步。"),
            line(1, "inner", LU, "陆雪琪的秘密心声"),
            line(2, "say", XF, "雪琪。"),
            line(2, "wait", LU, ""),
            line(2, "inner", LU, "陆雪琪第二句心声"));

    private static Line line(int round, String kind, String speaker, String text) {
        return Line.of(round, kind, speaker, speaker != null && speaker.equals(XF), text);
    }

    private static String dump(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        messages.forEach(m -> out.append("=== ").append(m.role()).append(" ===\n").append(m.content()).append('\n'));
        return out.toString();
    }

    private static void check(String name, List<Message> messages) throws IOException {
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

    private static void parity(String name, List<Message> messages) throws IOException {
        String python = Files.readString(SNAPSHOTS.resolve("parity/" + name + ".txt"), StandardCharsets.UTF_8);
        assertThat(dump(messages)).as("与原型快照 " + name + " 一致").isEqualTo(python);
    }

    private static Stage a1() {
        return new Stage(WORLD, WORLD_NARRATOR, List.of(ZHANG_XIAOFAN, LU_XUEQI), "张小凡", null, Prompts.TONE_DAILY,
                Prompts.SCALE_MILD, OPENING_A1, null);
    }

    private static Stage a2() {
        return new Stage(WORLD, WORLD_NARRATOR, List.of(ZHANG_XIAOFAN, BI_YAO, LU_XUEQI), "张小凡", null,
                Prompts.TONE_DAILY, Prompts.SCALE_MILD, OPENING_A2, null);
    }

    private static Persona luWithoutPast() {
        return new Persona("陆雪琪", WORLD, LU_XUEQI, Prompts.SCALE_MILD, List.of(), List.of(), List.of());
    }

    private static String perceive(List<Line> lines, String viewer) {
        return Prompts.perceive(lines, NAMES, viewer);
    }

    // ---- 与原型逐字节一致 ----

    @Test
    void narratorOpeningMatchesThePrototype() throws IOException {
        parity("narrator_opening_A1", Prompts.narratorOpening(a1()));
    }

    @Test
    void pairRoundMatchesThePrototype() throws IOException {
        parity("narrator_round_A1_pair", Prompts.narratorRound(a1(), perceive(List.of(), null),
                Prompts.latest("张小凡", "说", "那天夜里伤你师妹的，是这个。"), List.of("陆雪琪"), false));
    }

    @Test
    void groupRoundWhilePossessingMatchesThePrototype() throws IOException {
        parity("narrator_round_A2", Prompts.narratorRound(a2(), perceive(A2_LINES, null),
                Prompts.latest("张小凡", "说", "碧瑶，你先回山海苑，我随后就到。"), List.of("碧瑶", "陆雪琪"), false));
    }

    @Test
    void observingRoundMatchesThePrototype() throws IOException {
        Stage b3 = new Stage(WORLD, WORLD_NARRATOR, List.of(LU_XUEQI, BI_YAO), null, "陆雪琪", Prompts.TONE_DAILY,
                Prompts.SCALE_MILD, OPENING_B3, null);
        parity("narrator_round_B3", Prompts.narratorRound(b3, perceive(List.of(), null),
                Prompts.latest(null, "继续", ""), List.of("陆雪琪", "碧瑶"), true));
    }

    @Test
    void narratorClosingMatchesThePrototype() throws IOException {
        parity("narrator_closing_A2", Prompts.narratorClosing(a2(), perceive(A2_LINES, null)));
    }

    // ---- 本项目的快照 ----

    @Test
    void characterWithoutPast() throws IOException {
        check("character_first_scene", Prompts.character(luWithoutPast(), perceive(A2_LINES, LU)));
    }

    @Test
    void characterWithViewsMemoriesAndRecentScenes() throws IOException {
        Persona p = new Persona("陆雪琪", WORLD, LU_XUEQI, Prompts.SCALE_MILD,
                List.of(new View("张小凡", "他还是那个傻子，可我不再想拔剑了。")),
                List.of(new Past(1, "那夜他把骨铃放在石桌上，我没有躲开他的眼睛。")),
                List.of(new Past(2, "叙事：渡口，傍晚。\n张小凡（说）：我来送你。\n陆雪琪（说）：不必。\n陆雪琪（心声）：他又瘦了。")));
        check("character_with_past", Prompts.character(p, perceive(A2_LINES, LU)));
    }

    @Test
    void characterAdultScale() throws IOException {
        Persona p = new Persona("陆雪琪", WORLD, LU_XUEQI, Prompts.SCALE_ADULT, List.of(), List.of(), List.of());
        check("character_adult_scale", Prompts.character(p, perceive(List.of(), LU)));
    }

    @Test
    void narratorContinuesFromThePreviousScene() throws IOException {
        Stage s = new Stage(WORLD, WORLD_NARRATOR, List.of(ZHANG_XIAOFAN, LU_XUEQI), "张小凡", null,
                Prompts.TONE_ADVENTURE, Prompts.SCALE_MILD, "渡口，傍晚，张小凡来送行。",
                new Past(1, "叙事：山海苑后院，入夜。\n张小凡（说）：不是我。\n陆雪琪（说）：我信你。"));
        check("narrator_with_previous_scene", Prompts.narratorOpening(s));
    }

    @Test
    void narratorRoundAfterADoAction() throws IOException {
        check("narrator_round_do", Prompts.narratorRound(a1(), perceive(A2_LINES.subList(0, 1), null),
                Prompts.latest("张小凡", "做", "翻过院墙去追那个人影。"), List.of("陆雪琪"), false));
    }

    @Test
    void narratorRoundAfterWaiting() throws IOException {
        check("narrator_round_wait", Prompts.narratorRound(a1(), perceive(A2_LINES.subList(0, 1), null),
                Prompts.latest("张小凡", "静候", ""), List.of("陆雪琪"), false));
    }

    @Test
    void recallForTheOtherCharacter() throws IOException {
        check("recall", Prompts.recall("陆雪琪", WORLD, LU_XUEQI, List.of(new View("张小凡", "他变了很多。")),
                List.of("张小凡"), perceive(A2_LINES, LU)));
    }

    @Test
    void draftForANewWorld() throws IOException {
        check("draft_new_world", Prompts.draft("诛仙，青云大试后，陆雪琪", null, List.of()));
    }

    @Test
    void draftInAnExistingWorld() throws IOException {
        check("draft_existing_world", Prompts.draft("再添一位：碧瑶", WORLD, List.of("陆雪琪", "张小凡")));
    }

    // ---- 规则 ----

    @Test
    void lengthRuleIsInEveryPrompt() {
        assertThat(dump(Prompts.character(luWithoutPast(), perceive(List.of(), LU))))
                .contains("以说为主，动作一两笔带过").contains("亲密或打斗的时候，话少，动作与细节写足");
        assertThat(dump(Prompts.narratorOpening(a1()))).contains("每次出声只写一两句，点到为止");
    }

    @Test
    void theCharacterNeverSeesNarratorOnlySettingsOrOthersInnerVoice() {
        String text = dump(Prompts.character(luWithoutPast(), perceive(A2_LINES, LU)));
        for (String secret : WORLD_NARRATOR.lines().filter(l -> !l.isBlank() && !l.startsWith("【")).skip(1).toList()) {
            assertThat(text).doesNotContain(secret);
        }
        assertThat(text).doesNotContain("碧瑶的秘密心声").contains("陆雪琪的秘密心声");
        assertThat(text).doesNotContain(OPENING_A1);
    }

    @Test
    void theNarratorSeesNarratorOnlySettingsButNoInnerVoice() {
        String text = dump(Prompts.narratorClosing(a2(), perceive(A2_LINES, null)));
        assertThat(text).contains("一个月前，碧瑶醒了").doesNotContain("心声");
    }

    @Test
    void aCreatedProfileGetsItsNameForTheNarrator() {
        assertThat(Prompts.profileForNarrator("阿青", "【小传】\n客栈的伙计。")).isEqualTo("阿青\n\n【小传】\n客栈的伙计。");
        assertThat(Prompts.profileForNarrator("陆雪琪", LU_XUEQI)).isEqualTo(LU_XUEQI);
    }
}
