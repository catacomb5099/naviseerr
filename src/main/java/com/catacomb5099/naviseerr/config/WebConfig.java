package com.catacomb5099.naviseerr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.WebFluxConfigurer;

import java.util.List;

@Configuration
@EnableWebFlux
public class WebConfig implements WebFluxConfigurer {

    /**
     * CORS as a filter, first in line, rather than the handler-level mapping it used to be: the login's
     * {@code AuthFilter} answers 401 before any handler runs, and without this a cross-origin browser
     * (the dev setup, Vite :5173 -> :8080) could not read that answer. Origin patterns plus credentials,
     * because the session cookie must travel: Spring refuses {@code allowedOrigins("*")} with credentials.
     * The Docker install is same-origin through the web app's proxy and never preflights.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
