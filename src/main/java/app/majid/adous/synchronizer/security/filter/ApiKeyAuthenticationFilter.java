package app.majid.adous.synchronizer.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * API Key authentication filter.
 * Validates requests using an API key provided in the X-API-Key header.
 * Active when authentication type is set to 'APIKEY'.
 * <p>
 * Only the endpoints listed in {@link #PUBLIC_PATHS} are reachable without a key;
 * metrics and Prometheus endpoints require the key like the API does.
 */
@Component
@ConditionalOnProperty(name = "spring.application.authentication.type", havingValue = "APIKEY")
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter implements AuthenticationFilter {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final int RECOMMENDED_MIN_KEY_LENGTH = 16;

    /** Paths reachable without an API key (path itself or anything below it). */
    public static final List<String> PUBLIC_PATHS = List.of(
            "/actuator/health",
            "/actuator/info",
            "/swagger-ui",
            "/swagger-ui.html",
            "/v3/api-docs");

    private final byte[] validApiKey;

    public ApiKeyAuthenticationFilter(
            @Value("${spring.application.authentication.api-key:}") String validApiKey) {
        if (validApiKey == null || validApiKey.isBlank()) {
            throw new IllegalStateException(
                    "API_KEY must be set when AUTH_TYPE=APIKEY (spring.application.authentication.api-key)");
        }
        if (validApiKey.length() < RECOMMENDED_MIN_KEY_LENGTH) {
            logger.warn("The configured API key is shorter than {} characters; use a long random value",
                    RECOMMENDED_MIN_KEY_LENGTH);
        }
        this.validApiKey = validApiKey.getBytes(StandardCharsets.UTF_8);
    }

    public static boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return isPublicPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey == null || apiKey.trim().isEmpty()) {
            logger.warn("Missing API key in request from {}", request.getRemoteAddr());
            reject(response, "Missing API key. Please provide X-API-Key header.");
            return;
        }

        // Constant-time comparison so response timing does not leak how much of the key matched
        if (!MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8), validApiKey)) {
            logger.warn("Invalid API key attempt from {}", request.getRemoteAddr());
            reject(response, "Invalid API key.");
            return;
        }

        logger.debug("Valid API key authenticated for request to {}", request.getRequestURI());

        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken("api-user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authenticationToken.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authenticationToken);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}
