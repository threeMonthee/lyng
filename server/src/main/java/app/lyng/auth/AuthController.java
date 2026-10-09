package app.lyng.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class AuthController {

    record Login(String name, String password) {}

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, String>> login(@RequestBody Login body, HttpServletResponse response) {
        Optional<String> token = auth.login(body.name(), body.password());
        if (token.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "名字或口令不对"));
        }
        response.addCookie(cookie(token.get(), (int) AuthService.SESSION_TTL.toSeconds()));
        return ResponseEntity.ok(Map.of("name", body.name()));
    }

    @PostMapping("/logout")
    Map<String, Boolean> logout(@RequestAttribute(AuthFilter.TOKEN) String token, HttpServletResponse response) {
        auth.logout(token);
        response.addCookie(cookie("", 0));
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    Map<String, String> me(@RequestAttribute(AuthFilter.USER) UUID userId) {
        return Map.of("name", auth.nameOf(userId));
    }

    @GetMapping("/health")
    Map<String, Boolean> health() {
        return Map.of("ok", true);
    }

    private static Cookie cookie(String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(AuthFilter.COOKIE, value);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }
}
