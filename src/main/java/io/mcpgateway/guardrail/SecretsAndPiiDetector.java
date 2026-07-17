package io.mcpgateway.guardrail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Regex-based secret/PII scanner — the one real detector shipping in v1 (FR-GUARD-2). It runs on
 * tool responses before they re-enter the agent's context: a leaked credential in a tool result
 * becomes part of the LLM's conversation (and possibly its next outbound request), so redaction
 * must happen here, not at the client.
 */
@Component
public class SecretsAndPiiDetector implements Guardrail {

    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put("aws-access-key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"));
        PATTERNS.put("private-key-block", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));
        PATTERNS.put("bearer-token", Pattern.compile("\\bBearer\\s+[A-Za-z0-9._~+/-]{20,}=*"));
        PATTERNS.put("email-address", Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"));
        PATTERNS.put("card-number", Pattern.compile("\\b(?:\\d[ -]?){13,16}\\b"));
    }

    @Override
    public Result apply(String content) {
        String redacted = content;
        List<String> findings = new ArrayList<>();
        for (Map.Entry<String, Pattern> entry : PATTERNS.entrySet()) {
            Matcher matcher = entry.getValue().matcher(redacted);
            if (matcher.find()) {
                findings.add(entry.getKey());
                redacted = matcher.replaceAll("[REDACTED:" + entry.getKey() + "]");
            }
        }
        return new Result(redacted, findings);
    }
}
