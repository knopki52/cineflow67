package ru.cineflow.booking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import ru.cineflow.booking.live.LiveEvents;

/**
 * Если запущено несколько экземпляров booking-service, у каждого свои WebSocket-клиенты.
 * Все события идут через Redis Pub/Sub, и каждый экземпляр раздаёт их своим подписчикам.
 */
@Configuration
public class RedisRelayConfig {

    @Bean
    public RedisMessageListenerContainer liveContainer(RedisConnectionFactory factory, LiveEvents live) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(live, new ChannelTopic(LiveEvents.CHANNEL));
        return container;
    }
}
