package app.lyng.model;

import java.util.List;

/** 发给模型的一条消息。assistant 可带工具调用；tool 带 toolCallId。 */
public record Msg(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public static Msg system(String content) {
        return new Msg("system", content, List.of(), null);
    }

    public static Msg user(String content) {
        return new Msg("user", content, List.of(), null);
    }

    public static Msg assistant(String content, List<ToolCall> calls) {
        return new Msg("assistant", content, calls, null);
    }

    public static Msg tool(String callId, String content) {
        return new Msg("tool", content, List.of(), callId);
    }
}
