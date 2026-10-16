package app.lyng.mind;

import static app.lyng.support.Fixtures.BI_YAO;
import static app.lyng.support.Fixtures.LU_XUEQI;
import static app.lyng.support.Fixtures.OPENING_A1;
import static app.lyng.support.Fixtures.OPENING_A2;
import static app.lyng.support.Fixtures.OPENING_B3;
import static app.lyng.support.Fixtures.WORLD;
import static app.lyng.support.Fixtures.WORLD_NARRATOR;
import static app.lyng.support.Fixtures.ZHANG_XIAOFAN;
import static org.assertj.core.api.Assertions.assertThat;

import app.lyng.mind.Prompts.Care;
import app.lyng.mind.Prompts.Persona;
import app.lyng.mind.Prompts.Recalled;
import app.lyng.mind.Prompts.Stage;
import app.lyng.mind.Prompts.ViewOf;
import app.lyng.model.Msg;
import app.lyng.store.Chats.Message;
import app.lyng.store.Scenes.Line;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 发给模型的消息。叙事者与一场戏里人物的写法来自盲测验证（2026-10-09 Prompt v2），与当时的快照逐字节对照
 * （resources/snapshots/parity，不随脚本更新）；2.0 新写的部分用快照，改了 Prompt 后用 UPDATE_SNAPSHOTS=1 重新生成并检查差异。
 */
class PromptsTest {

    private static final Path SNAPSHOTS = Path.of("src/test/resources/snapshots");
    private static final UUID XF = UUID.randomUUID();
    private static final UUID LU = UUID.randomUUID();
    private static final UUID BY = UUID.randomUUID();
    private static final Map<UUID, String> NAMES = Map.of(XF, "张小凡", LU, "陆雪琪", BY, "碧瑶");

    /** 验证时 A2 的一段经过。 */
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

    private static long ids;

    private static Line line(int round, String kind, UUID speaker, String text) {
        return new Line(++ids, round, kind, speaker, text, XF.equals(speaker), null, Instant.EPOCH);
    }

    private static String dump(List<Msg> messages) {
        StringBuilder out = new StringBuilder();
        messages.forEach(m -> out.append("=== ").append(m.role()).append(" ===\n").append(m.content()).append('\n'));
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

    private static String validated(String name) throws IOException {
        return Files.readString(SNAPSHOTS.resolve("parity/" + name + ".txt"), StandardCharsets.UTF_8);
    }

    private static Stage a1() {
        return new Stage(WORLD, WORLD_NARRATOR, List.of(profile("张小凡", ZHANG_XIAOFAN), profile("陆雪琪", LU_XUEQI)),
                "张小凡", null, Prompts.DAILY, Prompts.MILD, OPENING_A1, null);
    }

    private static Stage a2() {
        return new Stage(WORLD, WORLD_NARRATOR, List.of(profile("张小凡", ZHANG_XIAOFAN), profile("碧瑶", BI_YAO),
                profile("陆雪琪", LU_XUEQI)), "张小凡", null, Prompts.DAILY, Prompts.MILD, OPENING_A2, null);
    }

    private static String profile(String name, String profile) {
        return Prompts.profileWithName(name, profile);
    }

    private static Persona lu(List<ViewOf> views, List<Care> cares, List<Recalled> recent, List<Recalled> recalled,
                              String self) {
        return new Persona("陆雪琪", WORLD, LU_XUEQI, self, views, cares, recent, recalled, Prompts.MILD);
    }

    private static Persona luAsBorn() {
        return lu(List.of(), List.of(), List.of(), List.of(), null);
    }

    // ---- 与验证时逐字节一致 ----

    @Test
    void narratorOpeningIsTheValidatedPrompt() throws IOException {
        assertThat(dump(Prompts.narratorOpening(a1()))).isEqualTo(validated("narrator_opening_A1"));
    }

    @Test
    void pairRoundIsTheValidatedPrompt() throws IOException {
        assertThat(dump(Prompts.narratorRound(a1(), Perceive.scene(List.of(), NAMES, null),
                Prompts.latest("张小凡", "say", "那天夜里伤你师妹的，是这个。"), List.of("陆雪琪"), false)))
                .isEqualTo(validated("narrator_round_A1_pair"));
    }

    @Test
    void groupRoundWhilePossessingIsTheValidatedPrompt() throws IOException {
        assertThat(dump(Prompts.narratorRound(a2(), Perceive.scene(A2_LINES, NAMES, null),
                Prompts.latest("张小凡", "say", "碧瑶，你先回山海苑，我随后就到。"), List.of("碧瑶", "陆雪琪"), false)))
                .isEqualTo(validated("narrator_round_A2"));
    }

    @Test
    void observingRoundIsTheValidatedPrompt() throws IOException {
        Stage b3 = new Stage(WORLD, WORLD_NARRATOR, List.of(profile("陆雪琪", LU_XUEQI), profile("碧瑶", BI_YAO)), null,
                "陆雪琪", Prompts.DAILY, Prompts.MILD, OPENING_B3, null);
        assertThat(dump(Prompts.narratorRound(b3, Perceive.scene(List.of(), NAMES, null),
                Prompts.latest(null, "continue", null), List.of("陆雪琪", "碧瑶"), true)))
                .isEqualTo(validated("narrator_round_B3"));
    }

    @Test
    void narratorClosingIsTheValidatedPrompt() throws IOException {
        assertThat(dump(Prompts.narratorClosing(a2(), Perceive.scene(A2_LINES, NAMES, null))))
                .isEqualTo(validated("narrator_closing_A2"));
    }

    /**
     * 一场戏里人物的 user（经过、轮到你、篇幅、写法）仍与验证时逐字一致。system 是 2.0 的：
     * 加了“可以去翻自己的经历”与“做一个真人”的几段，尺度去掉了“情感与张力写足”（2026-10-16，见 tech-design §4）。
     */
    @Test
    void aCharacterAsBornKeepsTheValidatedSceneFormat() throws IOException {
        List<Msg> messages = Prompts.characterInScene(luAsBorn(), Perceive.scene(A2_LINES, NAMES, LU));
        String validated = validated("character_in_scene_v2");
        assertThat("=== user ===\n" + messages.getLast().content() + "\n")
                .isEqualTo(validated.substring(validated.indexOf("=== user ===\n")));
        assertThat(messages.getFirst().content()).contains(LU_XUEQI.strip(), "【分寸】\n亲密与暴力都可以写，最露骨处留白。")
                .doesNotContain("情感与张力写足");
        snapshot("character_in_scene", messages);
    }

    // ---- 2.0 新写的部分 ----

    @Test
    void aCharacterWithAnInnerLife() throws IOException {
        Persona p = lu(List.of(new ViewOf("张小凡", "他还是那个傻子，可我不再想拔剑了。")),
                List.of(new Care("欠他一句对不起。", "张小凡"), new Care("回山后向师父禀明此事。", null)),
                List.of(new Recalled("第1场", "他把骨铃放在石桌上。——我错怪了他。")),
                List.of(new Recalled("和张小凡的私聊（10月15日）", "\n  张小凡（说）：睡了吗？\n  陆雪琪（说）：没有。")),
                "我比自己以为的更愿意相信他。");
        snapshot("character_in_scene_inner", Prompts.characterInScene(p, Perceive.scene(A2_LINES, NAMES, LU)));
    }

    /** 私聊的记录（整理与幕后用）：隔了半小时插一行时间，别人的心声看不到。私聊里开口的两步见 MindPromptsTest。 */
    @Test
    void aPrivateChatTranscript() {
        Instant t = Instant.parse("2026-10-15T13:05:00Z");
        List<Message> messages = List.of(
                new Message(1, XF, "say", "睡了吗？", true, "user", false, null, t),
                new Message(2, LU, "say", "没有。", false, "reply", false, null, t.plusSeconds(60)),
                new Message(3, LU, "inner", "这么晚了。", false, "reply", false, null, t.plusSeconds(60)),
                new Message(4, XF, "say", "明天我去渡口。", true, "user", false, null, t.plusSeconds(3 * 3600)));
        String transcript = Perceive.chat(messages, NAMES, LU);
        assertThat(transcript).contains("——10月15日 晚上9:05——", "——10月16日 凌晨0:05——", "陆雪琪（心声）：这么晚了。");
        assertThat(Perceive.chat(messages, NAMES, XF)).doesNotContain("这么晚了");
    }

    @Test
    void consolidationNumbersTheConcerns() throws IOException {
        snapshot("consolidate", Prompts.consolidate(new Prompts.Consolidate("陆雪琪", WORLD, LU_XUEQI, null,
                List.of(new ViewOf("张小凡", "他变了很多。")), List.of(new Care("欠他一句对不起。", "张小凡")), "第2场",
                Perceive.scene(A2_LINES, NAMES, LU), List.of("张小凡", "碧瑶"))));
        String withoutConcerns = dump(Prompts.consolidate(new Prompts.Consolidate("陆雪琪", WORLD, LU_XUEQI, "我还是我。",
                List.of(), List.of(), "和张小凡的私聊", "张小凡（说）：在吗？", List.of("张小凡"))));
        assertThat(withoutConcerns).doesNotContain("【了结·编号】").contains("【如今的你】\n我还是我。");
    }

    @Test
    void consentCheckSeesInnerVoices() throws IOException {
        snapshot("consent", Prompts.consent("碧瑶（心声）：别碰我。", "张小凡", "握住碧瑶的手。", List.of("碧瑶", "陆雪琪")));
    }

    @Test
    void creation() throws IOException {
        snapshot("create_ask", Prompts.ask("诛仙，十年后的陆雪琪", null, List.of()));
        snapshot("create_write_new_world", Prompts.write("诛仙，十年后的陆雪琪",
                List.of(new Prompts.Answer("从哪个时期开始？", "诛仙剑阵后十年")), null, List.of()));
        snapshot("create_write_existing_world", Prompts.write("再添一位：曾书书", List.of(), WORLD, List.of("陆雪琪", "张小凡")));
        snapshot("create_revise", Prompts.revise("【人物】曾书书\n【小传】风回峰弟子。", "曾书书的档案", "【小传】风回峰弟子。", "更爱说笑"));
        snapshot("create_check", Prompts.check("【人物】曾书书\n【小传】风回峰弟子。", WORLD));
    }

    // ---- 规则 ----

    @Test
    void aCharacterNeverSeesNarratorOnlySettingsOrOthersInnerVoice() {
        String text = dump(Prompts.characterInScene(luAsBorn(), Perceive.scene(A2_LINES, NAMES, LU)));
        for (String secret : WORLD_NARRATOR.lines().filter(l -> !l.isBlank() && !l.startsWith("【")).skip(1).toList()) {
            assertThat(text).doesNotContain(secret);
        }
        assertThat(text).doesNotContain("碧瑶的秘密心声").contains("陆雪琪的秘密心声");
    }

    @Test
    void theNarratorSeesNarratorOnlySettingsButNoInnerVoice() {
        String text = dump(Prompts.narratorClosing(a2(), Perceive.scene(A2_LINES, NAMES, null)));
        assertThat(text).contains("一个月前，碧瑶醒了").doesNotContain("心声");
    }

    @Test
    void anEmptyInnerLifeAddsNothing() {
        assertThat(Prompts.inner(luAsBorn())).isEmpty();
        String text = dump(Prompts.characterInScene(lu(List.of(), List.of(), List.of(), List.of(), "新的我"),
                Perceive.EMPTY));
        assertThat(text).contains("【如今的你】\n新的我").doesNotContain("【你眼中的他们】", "【你放不下的事】", "【你记得的】");
    }

    @Test
    void aCreatedProfileGetsItsNameForTheNarrator() {
        assertThat(Prompts.profileWithName("阿青", "【小传】\n客栈的伙计。")).isEqualTo("阿青\n\n【小传】\n客栈的伙计。");
        assertThat(Prompts.profileWithName("陆雪琪", LU_XUEQI)).isEqualTo(LU_XUEQI);
    }
}
