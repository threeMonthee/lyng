package app.lyng.store;

/** 找不到（或不属于当前用户）。接口层转成 404。 */
public class NotFound extends RuntimeException {

    public NotFound(String message) {
        super(message);
    }
}
