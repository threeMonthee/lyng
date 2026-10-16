package app.lyng.create;

import app.lyng.mind.Markup;
import app.lyng.store.Worlds;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 第一次启动、还没有任何世界时，放入“诛仙”：世界与三个人物来自盲测验证集（validation/），
 * 初始看法与牵挂在 seed/inner/。按一念生的草稿写法拼起来，走同一条诞生路径。
 * 世界文字保持验证时的原样（连同第一行标题），叙事者看到的与盲测时一致。
 */
@Component
public class Seed {

    private static final Logger log = LoggerFactory.getLogger(Seed.class);
    private static final List<String> CHARACTERS = List.of("lu-xueqi", "zhang-xiaofan", "bi-yao");

    private final Worlds worlds;
    private final CreationService creation;
    private final boolean enabled;

    public Seed(Worlds worlds, CreationService creation, @Value("${lyng.seed:true}") boolean enabled) {
        this.worlds = worlds;
        this.creation = creation;
        this.enabled = enabled;
    }

    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void plant() {
        if (!enabled || !worlds.worlds().isEmpty()) {
            return;
        }
        Markup.Draft draft = Markup.parseDraft(text(), true, List.of());
        // 档案保留验证时的原样（第一行是姓名）
        List<Markup.DraftCharacter> characters = new ArrayList<>();
        for (int i = 0; i < CHARACTERS.size(); i++) {
            Markup.DraftCharacter c = draft.characters().get(i);
            characters.add(new Markup.DraftCharacter(c.name(), read("seed/characters/" + CHARACTERS.get(i) + ".txt").strip(),
                    c.views(), c.concerns()));
        }
        creation.bear(null, new Markup.Draft(draft.world(), characters));
        log.info("已放入种子世界：诛仙");
    }

    /** 拼成一念生草稿的写法。 */
    static String text() {
        StringBuilder out = new StringBuilder()
                .append("【世界名】诛仙\n")
                .append("【世界·公开】\n").append(read("seed/world.txt").strip()).append('\n')
                .append("【世界·只有叙事者知道】\n").append(read("seed/world-narrator.txt").strip()).append('\n');
        for (String c : CHARACTERS) {
            out.append("【人物】").append(read("seed/characters/" + c + ".txt").strip()).append('\n')
                    .append(read("seed/inner/" + c + ".txt").strip()).append('\n');
        }
        return out.toString();
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读不到种子文件 " + path, e);
        }
    }
}
