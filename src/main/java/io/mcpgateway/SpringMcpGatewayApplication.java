package io.mcpgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * spring-mcp-gateway — Spring-native security and governance gateway for the
 * Model Context Protocol. See {@code docs/REQUIREMENTS.md} for the module map.
 */
@SpringBootApplication
public class SpringMcpGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringMcpGatewayApplication.class, args);
    }
}
