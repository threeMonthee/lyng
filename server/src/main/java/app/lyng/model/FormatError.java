package app.lyng.model;

/** 模型的输出不合规（空、标签之外有字、写了别人的言行……）。按技术失败处理，不是人物的选择。 */
public class FormatError extends RuntimeException {

    public FormatError(String message) {
        super(message);
    }
}
