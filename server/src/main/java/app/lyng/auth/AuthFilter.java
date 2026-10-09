package app.lyng.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** /api 下除登录与健康检查外都需要会话。用户身份只来自会话，不来自请求体。 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String COOKIE = "lyng_session";
    public static final String USER = "lyng.userId";
    public static final String TOKEN = "lyng.token";

    private final AuthService auth;

    public AuthFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/login") || path.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = tokenOf(request);
        Optional<UUID> user = auth.userOf(token);
        if (user.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write("{\"error\":\"请先登录\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        request.setAttribute(USER, user.get());
        request.setAttribute(TOKEN, token);
        chain.doFilter(request, response);
    }

    static String tokenOf(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(COOKIE)) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
