package io.mcpgateway.authz;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the framework-free PDP as a bean; the PDP itself stays plain-Java testable. */
@Configuration
public class AuthzConfig {

    @Bean
    PolicyDecisionPoint policyDecisionPoint() {
        return new PolicyDecisionPoint();
    }
}
