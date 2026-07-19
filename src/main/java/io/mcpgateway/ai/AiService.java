package io.mcpgateway.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Server-side bridge to the Claude API for the console's AI features. The API key lives only in
 * the gateway's environment ({@code ANTHROPIC_API_KEY}) — it is never exposed to the browser.
 * When no key is configured, the service reports itself unavailable and callers degrade
 * gracefully instead of failing.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final AnthropicClient client;
    private final String model;

    public AiService(@Value("${ANTHROPIC_API_KEY:}") String apiKey,
                     @Value("${gateway.ai.model:claude-opus-4-8}") String model) {
        this.model = model;
        if (apiKey == null || apiKey.isBlank()) {
            this.client = null;
            log.info("AI features disabled: ANTHROPIC_API_KEY not set");
        } else {
            this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            log.info("AI features enabled with model {}", model);
        }
    }

    public boolean isConfigured() {
        return client != null;
    }

    /**
     * One system+user completion returning the response text. Callers must check
     * {@link #isConfigured()} first; this throws if the service is unavailable.
     */
    public String complete(String system, String user, int maxTokens) {
        if (client == null) {
            throw new IllegalStateException("AI is not configured");
        }
        Message message = client.messages().create(MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(system)
                .addUserMessage(user)
                .build());
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }
}
