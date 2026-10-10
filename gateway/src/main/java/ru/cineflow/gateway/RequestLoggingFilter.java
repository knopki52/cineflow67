package ru.cineflow.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Логирование каждого запроса: метод, путь, сервис-получатель, статус и время ответа + X-Request-Id. */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        ServerHttpRequest request = exchange.getRequest().mutate().header("X-Request-Id", requestId).build();
        exchange.getResponse().getHeaders().add("X-Request-Id", requestId);
        return chain.filter(exchange.mutate().request(request).build()).doFinally(signal -> {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (request.getPath().value().startsWith("/ws")) return;
            log.info("[{}] {} {} → {} | {} | {} мс", requestId, request.getMethod(), request.getPath().value(),
                    route == null ? "-" : route.getId(), exchange.getResponse().getStatusCode(),
                    System.currentTimeMillis() - start);
        });
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
