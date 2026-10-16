package app.lyng.chat;

import static app.lyng.support.ScriptedModel.forName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lyng.mind.Prompts;
import app.lyng.model.ModelRequest;
import app.lyng.model.Purpose;
import app.lyng.scene.SceneService;
import app.lyng.store.Chats;
import app.lyng.store.Chats.Conversation;
import app.lyng.store.Chats.Message;
import app.lyng.store.Conflict;
import app.lyng.store.Inner;
import app.lyng.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ChatFlowTest extends IntegrationTest {

    @Autowired
    ChatService chats;
    @Autowired
    Chats store;
    @Autowired
    Inner inner;
    @Autowired
    SceneService scenes;

    private List<String> said(UUID conversation) {
        return store.messages(conversation, Long.MAX_VALUE, 100).stream()
                .map(m -> m.kind() + ":" + name(m.speakerId()) + ":" + m.text()).toList();
    }

    private static String user(ModelRequest r) {
        return r.messages().getLast().content();
    }

    @Test
    void youWriteAndTheOtherSideAnswersInTheirOwnMessages() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.CHARACTER, "陆雪琪（说）：在。\n陆雪琪（说）：何事。\n陆雪琪（心声）：他终于来了消息。");
        Recorder events = new Recorder();
        chats.send(c.id(), "睡了吗？\n今天的事，对不起。", events);

        assertThat(said(c.id())).containsExactly("say:张小凡:睡了吗？", "say:张小凡:今天的事，对不起。", "say:陆雪琪:在。",
                "say:陆雪琪:何事。", "inner:陆雪琪:他终于来了消息。");
        assertThat(store.messages(c.id(), Long.MAX_VALUE, 100)).filteredOn(Message::byUser).hasSize(2);
        assertThat(events.names).containsSubsequence("saved", "status", "draft", "draft", "saved", "done");

        ModelRequest r = model.requests(Purpose.CHARACTER).getFirst();
        assertThat(r.meta()).containsEntry("name", "陆雪琪").containsEntry("allowActions", false);
        assertThat(user(r)).contains("张小凡（说）：睡了吗？\n张小凡（说）：今天的事，对不起。", "张小凡刚发来了上面最后的话。");
        assertThat(user(r)).doesNotContain("（行）");
    }

    @Test
    void theOtherSideMayChooseNotToReply() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.CHARACTER, "陆雪琪（心声）：不回。");
        chats.send(c.id(), "在吗？", new Recorder());
        assertThat(said(c.id())).containsExactly("say:张小凡:在吗？", "inner:陆雪琪:不回。");
    }

    @Test
    void continuingGivesThemAnotherChanceAndTheirOwnInnerVoiceStaysTheirs() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.CHARACTER, "陆雪琪（说）：嗯。\n陆雪琪（心声）：他还会说什么。",
                "陆雪琪（说）：早些睡。\n陆雪琪（心声）：算了。");
        chats.send(c.id(), "在吗？", new Recorder());
        chats.proceed(c.id(), new Recorder());
        ModelRequest second = model.requests(Purpose.CHARACTER).get(1);
        assertThat(user(second)).contains("陆雪琪（心声）：他还会说什么。", "张小凡没有再说什么。");
        assertThat(store.messages(c.id(), Long.MAX_VALUE, 100).getLast().opportunity()).isEqualTo("continue");

        chats.play(c.id(), lu);
        model.reply(Purpose.CHARACTER, "张小凡（说）：好。\n张小凡（心声）：她在等。");
        chats.send(c.id(), "你也早些睡。", new Recorder());
        assertThat(user(model.requests(Purpose.CHARACTER).get(2))).doesNotContain("他还会说什么", "算了");
    }

    @Test
    void aFailedReplyWaitsForRetry() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.CHARACTER, "乱写", "又乱写");
        Recorder failed = new Recorder();
        chats.send(c.id(), "在吗？", failed);
        assertThat(failed.names).contains("failed");
        assertThat(store.require(c.id()).pending()).containsExactly(lu + "|REPLY");
        assertThatThrownBy(() -> chats.send(c.id(), "怎么了？", new Recorder())).isInstanceOf(Conflict.class);

        model.reply(Purpose.CHARACTER, "陆雪琪（说）：在。\n陆雪琪（心声）：嗯。");
        chats.retry(c.id(), new Recorder());
        assertThat(said(c.id())).containsExactly("say:张小凡:在吗？", "say:陆雪琪:在。", "inner:陆雪琪:嗯。");
        assertThat(store.require(c.id()).pending()).isEmpty();
    }

    @Test
    void leavingLetsBothSidesLookBackOnce() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.CHARACTER, "陆雪琪（说）：在。\n陆雪琪（心声）：嗯。")
                .replyWhen(Purpose.CONSOLIDATE, forName("陆雪琪"), "【记忆】他深夜来消息问我睡了没有。\n【意义】他还记得我。")
                .replyWhen(Purpose.CONSOLIDATE, forName("张小凡"), "【无】");
        chats.send(c.id(), "睡了吗？", new Recorder());
        chats.leave(c.id());
        settle();
        Inner.Memory memory = inner.memories(lu, 10, 0).getFirst();
        assertThat(memory.sourceLabel()).isEqualTo("和张小凡的私聊");
        assertThat(memory.sourceKind()).isEqualTo("chat");
        String prompt = user(model.requests(Purpose.CONSOLIDATE).stream().filter(forName("陆雪琪")).findFirst().orElseThrow());
        assertThat(prompt).contains("【这段经历：和张小凡的私聊】", "张小凡（说）：睡了吗？");

        chats.leave(c.id());
        settle();
        assertThat(jdbc.sql("SELECT count(*) FROM consolidations").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void aLongConversationIsLookedBackOnWithoutLeaving() {
        Conversation c = chats.open(xf, lu);
        String many = IntStream.rangeClosed(1, 24).mapToObj(i -> "第" + i + "句").collect(Collectors.joining("\n"));
        model.reply(Purpose.CHARACTER, "陆雪琪（说）：你今天话真多。\n陆雪琪（心声）：嗯。");
        chats.send(c.id(), many, new Recorder());
        settle();
        assertThat(jdbc.sql("SELECT count(*) FROM consolidations WHERE source_id = ?").param(c.id()).query(Integer.class)
                .single()).isEqualTo(2);
    }

    /** 开口时看得到最近二十来个来回，不只是最后几句。 */
    @Test
    void theEarlierPartOfAConversationStaysInView() {
        Conversation c = chats.open(xf, lu);
        String many = IntStream.rangeClosed(1, 60).mapToObj(i -> "第" + i + "句").collect(Collectors.joining("\n"));
        model.reply(Purpose.CHARACTER, "陆雪琪（说）：你说了好多。\n陆雪琪（心声）：嗯。");
        chats.send(c.id(), many, new Recorder());
        assertThat(user(model.requests(Purpose.CHARACTER).getFirst())).contains("张小凡（说）：第1句\n", "张小凡（说）：第60句");
        settle();
    }

    @Test
    void comingBackAfterAWhileLetsSomeoneWhoCaresReachOut() {
        Conversation c = chats.open(xf, lu);
        model.reply(Purpose.CHARACTER, "陆雪琪（说）：嗯。\n陆雪琪（心声）：他走了。");
        chats.send(c.id(), "我走了。", new Recorder());
        jdbc.sql("UPDATE messages SET created_at = now() - interval '2 hours'").update();
        inner.addConcern(lu, xf, "他去了哪里，平安吗。", null);
        model.reply(Purpose.CHARACTER, "陆雪琪（说）：你到了吗？\n陆雪琪（心声）：只是问问。");

        assertThat(chats.presence()).isEqualTo(1);
        settle();
        Message reach = store.messages(c.id(), Long.MAX_VALUE, 100).stream().filter(m -> m.kind().equals("say")).toList()
                .getLast();
        assertThat(reach.text()).isEqualTo("你到了吗？");
        assertThat(reach.opportunity()).isEqualTo("reach");
        assertThat(reach.unread()).isTrue();
        assertThat(user(model.requests(Purpose.CHARACTER).getLast())).contains("你们有一阵没说话了。你想起了张小凡。");
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
