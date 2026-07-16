package io.mcpgateway.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * HTTP client for backend MCP servers speaking JSON-RPC 2.0 over POST (streamable HTTP transport).
 *
 * <p>This is the only place gateway code talks to a backend, so connection concerns concentrate
 * here: request timeouts, and one circuit breaker per backend so a failing server sheds load
 * fast without dragging down calls routed to healthy ones (FR-GW-2). Breaker-open failures
 * surface as the same RuntimeException contract callers already handle.
 */
@Component
public class McpBackendClient {

    private final RestClient restClient;
    private final CircuitBreakerRegistry breakers;

    public McpBackendClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = builder.requestFactory(requestFactory).build();
        this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
    }

    /**
     * Fetches the backend's live tool list — the ground truth that manifest verification
     * compares against the pinned hash (FR-REG-5).
     *
     * @return the {@code result.tools} array, or empty if the backend answered with an error
     */
    public Optional<JsonNode> listTools(String baseUrl) {
        JsonNode response = exchange(baseUrl, "tools/list", null);
        JsonNode tools = response.path("result").path("tools");
        return tools.isArray() ? Optional.of(tools) : Optional.empty();
    }

    /**
     * Invokes a tool on the backend and returns the raw JSON-RPC response, letting the caller
     * pass through {@code result} or translate {@code error}.
     */
    public JsonNode callTool(String baseUrl, String toolName, JsonNode arguments) {
        return exchange(baseUrl, "tools/call", Map.of(
                "name", toolName,
                "arguments", arguments == null ? Map.of() : arguments));
    }

    private JsonNode exchange(String baseUrl, String method, Object params) {
        Map<String, Object> request = params == null
                ? Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", method)
                : Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", method,
                        "params", params);
        CircuitBreaker breaker = breakers.circuitBreaker(baseUrl);
        return breaker.executeSupplier(() -> restClient.post()
                .uri(baseUrl)
                .header("Content-Type", "application/json")
                .body(request)
                .retrieve()
                .body(JsonNode.class));
    }
}
