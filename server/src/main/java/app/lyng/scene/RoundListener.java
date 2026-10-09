package app.lyng.scene;

import app.lyng.event.SceneEvent;
import app.lyng.markup.Beat;

/** 一轮进行中，把发生的事告诉外面（SSE 推给客户端）。实现不得抛出异常：客户端断开也要让这一轮在服务端跑完。 */
public interface RoundListener {

    /** 一件事已经落库。 */
    void event(SceneEvent event);

    /** 人物还在生成时，一个已经写完的气泡。是暂定的：这次生成校验通过后以 event 为准，失败会被 retract。 */
    void bubble(String speaker, Beat beat);

    /** 这个人物暂定的气泡作废（这次生成技术失败）。 */
    void retract(String speaker);

    RoundListener NONE = new RoundListener() {
        @Override
        public void event(SceneEvent event) {}

        @Override
        public void bubble(String speaker, Beat beat) {}

        @Override
        public void retract(String speaker) {}
    };
}
