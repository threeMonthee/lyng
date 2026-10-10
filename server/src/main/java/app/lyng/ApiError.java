package app.lyng;

/** 用户能看懂的请求错误：找不到、冲突、参数不对。由 web 层转成对应的 HTTP 状态与 {"error": …}。 */
public class ApiError extends RuntimeException {

    private final int status;

    public ApiError(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiError notFound(String what) {
        return new ApiError(404, "找不到" + what);
    }

    public static ApiError conflict(String message) {
        return new ApiError(409, message);
    }

    public static ApiError badRequest(String message) {
        return new ApiError(400, message);
    }

    public int status() {
        return status;
    }
}
