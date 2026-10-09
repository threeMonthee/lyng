package app.lyng.prompt;

import static app.lyng.support.Fixtures.BI_YAO;
import static app.lyng.support.Fixtures.LU_XUEQI;
import static app.lyng.support.Fixtures.OPENING_A1;
import static app.lyng.support.Fixtures.OPENING_A2;
import static app.lyng.support.Fixtures.WORLD;
import static app.lyng.support.Fixtures.WORLD_NARRATOR;
import static app.lyng.support.Fixtures.ZHANG_XIAOFAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.event.SceneEvent;
import app.lyng.markup.Beat;
import app.lyng.model.ChatMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Prompt 快照测试。改了 Prompt 之后用 UPDATE_SNAPSHOTS=1 重新生成并检查差异。
 * 移植自验证原型的叙事者 Prompt 另有“平行测试”：与原型 Python 版的快照逐字节一致（resources/snapshots/parity，不随脚本更新）。
 */
class PromptSnapshotTest {

    private static final Path SNAPSHOTS = Path.of("src/test/resources/snapshots");
    private static final UUID XIAOFAN = UUID.randomUUID();
    private static final UUID LU = UUID.randomUUID();
    private static final UUID BIYAO = UUID.randomUUID();

    private static final List<SceneEvent> A2_EVENTS = List.of(
            SceneEvent.narration(0, "长街上人来人往。"),
            SceneEvent.user(1, XIAOFAN, "张小凡", "静候", ""),
            SceneEvent.character(1, BIYAO, "碧瑶", List.of(new Beat("行", "碧瑶挽住了张小凡的胳膊。"), new Beat("说", "哟，这不是陆师姐么？")),
                    "碧瑶的秘密心声", false),
            SceneEvent.character(1, LU, "陆雪琪", List.of(new Beat("行", "陆雪琪停住了脚步。")), "陆雪琪的秘密心声", false),
            SceneEvent.user(2, XIAOFAN, "张小凡", "说", "雪琪。"),
            SceneEvent.character(2, LU, "陆雪琪", List.of(), "陆雪琪第二句心声", true),
            SceneEvent.failure(2, "character", BIYAO, "碧瑶", "技术失败：空响应（已重试一次）"));

    private static String dump(List<ChatMessage> messages) {
        StringBuilder out = new StringBuilder();
        messages.forEach(m -> out.append("=== ").append(m.role()).append(" ===\n").append(m.content()).append('\n'));
        return out.toString();
    }

    private static void check(String name, List<ChatMessage> messages) throws IOException {
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

    private static void parity(String name, List<ChatMessage> messages) throws IOException {
        String python = Files.readString(SNAPSHOTS.resolve("parity/" + name + ".txt"), StandardCharsets.UTF_8);
        assertThat(dump(messages)).as("与原型快照 " + name + " 一致").isEqualTo(python);
    }

    private static NarratorContext a1() {
        return new NarratorContext(WORLD, WORLD_NARRATOR, List.of(ZHANG_XIAOFAN, LU_XUEQI), "张小凡", Prompts.TONE_DAILY,
                Prompts.SCALE_MILD, OPENING_A1, null);
    }

    private static NarratorContext a2() {
        return new NarratorContext(WORLD, WORLD_NARRATOR, List.of(ZHANG_XIAOFAN, BI_YAO, LU_XUEQI), "张小凡",
                Prompts.TONE_DAILY, Prompts.SCALE_MILD, OPENING_A2, null);
    }

    private static CharacterContext luWithoutPast() {
        return new CharacterContext("陆雪琪", WORLD, LU_XUEQI, Prompts.SCALE_MILD, List.of(), List.of(), List.of());
    }

    // ---- 与原型逐字节一致 ----

    @Test
    void narratorOpeningMatchesThePrototype() throws IOException {
        parity("narrator_opening_A1", Prompts.narratorOpening(a1()));
    }

    @Test
    void narratorPairRoundMatchesThePrototype() throws IOException {
        parity("narrator_round_A1_pair",
                Prompts.narratorRound(a1(), List.of(), SceneEvent.user(2, XIAOFAN, "张小凡", "说", "那天夜里伤你师妹的，是这个。")));
    }

    @Test
    void narratorClosingMatchesThePrototype() throws IOException {
        parity("narrator_closing_A2", Prompts.narratorClosing(a2(), A2_EVENTS));
    }

    // ---- 本项目的快照 ----

    @Test
    void characterWithoutPast() throws IOException {
        check("character_first_scene", Prompts.character(luWithoutPast(), A2_EVENTS));
    }

    @Test
    void characterWithRelationsMemoriesAndRecentScenes() throws IOException {
        CharacterContext ctx = new CharacterContext("陆雪琪", WORLD, LU_XUEQI, Prompts.SCALE_MILD,
                List.of(new Relation("张小凡", "他还是那个傻子，可我不再想拔剑了。")),
                List.of(new Memory(1, "那夜他把骨铃放在石桌上，我没有躲开他的眼睛。")),
                List.of(new PastScene(2, "叙事：渡口，傍晚。\n张小凡（说）：我来送你。\n陆雪琪（说）：不必。\n陆雪琪（心声）：他又瘦了。")));
        check("character_with_past", Prompts.character(ctx, A2_EVENTS));
    }

    @Test
    void characterAdultScale() throws IOException {
        CharacterContext ctx = new CharacterContext("陆雪琪", WORLD, LU_XUEQI, Prompts.SCALE_ADULT, List.of(), List.of(), List.of());
        check("character_adult_scale", Prompts.character(ctx, List.of()));
    }

    @Test
    void narratorContinuesFromThePreviousScene() throws IOException {
        NarratorContext ctx = new NarratorContext(WORLD, WORLD_NARRATOR, List.of(ZHANG_XIAOFAN, LU_XUEQI), "张小凡",
                Prompts.TONE_ADVENTURE, Prompts.SCALE_MILD, "渡口，傍晚，张小凡来送行。",
                new PastScene(1, "叙事：山海苑后院，入夜。\n张小凡（说）：不是我。\n陆雪琪（说）：我信你。"));
        check("narrator_with_previous_scene", Prompts.narratorOpening(ctx));
    }

    @Test
    void narratorRoundAfterADoAction() throws IOException {
        check("narrator_round_do", Prompts.narratorRound(a1(), A2_EVENTS.subList(0, 1),
                SceneEvent.user(1, XIAOFAN, "张小凡", "做", "翻过院墙去追那个人影。")));
    }

    @Test
    void narratorRoundAfterWaiting() throws IOException {
        check("narrator_round_wait", Prompts.narratorRound(a1(), A2_EVENTS.subList(0, 1),
                SceneEvent.user(1, XIAOFAN, "张小凡", "静候", "")));
    }

    @Test
    void recallForTheOtherCharacter() throws IOException {
        check("recall", Prompts.recall("陆雪琪", WORLD, LU_XUEQI, List.of(new Relation("张小凡", "他变了很多。")),
                List.of("张小凡"), A2_EVENTS));
    }

    @Test
    void draftForANewWorld() throws IOException {
        check("draft_new_world", Prompts.draft("诛仙，青云大试后，陆雪琪", null, List.of()));
    }

    @Test
    void draftInAnExistingWorld() throws IOException {
        check("draft_existing_world", Prompts.draft("再添一位：碧瑶", WORLD, List.of("陆雪琪", "张小凡")));
    }

    // ---- 群戏调度（这一刀不用，随原型移植）----

    @Test
    void groupRoundInstructionsExcludeThePossessedCharacter() throws IOException {
        String text = Prompts.groupRoundInstructions(List.of("碧瑶", "陆雪琪"), "张小凡", false);
        assertThat(text).contains("张小凡是被附身的人物，不在可选之列，不要写进“轮到：”；他什么时候开口，由他自己决定。");
        assertThat(Prompts.groupRoundInstructions(List.of("碧瑶", "陆雪琪"), null, true)).doesNotContain("被附身的人物");
        check("group_round_instructions", List.of(ChatMessage.user(text)));
    }

    // ---- 规则 ----

    @Test
    void lengthRuleIsInEveryPrompt() {
        assertThat(dump(Prompts.character(luWithoutPast(), List.of())))
                .contains("以说为主，动作一两笔带过").contains("亲密或打斗的时候，话少，动作与细节写足");
        assertThat(dump(Prompts.narratorOpening(a1()))).contains("每次出声只写一两句，点到为止");
    }

    @Test
    void theCharacterNeverSeesNarratorOnlySettingsOrOthersInnerVoice() {
        String text = dump(Prompts.character(luWithoutPast(), A2_EVENTS));
        for (String secret : WORLD_NARRATOR.lines().filter(l -> !l.isBlank() && !l.startsWith("【")).skip(1).toList()) {
            assertThat(text).doesNotContain(secret);
        }
        assertThat(text).doesNotContain("碧瑶的秘密心声").contains("陆雪琪的秘密心声");
        assertThat(text).doesNotContain(OPENING_A1);
    }

    @Test
    void theNarratorSeesNarratorOnlySettingsButNoInnerVoice() {
        String text = dump(Prompts.narratorClosing(a2(), A2_EVENTS));
        assertThat(text).contains("一个月前，碧瑶醒了").doesNotContain("心声");
    }

    @Test
    void observingIsNotImplementedYet() {
        NarratorContext observing = new NarratorContext(WORLD, WORLD_NARRATOR, List.of(LU_XUEQI), null,
                Prompts.TONE_DAILY, Prompts.SCALE_MILD, "x", null);
        assertThatThrownBy(() -> Prompts.narratorOpening(observing)).isInstanceOf(IllegalArgumentException.class);
    }
}
