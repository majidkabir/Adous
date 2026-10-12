package app.majid.adous.synchronizer.security.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyAuthenticationFilterTest {

    private static final String KEY = "a-sufficiently-long-test-key";

    private final ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(KEY);

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void shouldFailStartupWhenApiKeyIsBlank(String key) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ApiKeyAuthenticationFilter(key));
        assertTrue(e.getMessage().contains("API_KEY"));
    }

    @Test
    void shouldFailStartupWhenApiKeyIsMissing() {
        assertThrows(IllegalStateException.class, () -> new ApiKeyAuthenticationFilter(null));
    }

    @Test
    void shouldAcceptRequestWithValidKey() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run("/api/synchronizer/repo-to-db", KEY, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "request should reach the controller");
    }

    @Test
    void shouldRejectRequestWithWrongKey() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run("/api/synchronizer/repo-to-db", KEY + "x", chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void shouldRejectRequestWithKeyThatIsAPrefixOfTheRealOne() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run("/api/synchronizer/repo-to-db", KEY.substring(0, 5), chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void shouldRejectRequestWithoutKey() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run("/api/synchronizer/repo-to-db", null, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Missing API key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/jvm.memory.used",
            "/actuator/env", "/actuator/healthz"})
    void shouldRequireKeyForNonPublicActuatorEndpoints(String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run(path, null, chain);

        assertEquals(401, response.getStatus(), path);
        assertNull(chain.getRequest(), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/info",
            "/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs/swagger-config"})
    void shouldAllowPublicEndpointsWithoutKey(String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run(path, null, chain);

        assertEquals(200, response.getStatus(), path);
        assertNotNull(chain.getRequest(), path);
    }

    private MockHttpServletResponse run(String path, String apiKey, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (apiKey != null) {
            request.addHeader("X-API-Key", apiKey);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
