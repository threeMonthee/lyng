package app.lyng.store;

/** 现在不能做这件事（例如这一场还有步骤没跑完）。接口层转成 409。 */
public class Conflict extends RuntimeException {

    public Conflict(String message) {
        super(message);
    }
}
