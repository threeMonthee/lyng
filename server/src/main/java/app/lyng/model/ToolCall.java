package app.lyng.model;

/** 模型发起的一次工具调用；arguments 是模型给出的 JSON 原文。 */
public record ToolCall(String id, String name, String arguments) {}
