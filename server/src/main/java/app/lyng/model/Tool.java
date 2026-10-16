package app.lyng.model;

import java.util.Map;

/** 提供给模型的工具：名字、说明、参数的 JSON Schema。 */
public record Tool(String name, String description, Map<String, Object> parameters) {}
