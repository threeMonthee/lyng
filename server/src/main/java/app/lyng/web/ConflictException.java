package app.lyng.web;

/** 当前状态不允许这个操作，例如这一场正在进行一轮。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
