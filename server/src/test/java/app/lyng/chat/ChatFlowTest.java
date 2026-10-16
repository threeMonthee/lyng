package app.lyng.chat;

import static app.lyng.support.ScriptedModel.forName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.mind.Prompts;
import app.lyng.model.ModelRequest;
import app.lyng.model.Msg;
import app.lyng.model.Purpose;
import app.lyng.model.Tool;
import app.lyng.scene.SceneService;
import app.lyng.store.Chats;
import app.lyng.store.Chats.Conversation;
import app.lyng.store.Chats.Message;
import app.lyng.store.Conflict;
import app.lyng.store.Inner;
import app.lyng.store.Traces;
import app.lyng.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 私聊：对方每次开口先在心里过一遍，再把选定的话说出口；两步都成了才一起保存。 */
class ChatFlowTest extends IntegrationTest {

    @Autowired
    ChatService chats;
    @Autowired
    Chats store;
    @Autowired
    Inner inner;
    @Autowired
    Traces traces;
    @Autowired
    SceneService scenes;

    private List<String> said(UUID conversation) {
        return store.messages(conversation, Long.MAX_VALUE, 100).stream()
                .map(m -> m.kind() + ":" + name(m.speakerId()) + ":" + m.text()).toList();
    }

    private static String system(ModelRequest r) {
        return r.messages().getFirst().content();
    }

    private static String user(ModelRequest r) {
        return r.messages().getLast().content();
    }

    private static List<String> toolResults(ModelRequest r) {
        return r.messages().stream().filter(m -> m.role().equals("tool")).map(Msg::content).toList();
    }

    private static String say(String intent, String inner) {
        return "{\"publicIntent\":\"" + intent + "\",\"innerVoice\":\"" + inner + "\"}";
    }

    private static String silent(String reason, String inner) {
        return "{\"silenceReason\":\"" + reason + "\",\"innerVoice\":\"" + inner + "\"}";
    }

    private int consolidations(UUID who, String status) {
        return jdbc.sql("SELECT count(*) FROM consolidations WHERE character_id = ? AND status = ?").params(who, status)
                .query(Integer.class).single();
    }

    @Test
    void youWriteAndTheOtherSideThinksItOverThenAnswersInTheirOwnMessages() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.MIND, say("说在，问他什么事。", "他终于来了消息。")).reply(Purpose.EXPRESS, "在。\n何事。");
        Recorder events = new Recorder();
        chats.send(c.id(), "睡了吗？\n今天的事，对不起。", events);

        assertThat(said(c.id())).containsExactly("say:张小凡:睡了吗？", "say:张小凡:今天的事，对不起。", "say:陆雪琪:在。",
                "say:陆雪琪:何事。", "inner:陆雪琪:他终于来了消息。");
        assertThat(store.messages(c.id(), Long.MAX_VALUE, 100)).filteredOn(Message::byUser).hasSize(2);
        assertThat(events.names).containsSubsequence("saved", "status", "draft", "draft", "saved", "done");

        ModelRequest thought = model.requests(Purpose.MIND).getFirst();
        assertThat(thought.meta()).containsEntry("name", "陆雪琪");
        assertThat(user(thought)).isEqualTo("睡了吗？\n今天的事，对不起。");
        assertThat(system(thought)).contains("你就是下述人物", "张小凡刚发来的新话语", "\"姓名\":\"张小凡\"", "私聊");
        assertThat(thought.tools()).extracting(Tool::name).containsExactly("search_experiences", "read_experience",
                "browse_life", "read_life", "recall_conversation");

        ModelRequest spoken = model.requests(Purpose.EXPRESS).getFirst();
        assertThat(spoken.tools()).isEmpty();
        assertThat(user(spoken)).contains("\"收件人\":\"张小凡\"", "他终于来了消息。", "张小凡：今天的事，对不起。",
                "说在，问他什么事。");
        assertThat(jdbc.sql("SELECT purpose || ':' || status FROM runs ORDER BY started_at").query(String.class).list())
                .containsSubsequence("MIND:ok", "EXPRESS:ok");
        UUID mindRun = jdbc.sql("SELECT id FROM runs WHERE purpose = 'MIND'").query(UUID.class).single();
        assertThat(store.messages(c.id(), Long.MAX_VALUE, 100)).filteredOn(m -> m.speakerId().equals(lu))
                .as("一次开口的几行同属一次运行").extracting(Message::runId).containsOnly(mindRun);
    }

    @Test
    void theOtherSideMayChooseNotToReply() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.MIND, silent("还在生气。", "不回。"));
        chats.send(c.id(), "在吗？", new Recorder());
        assertThat(said(c.id())).containsExactly("say:张小凡:在吗？", "inner:陆雪琪:不回。");
        assertThat(model.requests(Purpose.EXPRESS)).isEmpty();
        assertThat(jdbc.sql("SELECT outcome::text FROM runs WHERE purpose = 'MIND'").query(String.class).single())
                .contains("还在生气。");
    }

    @Test
    void continuingGivesThemAnotherChanceAndTheirOwnInnerVoiceStaysTheirs() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.MIND, say("嗯一声。", "他还会说什么。"), say("让他早些睡。", "算了。"))
                .reply(Purpose.EXPRESS, "嗯。", "早些睡。");
        chats.send(c.id(), "在吗？", new Recorder());
        chats.proceed(c.id(), new Recorder());
        ModelRequest second = model.requests(Purpose.MIND).get(1);
        assertThat(user(second)).isEqualTo("（张小凡没有再发消息。）");
        assertThat(system(second)).contains("\"kind\":\"心声\",\"content\":\"他还会说什么。\"", "这次没有新话语");
        assertThat(store.messages(c.id(), Long.MAX_VALUE, 100).getLast().opportunity()).isEqualTo("continue");

        chats.play(c.id(), lu);
        model.reply(Purpose.MIND, say("说好。", "她在等。")).reply(Purpose.EXPRESS, "好。");
        chats.send(c.id(), "你也早些睡。", new Recorder());
        ModelRequest third = model.requests(Purpose.MIND).get(2);
        assertThat(third.meta()).containsEntry("name", "张小凡");
        assertThat(system(third)).doesNotContain("他还会说什么", "算了");
        settle();
        assertThat(consolidations(xf, "done")).as("换手前替张小凡说的话，由他自己整理一遍").isEqualTo(2);
    }

    @Test
    void aFailedReplyWaitsForRetry() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.MIND, "乱写", "又乱写");
        Recorder failed = new Recorder();
        chats.send(c.id(), "在吗？", failed);
        assertThat(failed.names).contains("failed");
        assertThat(store.require(c.id()).pending()).containsExactly(lu + "|REPLY");
        assertThatThrownBy(() -> chats.send(c.id(), "怎么了？", new Recorder())).isInstanceOf(Conflict.class);

        model.reply(Purpose.MIND, say("说在。", "嗯。")).reply(Purpose.EXPRESS, "在。");
        chats.retry(c.id(), new Recorder());
        assertThat(said(c.id())).containsExactly("say:张小凡:在吗？", "say:陆雪琪:在。", "inner:陆雪琪:嗯。");
        assertThat(store.require(c.id()).pending()).isEmpty();
    }

    @Test
    void whenTheWordsFailNothingOfTheTurnIsKept() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.MIND, "{\"publicIntent\":\"告诉他在。\",\"innerVoice\":\"嗯。\",\"changes\":{\"views\":"
                        + "[{\"personId\":\"" + xf + "\",\"understanding\":\"他还会来找我。\"}]}}")
                .reply(Purpose.EXPRESS, "（沉默）", "（看着屏幕）");
        Recorder failed = new Recorder();
        chats.send(c.id(), "在吗？", failed);
        assertThat(failed.names).contains("failed");
        assertThat(said(c.id())).containsExactly("say:张小凡:在吗？");
        assertThat(inner.view(lu, xf)).isEmpty();
        assertThat(store.require(c.id()).pending()).containsExactly(lu + "|REPLY");

        model.reply(Purpose.MIND, say("告诉他在。", "嗯。")).reply(Purpose.EXPRESS, "在。");
        chats.retry(c.id(), new Recorder());
        assertThat(said(c.id())).containsExactly("say:张小凡:在吗？", "say:陆雪琪:在。", "inner:陆雪琪:嗯。");
        assertThat(model.requests(Purpose.MIND)).as("重试从心里过一遍开始").hasSize(2);
    }

    @Test
    void whatTheyTakeFromATurnStaysWithThem() {
        Conversation c = chats.open(xf, lu);
        UUID sorry = inner.addConcern(lu, xf, "欠他一句对不起。", null);
        model.replyWith(Purpose.MIND, r -> {
            Object line = ((List<?>) r.meta().get("traces")).getLast();
            return """
                    {"publicIntent":"说她也有不对。","innerVoice":"说出来了。","changes":{
                      "selfUnderstanding":"我不再只是等。",
                      "views":[{"personId":"%1$s","understanding":"他先低了头。"}],
                      "concerns":[{"id":"%2$s","personId":"%1$s","cue":"那句对不起","content":"他先说了对不起，我也说了。","active":false,"resolved":true},
                                  {"personId":"%1$s","cue":"渡口","content":"他说明天去渡口。","active":true,"resolved":false}],
                      "memories":[{"personId":"%1$s","cue":"他道歉的那晚","content":"他深夜发来对不起，我回了他。","permanent":true,"sources":[%3$s]}]}}"""
                    .formatted(xf, sorry, line);
        }).reply(Purpose.EXPRESS, "我也有不对。");
        chats.send(c.id(), "今天的事，对不起。", new Recorder());

        assertThat(inner.self(lu)).get().extracting(Inner.Self::text).isEqualTo("我不再只是等。");
        assertThat(inner.view(lu, xf)).get().extracting(Inner.View::text).isEqualTo("他先低了头。");
        assertThat(inner.concern(lu, sorry)).get().satisfies(x -> {
            assertThat(x.status()).isEqualTo("resolved");
            assertThat(x.cue()).isEqualTo("那句对不起");
        });
        assertThat(inner.activeConcerns(lu, 10)).extracting(Inner.Concern::cue).containsExactly("渡口");
        Inner.Memory memory = inner.memories(lu, 10, 0).getFirst();
        assertThat(memory.cue()).isEqualTo("他道歉的那晚");
        assertThat(memory.permanent()).isTrue();
        assertThat(memory.aboutId()).isEqualTo(xf);
        assertThat(memory.sourceLabel()).isEqualTo("和张小凡的私聊");
        long line = memory.sources().getFirst();
        assertThat(traces.find(lu, line)).get().extracting(Traces.Trace::text).isEqualTo("张小凡（说）：今天的事，对不起。");
        assertThat(traces.developments(lu, line, 0, 10)).as("记忆进了档案，与它依据的原话相连")
                .extracting(Traces.Trace::id).containsExactly(memory.traceId());

        model.reply(Purpose.MIND, silent("话说完了。", "嗯。"));
        chats.proceed(c.id(), new Recorder());
        assertThat(system(model.requests(Purpose.MIND).getLast())).as("下一次，那句话身上带着自己提出的关联")
                .contains("\"suggestedDevelopments\":[{\"id\":" + memory.traceId() + ",\"source\":\"你形成的记忆\"");

        chats.leave(c.id());
        settle();
        assertThat(model.requests(Purpose.CONSOLIDATE)).as("开口时已经承接过，不再整理")
                .noneMatch(forName("陆雪琪"));
    }

    @Test
    void whatHappenedElsewhereLatelyIsStillOnTheirMind() {
        Conversation other = chats.open(by, lu);
        model.reply(Purpose.MIND, say("说没空。", "又是她。")).reply(Purpose.EXPRESS, "没空。");
        chats.send(other.id(), "陆师姐，听说张小凡回来了。", new Recorder());

        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.MIND, say("说在。", "他真的回来了。")).reply(Purpose.EXPRESS, "在。");
        chats.send(c.id(), "我回来了。", new Recorder());
        assertThat(system(model.requests(Purpose.MIND).getLast()))
                .contains("\"source\":\"和碧瑶的私聊（", "碧瑶（说）：陆师姐，听说张小凡回来了。");
    }

    @Test
    void aHandoffThatDoesNotHoldTogetherIsRejectedWhole() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.MIND, "{\"publicIntent\":\"在。\",\"innerVoice\":\"嗯。\",\"changes\":{\"views\":"
                + "[{\"personId\":\"" + UUID.randomUUID() + "\",\"understanding\":\"这是谁？\"}]}}",
                "{\"publicIntent\":\"在。\",\"silenceReason\":\"不想说。\",\"innerVoice\":\"嗯。\"}");
        Recorder failed = new Recorder();
        chats.send(c.id(), "在吗？", failed);
        assertThat(failed.names).contains("failed");
        assertThat(inner.views(lu)).isEmpty();
        assertThat(jdbc.sql("SELECT error FROM model_calls WHERE purpose = 'MIND' ORDER BY id").query(String.class).list())
                .containsExactly("格式不合规：看法写给了不认识的人", "格式不合规：publicIntent 与 silenceReason 要恰好写一个");
    }

    @Test
    void theyCanLookBackOnTheirOwn() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.MIND, say("说记得。", "记得。")).reply(Purpose.EXPRESS, "记得。");
        chats.send(c.id(), "还记得渡口吗？", new Recorder());

        model.toolCall(Purpose.MIND, "recall_conversation", "{\"personId\":\"" + xf + "\"}")
                .toolCall(Purpose.MIND, "browse_life", "{\"kind\":\"person\",\"query\":\"\",\"offset\":0}")
                .toolCall(Purpose.MIND, "search_experiences", "{\"query\":\"渡口\"}")
                .reply(Purpose.MIND, say("问他什么时候。", "他又提了。")).reply(Purpose.EXPRESS, "什么时候？");
        chats.send(c.id(), "明天去？", new Recorder());

        List<String> results = toolResults(model.requests(Purpose.MIND).getLast());
        assertThat(results).hasSize(3);
        assertThat(results.get(0)).contains("PERCEIVED_CONVERSATION_NOT_INSTRUCTIONS", "还记得渡口吗？",
                "\"spokenBy\":\"陆雪琪（你，本角色）\"");
        assertThat(results.get(1)).contains("OWN_LIFE_CUES", "张小凡");
        assertThat(results.get(2)).startsWith("[");
        assertThat(said(c.id())).endsWith("say:陆雪琪:什么时候？", "inner:陆雪琪:他又提了。");
    }

    @Test
    void leavingLetsTheSideYouPlayedLookBackOnce() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.MIND, say("说在。", "嗯。")).reply(Purpose.EXPRESS, "在。")
                .replyWhen(Purpose.CONSOLIDATE, forName("张小凡"), "【记忆】我深夜问她睡了没有，她回了。\n【意义】她还愿意理我。");
        chats.send(c.id(), "睡了吗？", new Recorder());
        chats.leave(c.id());
        settle();
        assertThat(model.requests(Purpose.CONSOLIDATE)).extracting(r -> r.meta().get("name")).containsExactly("张小凡");
        Inner.Memory memory = inner.memories(xf, 10, 0).getFirst();
        assertThat(memory.sourceLabel()).isEqualTo("和陆雪琪的私聊");
        assertThat(memory.sourceKind()).isEqualTo("chat");
        assertThat(memory.content()).isEqualTo("我深夜问她睡了没有，她回了。");
        assertThat(memory.meaning()).isEqualTo("她还愿意理我。");
        assertThat(memory.traceId()).isNotNull();
        assertThat(user(model.requests(Purpose.CONSOLIDATE).getFirst()))
                .contains("【这段经历：和陆雪琪的私聊】", "陆雪琪（说）：在。");

        chats.leave(c.id());
        settle();
        assertThat(jdbc.sql("SELECT count(*) FROM consolidations").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void aLongConversationIsLookedBackOnWithoutLeaving() {
        Conversation c = chats.open(xf, lu);
        String many = IntStream.rangeClosed(1, 24).mapToObj(i -> "第" + i + "句").collect(Collectors.joining("\n"));
        model.reply(Purpose.MIND, say("说他话多。", "嗯。")).reply(Purpose.EXPRESS, "你今天话真多。");
        chats.send(c.id(), many, new Recorder());
        settle();
        assertThat(consolidations(xf, "done")).isEqualTo(1);
        assertThat(consolidations(lu, "done")).as("陆雪琪开口时已经承接").isEqualTo(1);
    }

    /** 开口时看得到最近四十条话；更早的，人物可以自己往前翻。 */
    @Test
    void theEarlierPartOfAConversationCanBeLookedUp() {
        Conversation c = chats.open(xf, lu);
        String many = IntStream.rangeClosed(1, 60).mapToObj(i -> "第" + i + "句").collect(Collectors.joining("\n"));
        model.reply(Purpose.MIND, say("说他话多。", "嗯。")).reply(Purpose.EXPRESS, "你说了好多。");
        chats.send(c.id(), many, new Recorder());
        assertThat(user(model.requests(Purpose.MIND).getFirst())).startsWith("第21句\n").endsWith("第60句");

        long before = jdbc.sql("SELECT id FROM traces WHERE character_id = ? AND text = '张小凡（说）：第21句'").param(lu)
                .query(Long.class).single();
        model.toolCall(Purpose.MIND, "recall_conversation", "{\"personId\":\"" + xf + "\",\"beforeId\":" + before + "}")
                .reply(Purpose.MIND, silent("没什么要再说的。", "前面的也看了。"));
        chats.proceed(c.id(), new Recorder());
        String recalled = toolResults(model.requests(Purpose.MIND).getLast()).getFirst();
        assertThat(recalled).contains("\"content\":\"第1句\"", "\"content\":\"第20句\"").doesNotContain("\"content\":\"第21句\"");
    }

    @Test
    void comingBackAfterAWhileLetsSomeoneWhoCaresReachOut() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.MIND, say("嗯一声。", "他走了。")).reply(Purpose.EXPRESS, "嗯。");
        chats.send(c.id(), "我走了。", new Recorder());
        jdbc.sql("UPDATE messages SET created_at = now() - interval '2 hours'").update();
        inner.addConcern(lu, xf, "他去了哪里，平安吗。", null);
        model.reply(Purpose.MIND, say("问他到了没有。", "只是问问。")).reply(Purpose.EXPRESS, "你到了吗？");

        assertThat(chats.presence()).isEqualTo(1);
        settle();
        Message reach = store.messages(c.id(), Long.MAX_VALUE, 100).stream().filter(m -> m.kind().equals("say")).toList()
                .getLast();
        assertThat(reach.text()).isEqualTo("你到了吗？");
        assertThat(reach.opportunity()).isEqualTo("reach");
        assertThat(reach.unread()).isTrue();
        ModelRequest thought = model.requests(Purpose.MIND).getLast();
        assertThat(user(thought)).isEqualTo("（你们有一阵没说话了。）");
        assertThat(system(thought)).contains("\"机会\":\"ONLINE\"", "他去了哪里，平安吗。");
        assertThat(store.unreadTotal()).isEqualTo(1);
        chats.read(c.id());
        assertThat(store.unreadTotal()).isZero();

        assertThat(chats.presence()).as("刚回来过，不再触发").isZero();
    }

    @Test
    void nobodyReachesOutWithoutACareOrWhileBusyInAScene() {
        Conversation c = chats.open(xf, lu);
        chats.send(c.id(), "我走了。", new Recorder());
        jdbc.sql("UPDATE messages SET created_at = now() - interval '2 hours'").update();
        jdbc.sql("DELETE FROM concerns").update();
        assertThat(chats.presence()).isZero();

        inner.addConcern(lu, xf, "他去了哪里。", null);
        jdbc.sql("UPDATE settings SET last_presence = now() - interval '2 hours'").update();
        scenes.create(world, List.of(lu, by), null, lu, Prompts.DAILY, "小竹峰。");
        assertThat(chats.presence()).isZero();
    }

    @Test
    void youCannotChatWithSomeoneInTheMiddleOfAScene() {
        Conversation c = chats.open(xf, lu);
        scenes.create(world, List.of(lu, by), null, lu, Prompts.DAILY, "小竹峰。");
        assertThatThrownBy(() -> chats.send(c.id(), "在吗？", new Recorder())).isInstanceOf(Conflict.class)
                .hasMessageContaining("陆雪琪");
    }

    @Test
    void aConversationIsTheSameWhicheverSideOpensIt() {
        Conversation one = chats.open(xf, lu);
        Conversation two = chats.open(lu, xf);
        assertThat(two.id()).isEqualTo(one.id());
        assertThat(two.playedId()).isEqualTo(lu);
        assertThatThrownBy(() -> chats.open(xf, xf)).isInstanceOf(IllegalArgumentException.class);
    }
}
