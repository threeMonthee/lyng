package app.lyng.web;

import app.lyng.model.CallFailure;
import app.lyng.store.Conflict;
import app.lyng.store.NotFound;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 错误统一写成 { "error": "人能读懂的一句话" }。模型调用失败带上 runId，幕后能看到原因。 */
@RestControllerAdvice
public class Errors {

    private static final Logger log = LoggerFactory.getLogger(Errors.class);

    @ExceptionHandler(NotFound.class)
    ResponseEntity<Map<String, Object>> notFound(NotFound e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(Conflict.class)
    ResponseEntity<Map<String, Object>> conflict(Conflict e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> unreadable(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "请求的格式不对");
    }

    @ExceptionHandler(CallFailure.class)
    ResponseEntity<Map<String, Object>> callFailure(CallFailure e) {
        ResponseEntity<Map<String, Object>> response = body(HttpStatus.BAD_GATEWAY, e.getMessage());
        response.getBody().put("runId", e.runId());
        return response;
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> unexpected(RuntimeException e) {
        log.error("未预期的错误", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务出了点问题：" + e.getMessage());
    }

    static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    /** 流里的错误：和普通接口一样的状态码与说法。 */
    static Map<String, Object> streamError(RuntimeException e) {
        Map<String, Object> body = new HashMap<>();
        HttpStatus status = switch (e) {
            case NotFound n -> HttpStatus.NOT_FOUND;
            case Conflict c -> HttpStatus.CONFLICT;
            case IllegalArgumentException i -> HttpStatus.BAD_REQUEST;
            case CallFailure f -> {
                body.put("runId", f.runId());
                yield HttpStatus.BAD_GATEWAY;
            }
            default -> {
                log.error("流里出现未预期的错误", e);
                yield HttpStatus.INTERNAL_SERVER_ERROR;
            }
        };
        body.put("status", status.value());
        body.put("error", status == HttpStatus.INTERNAL_SERVER_ERROR ? "服务出了点问题：" + e.getMessage() : e.getMessage());
        return body;
    }
}
