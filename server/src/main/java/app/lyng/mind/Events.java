package app.lyng.mind;

import java.util.Map;

/**
 * 跑模型的过程中发给页面的事件（接口层转成 SSE）。客户端断开后照常跑完，事件丢弃即可。
 *
 * <ul>
 *   <li>status：正在做哪一步（step）、谁（speakerId）</li>
 *   <li>draft：一段临时内容（speakerId、kind、text），还没落库</li>
 *   <li>discard：这次尝试作废，收回临时内容</li>
 *   <li>saved：这一步落库的行</li>
 *   <li>notice：不算失败的提示（如同意检查没通过）</li>
 *   <li>failed：技术失败（message、runId）</li>
 * </ul>
 */
public interface Events {

    Events NONE = (event, data) -> {};

    void emit(String event, Map<String, Object> data);
}
