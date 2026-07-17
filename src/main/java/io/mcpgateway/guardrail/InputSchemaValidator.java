package io.mcpgateway.guardrail;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validates tool-call arguments against the tool's registered input schema before anything is
 * proxied (FR-GUARD-1): malformed or smuggled arguments never reach a backend. Uses the schema
 * pinned in the registry — the backend's live schema is deliberately not consulted here, since
 * the pinned manifest is the reviewed contract.
 */
@Component
public class InputSchemaValidator {

    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /** @return validation error messages; empty means the arguments conform */
    public List<String> validate(String schemaJson, String argumentsJson) {
        JsonSchema schema = factory.getSchema(schemaJson);
        return schema.validate(argumentsJson, com.networknt.schema.InputFormat.JSON).stream()
                .map(ValidationMessage::getMessage)
                .toList();
    }
}
