package io.mcpgateway.audit;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Enables the async executor that keeps audit writes off the request path (FR-AUDIT-2). */
@Configuration
@EnableAsync
public class AuditConfig {
}
