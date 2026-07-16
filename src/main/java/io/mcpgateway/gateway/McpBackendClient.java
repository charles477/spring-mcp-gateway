package io.mcpgateway.gateway;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * HTTP client for backend MCP servers speaking JSON-RPC 2.0 over POST (streamable HTTP transport).
 *
 * <p>This is the only place gateway code talks to a backend, so connection concerns
 * (timeouts, and later circuit breaking per FR-GW-2) concentrate here instead of leaking
 * into routing logic.
 */
@Component
public class McpBackendClient {

    private final RestClient restClient;

    public McpBackendClient(RestClient.Builder builder) {
        this.restClient = builder.build();
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
        return restClient.post()
                .uri(baseUrl)
                .header("Content-Type", "application/json")
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }
}
