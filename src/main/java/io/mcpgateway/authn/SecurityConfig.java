package io.mcpgateway.authn;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Edge security policy: stateless bearer-token authentication with deny-by-default routing.
 *
 * <p>Only the liveness/readiness probes are anonymous. Every other endpoint — MCP, Admin, and
 * anything added later — requires a validated token, so a new controller can never be exposed
 * unauthenticated by omission (FR-AUTHN-4).
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthnProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthnProperties properties) throws Exception {
        http
                // Bearer-token API: no cookies, no sessions, hence no CSRF surface.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                new GatewayJwtAuthenticationConverter(properties))));
        return http.build();
    }
}
