package app.lyng.model;

import java.util.UUID;

/** 一次运行最终失败（已按规则重试）。runId 指向幕后能看到的那次运行。 */
public class CallFailure extends RuntimeException {

    private final UUID runId;

    public CallFailure(String message, UUID runId) {
        super(message);
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}
