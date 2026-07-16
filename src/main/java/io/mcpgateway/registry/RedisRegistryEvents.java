package io.mcpgateway.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Redis pub/sub adapter for {@link RegistryEvents} (FR-REG-3): mutations publish the server name
 * on one channel; every node — including the publisher — invalidates its local cache on receipt.
 * The publisher also invalidates synchronously so its own next request never races the broadcast.
 */
@Component
public class RedisRegistryEvents implements RegistryEvents {

    static final String CHANNEL = "mcpgw:registry:invalidate";

    private static final Logger log = LoggerFactory.getLogger(RedisRegistryEvents.class);

    private final StringRedisTemplate redis;
    private final RoutingCache cache;

    public RedisRegistryEvents(StringRedisTemplate redis, RoutingCache cache) {
        this.redis = redis;
        this.cache = cache;
    }

    @Override
    public void serverChanged(String serverName) {
        cache.invalidateServer(serverName);
        try {
            redis.convertAndSend(CHANNEL, serverName);
        } catch (RuntimeException e) {
            // Fail open on freshness, closed on nothing: this node is already invalidated; peers
            // converge on their next miss. Broken pub/sub must not turn mutations into errors.
            log.error("could not broadcast invalidation for '{}': {}", serverName, e.getMessage());
        }
    }

    /** Subscribes every node to the invalidation channel. */
    @Configuration
    static class Listener {

        @Bean
        RedisMessageListenerContainer registryInvalidationListener(
                RedisConnectionFactory connectionFactory, RoutingCache cache) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(
                    (message, pattern) -> cache.invalidateServer(new String(message.getBody())),
                    new ChannelTopic(CHANNEL));
            return container;
        }
    }
}
