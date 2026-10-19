package com.catacomb5099.naviseerr.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.WebFilter;

import java.net.URI;
import java.util.List;

@Configuration
@EnableWebFlux
public class WebConfig implements WebFluxConfigurer {

    /**
     * CORS as a filter, first in line, rather than the handler-level mapping it used to be: the login's
     * {@code AuthFilter} answers 401 before any handler runs, and without this a cross-origin browser
     * (the dev setup, Vite :5173 -> :8080) could not read that answer. Only the listed origins
     * ({@code auth.allowed-origins}) may do so with the session cookie; any other origin gets no CORS
     * headers, so a page on another site cannot read an answer or send the cookie along. The Docker image
     * sets the list empty (Dockerfile): an install allows no other address at all, like Navidrome and
     * Jellyfin; the jar's own default is the developers' Vite origin.
     *
     * <p>The web app's own calls carry an Origin whose host is the request's own Host (the scheme may
     * differ: HTTPS at the proxy, plain HTTP here, which is why Spring's own same-origin test is not
     * enough). Those are not cross-origin and never need a CORS answer, so they skip the filter; the
     * Docker install, same-origin through the web app's proxy, is never affected by the list.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public WebFilter corsWebFilter(@Value("${auth.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        CorsWebFilter cors = new CorsWebFilter(source);
        return (exchange, chain) -> isOwnOrigin(exchange.getRequest()) ? chain.filter(exchange) : cors.filter(exchange, chain);
    }

    /**
     * The Origin header names this very host (and port), whatever the scheme: the web app calling its own
     * server. The raw Host header, not the URI's authority: Netty fills a missing port in from the
     * listening socket, and "music.example" behind an HTTPS proxy must still equal "https://music.example".
     */
    static boolean isOwnOrigin(ServerHttpRequest request) {
        String origin = request.getHeaders().getOrigin();
        String host = request.getHeaders().getFirst("Host");
        if (host == null) host = request.getURI().getAuthority();   // HTTP/2 carries :authority instead
        if (origin == null || host == null) return false;
        try {
            return host.equalsIgnoreCase(URI.create(origin).getAuthority());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
