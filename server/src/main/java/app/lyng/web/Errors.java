package app.lyng.web;

import app.lyng.ApiError;
import app.lyng.model.CallFailure;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 请求错误一律返回 {"error": "给人看的原因"}。 */
@RestControllerAdvice
class Errors {

    @ExceptionHandler(ApiError.class)
    ResponseEntity<Map<String, String>> api(ApiError e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    /** 创作这类不走 SSE 的调用，技术失败也要让页面看得见、可以重试。 */
    @ExceptionHandler(CallFailure.class)
    ResponseEntity<Map<String, String>> model(CallFailure e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }
}
