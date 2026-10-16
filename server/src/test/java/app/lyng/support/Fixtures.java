package app.lyng.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 诛仙盲测集（世界、人物档案在 validation/，开场在测试资源里），测试里当作真实形态的数据用。 */
public final class Fixtures {

    public static final String WORLD = read("seed/world.txt");
    public static final String WORLD_NARRATOR = read("seed/world-narrator.txt");
    public static final String LU_XUEQI = read("seed/characters/lu-xueqi.txt");
    public static final String ZHANG_XIAOFAN = read("seed/characters/zhang-xiaofan.txt");
    public static final String BI_YAO = read("seed/characters/bi-yao.txt");
    public static final String OPENING_A1 = read("fixtures/opening-A1.txt");
    public static final String OPENING_A2 = read("fixtures/opening-A2.txt");
    public static final String OPENING_B3 = read("fixtures/opening-B3.txt");

    private Fixtures() {}

    /** 诛仙世界与三个人物（陆雪琪、张小凡、碧瑶），不带初始看法与牵挂。 */
    public static String draft() {
        return "【世界名】诛仙\n【世界·公开】\n" + WORLD + "\n【世界·只有叙事者知道】\n" + WORLD_NARRATOR + "\n"
                + "【人物】" + LU_XUEQI + "\n【人物】" + ZHANG_XIAOFAN + "\n【人物】" + BI_YAO + "\n";
    }

    public static String read(String resource) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到测试资源：" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
