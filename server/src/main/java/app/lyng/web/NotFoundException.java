package app.lyng.web;

/** 找不到，或者属于别的用户——对调用方一视同仁，不泄露别人的数据是否存在。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what) {
        super(what + "不存在");
    }
}
