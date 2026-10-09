package app.lyng.model;

import java.util.function.Consumer;

/**
 * 确定性模型替身：输出只取决于调用用途和调用次序，格式永远合规，费用固定。
 * 不发网络请求。fake 模式（lyng.model.backend=fake）下整个应用用它，可以不花钱地走完全部流程。
 *
 * @param chunkChars 每次回调多少个字符，用来演练流式解析
 * @param chunkDelayMs 每个片段之间的停顿，只在手动查看界面时调大
 */
public class FakeChatModel implements ChatModel {

    private static final double COST_PER_CALL = 0.0001;

    private final int chunkChars;
    private final long chunkDelayMs;
    private int calls;

    public FakeChatModel(int chunkChars, long chunkDelayMs) {
        this.chunkChars = chunkChars;
        this.chunkDelayMs = chunkDelayMs;
    }

    @Override
    public Completion complete(ChatRequest request, Consumer<String> onDelta) {
        int n;
        synchronized (this) {
            n = ++calls;
        }
        String text = reply(request.purpose(), n);
        long started = System.nanoTime();
        if (onDelta != null) {
            stream(text, onDelta);
        }
        return new Completion(text, "stop", COST_PER_CALL, new Usage(0, text.length(), 0), "替身",
                0, (System.nanoTime() - started) / 1_000_000);
    }

    private void stream(String text, Consumer<String> onDelta) {
        for (int i = 0; i < text.length(); i += chunkChars) {
            onDelta.accept(text.substring(i, Math.min(text.length(), i + chunkChars)));
            if (chunkDelayMs > 0) {
                try {
                    Thread.sleep(chunkDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    static String reply(CallPurpose purpose, int n) {
        String name = purpose.subject();
        return switch (purpose.kind()) {
            case "opening" -> "（替身开场）夜里的院子很静，石桌上还留着一盏没熄的灯。";
            case "closing" -> "（替身收尾）灯火渐渐暗了下去，有一句话谁也没有说出口。";
            case "round" -> "叙事：" + ("做".equals(name) ? "（替身叙事 " + n + "）风从院门口吹了进来。" : "无");
            case "character" -> name + "（行）：" + name + "抬眼看了他一下。\n"
                    + name + "（说）：（" + name + "的替身台词 " + n + "）\n"
                    + name + "（心声）：（" + name + "的替身心声 " + n + "）";
            case "recall" -> recall(purpose);
            case "draft" -> draft();
            default -> throw new IllegalArgumentException("未知调用用途：" + purpose.kind());
        };
    }

    private static String recall(CallPurpose purpose) {
        StringBuilder text = new StringBuilder("回想：（" + purpose.subject() + "的替身回想）");
        for (String other : purpose.others()) {
            text.append("\n看待").append(other).append("：（替身看法）");
        }
        return text.toString();
    }

    private static String draft() {
        return """
                【世界名】替身世界
                【世界·公开】
                这是一个替身生成的世界：山脚有一座小镇，镇上有一家客栈。
                【世界·只有叙事者知道】
                客栈的地窖里藏着一个只有叙事者知道的秘密。
                【人物】阿青
                【小传】
                阿青是客栈的伙计，从小在镇上长大。

                【外面与里面】
                她对客人总是笑，夜里却一个人在后院数星星。
                【人物】阿远
                【小传】
                阿远是路过的刀客，话不多。

                【外面与里面】
                他看起来冷，其实记得每个人的名字。
                """;
    }
}
