package app.lyng.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 从验证原型带来的诛仙盲测集（世界、人物档案、开场），测试里当作真实形态的数据用。 */
public final class Fixtures {

    public static final String WORLD = read("fixtures/world.txt");
    public static final String WORLD_NARRATOR = read("fixtures/world-narrator.txt");
    public static final String LU_XUEQI = read("fixtures/lu-xueqi.txt");
    public static final String ZHANG_XIAOFAN = read("fixtures/zhang-xiaofan.txt");
    public static final String BI_YAO = read("fixtures/bi-yao.txt");
    public static final String OPENING_A1 = read("fixtures/opening-A1.txt");
    public static final String OPENING_A2 = read("fixtures/opening-A2.txt");
    public static final String OPENING_B3 = read("fixtures/opening-B3.txt");

    private Fixtures() {}

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
