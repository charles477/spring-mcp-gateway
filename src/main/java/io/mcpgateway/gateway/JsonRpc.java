package io.mcpgateway.gateway;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * Minimal JSON-RPC 2.0 model for the MCP wire protocol (FR-GW-1). Only what the gateway needs:
 * requests in, result-or-error out. IDs are passed through opaquely as the spec requires.
 */
public final class JsonRpc {

    /** JSON-RPC error codes used by the gateway. Spec-defined below -32600; -32000.. are ours. */
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int DENIED = -32000;
    public static final int TOOL_QUARANTINED = -32001;
    public static final int UPSTREAM_ERROR = -32002;
    public static final int RATE_LIMITED = -32003;
    public static final int APPROVAL_REQUIRED = -32004;

    private JsonRpc() {
    }

    /** An incoming JSON-RPC request; {@code params} stays raw until the method is known. */
    public record Request(String jsonrpc, JsonNode id, String method, JsonNode params) {
    }

    /** A JSON-RPC response carrying either {@code result} or {@code error}, never both. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(String jsonrpc, JsonNode id, JsonNode result, Error error) {

        public static Response result(JsonNode id, JsonNode result) {
            return new Response("2.0", id, result, null);
        }

        public static Response error(JsonNode id, int code, String message) {
            return new Response("2.0", id, null, new Error(code, message));
        }
    }

    /** JSON-RPC error object. */
    public record Error(int code, String message) {
    }
}
