package app.lyng.model;

/** 模型输出不符合约定的格式。网关把它当作技术失败（重试一次），绝不当作人物的选择。 */
public class FormatError extends RuntimeException {

    public FormatError(String message) {
        super(message);
    }
}
