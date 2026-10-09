package app.lyng.support;

import app.lyng.event.SceneEvent;
import app.lyng.markup.Beat;
import app.lyng.scene.RoundListener;
import app.lyng.world.Character;
import app.lyng.world.World;
import app.lyng.world.WorldRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 测试里的一个世界、两个人物（阿远被附身，阿青由 AI 驱动）和一个记录一轮里发生了什么的监听器。 */
public record StoryFixture(UUID userId, World world, Character qing, Character yuan) {

    public static StoryFixture create(JdbcClient jdbc, WorldRepository worlds) {
        UUID userId = jdbc.sql("SELECT id FROM users WHERE name = 'me'").query(UUID.class).single();
        World world = worlds.insertWorld(userId, "客栈", "山脚有一座小镇，镇上有一家客栈。", "客栈的地窖里藏着一个秘密。");
        Character qing = worlds.insertCharacter(userId, world.id(), "阿青", "阿青是客栈的伙计，对客人总是笑，夜里却一个人数星星。");
        Character yuan = worlds.insertCharacter(userId, world.id(), "阿远", "阿远是路过的刀客，话不多，记得每个人的名字。");
        return new StoryFixture(userId, world, qing, yuan);
    }

    /** 记录一轮里推给客户端的一切，按先后。 */
    public static final class Log implements RoundListener {
        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        private final List<SceneEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void event(SceneEvent event) {
            events.add(event);
            lines.add("event:" + event.kind().name().toLowerCase() + (event.step() != null ? "/" + event.step() : ""));
        }

        @Override
        public void bubble(String speaker, Beat beat) {
            lines.add("bubble:" + speaker + "/" + beat.tag());
        }

        @Override
        public void retract(String speaker) {
            lines.add("retract:" + speaker);
        }

        public List<String> lines() {
            return List.copyOf(lines);
        }

        public List<SceneEvent> events() {
            return List.copyOf(events);
        }
    }
}
