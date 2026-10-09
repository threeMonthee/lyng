package app.lyng.web;

import app.lyng.model.CallFailure;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> noResource(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "没有这个地址");
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String, String>> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({BadRequestException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 创作等同步调用的技术失败：按技术失败呈现，客户端可以重试。 */
    @ExceptionHandler(CallFailure.class)
    ResponseEntity<Map<String, String>> callFailure(CallFailure e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage(), "kind", e.kind().name().toLowerCase()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("未预期的错误", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务出错了");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
