package app.lyng.model;

import java.util.List;
import java.util.function.Consumer;

/**
 * 确定性模型替身：输出只取决于调用用途与调用次序，格式永远合规，费用固定，不发网络请求。
 * 测试与 --fake 模式用它；delayMs 大于 0 时逐字慢慢吐出，便于看流式界面。
 */
public class FakeModel implements ChatModel {

    private static final double COST = 0.0001;

    private static final List<String> ACTIONS = List.of(
            "抬眼看了过来，又很快移开了目光。",
            "把手里的茶盏放下，指尖在杯沿停了一下。",
            "往窗边走了两步，背对着屋里的人。",
            "低头笑了一下，没有让人看见。");
    private static final List<String> SPEECH = List.of(
            "你来了。",
            "这么晚了，还不回去？",
            "我没有怪你。只是……你下次别这样了。",
            "外面的雨好像小了些。");
    private static final List<String> INNER = List.of(
            "他瘦了。",
            "不能让他看出来我在等。",
            "要是他再问一次，我就说实话。",
            "原来我一直记得这件事。");

    private final long delayMs;
    private int calls;

    public FakeModel(long delayMs) {
        this.delayMs = delayMs;
    }

    @Override
    public Completion complete(Request request, Consumer<String> onDelta) {
        int n;
        synchronized (this) {
            n = ++calls;
        }
        long started = System.nanoTime();
        String text = reply(request.purpose(), n);
        for (int i = 0; i < text.length(); i += 3) {
            onDelta.accept(text.substring(i, Math.min(text.length(), i + 3)));
            sleep();
        }
        return new Completion(text, "stop", COST, "替身", 0, text.length(), 0, 0, (System.nanoTime() - started) / 1_000_000);
    }

    private void sleep() {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static String reply(Purpose purpose, int n) {
        String subject = purpose.subject();
        return switch (purpose.kind()) {
            case "opening" -> "（替身开场）入夜，院子里只剩一盏灯，檐下的雨一滴一滴落在石阶上。";
            case "closing" -> "（替身收尾）灯芯爆了一下，屋里暗了半分。那句话，终究谁也没有说出口。";
            case "round" -> round(purpose, n);
            case "character" -> subject + "（行）：" + subject + pick(ACTIONS, n) + "\n"
                    + subject + "（说）：" + pick(SPEECH, n) + "\n"
                    + subject + "（心声）：" + pick(INNER, n);
            case "recall" -> recall(purpose);
            case "draft" -> draft(subject != null);
            default -> throw new IllegalArgumentException("未知调用用途：" + purpose.kind());
        };
    }

    private static String round(Purpose purpose, int n) {
        String narration = "做".equals(purpose.subject()) || "继续".equals(purpose.subject())
                ? "（替身叙事）风从院门口灌进来，吹得灯影晃了晃。" : "无";
        if (purpose.names().isEmpty()) {
            return "叙事：" + narration;
        }
        return "叙事：" + narration + "\n轮到：" + String.join("、", purpose.names());
    }

    private static String pick(List<String> options, int n) {
        return options.get(n % options.size());
    }

    private static String recall(Purpose purpose) {
        StringBuilder text = new StringBuilder("回想：（" + purpose.subject() + "的替身回想）那一夜的灯一直亮着，我记得他说话时的样子。");
        for (String other : purpose.names()) {
            text.append("\n看待").append(other).append("：（替身看法）比从前近了一点，也更看不透了。");
        }
        return text.toString();
    }

    private static String draft(boolean existingWorld) {
        String world = """
                【世界名】替身世界
                【世界·公开】
                群山脚下有一座小镇，镇口一家客栈，南来北往的人都在这里歇脚。
                【此刻】入秋，连着下了三天雨。
                【世界·只有叙事者知道】
                客栈地窖里藏着一封十年前的信，收信的人还活着。
                """;
        String characters = """
                【人物】阿青
                【小传】
                阿青是客栈的伙计，从小在镇上长大，认得每一个常来的客人。
                【外面与里面】
                她对谁都笑，夜里却一个人坐在后院数檐下的雨。
                【在乎一个人时】
                会记得他爱喝的茶，却从来不说是特意留的。
                【怎么生气】
                不吵，只是不再给他留灯。
                【怎么和好】
                第二天照旧留灯，像什么都没发生过。
                【此刻】
                雨夜，客栈里只剩一位客人。
                """;
        String second = """
                【人物】沈远
                【小传】
                沈远是路过的刀客，十年前离开这座镇子，如今回来找一个人。
                【外面与里面】
                他话少，看起来冷，其实记得镇上每个人的名字。
                【在乎一个人时】
                会站在她看不见的地方，等她屋里的灯熄了才走。
                【怎么生气】
                一句话不说，转身就走。
                【怎么和好】
                第二天一早，门口多了一包她小时候爱吃的点心。
                【此刻】
                他在客栈住了三天，还没开口问那件事。
                """;
        return existingWorld ? characters.replace("阿青", "小满") : world + characters + second;
    }
}
