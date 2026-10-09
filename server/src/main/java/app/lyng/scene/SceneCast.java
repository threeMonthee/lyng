package app.lyng.scene;

import app.lyng.world.Character;
import app.lyng.world.World;
import java.util.List;
import java.util.stream.Stream;

/** 一场戏的世界与在场人物。双人戏：一个被附身的人物，一个 AI 人物。 */
public record SceneCast(World world, Character possessed, List<Character> ai) {

    public List<Character> present() {
        return Stream.concat(Stream.of(possessed), ai.stream()).toList();
    }
}
