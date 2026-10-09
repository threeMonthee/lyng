package app.lyng.model;

import java.util.List;

public record ChatRequest(ModelChoice choice, List<ChatMessage> messages, int maxTokens, CallPurpose purpose) {}
