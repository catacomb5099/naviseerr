package com.catacomb5099.naviseerr.mediaserver;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Outbound clients for the self-hoster's music servers (Navidrome, Jellyfin), one per server, on the
 * {@code CuratorConfig} pattern. A server is "configured" when its URL is set; the clients never call
 * an unconfigured one. Short connect timeout, and the download loop never waits for these calls.
 */
@Configuration
public class MediaServerConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public WebClient navidromeWebClient(@Value("${media-servers.navidrome.url}") String url,
                                        @Value("${media-servers.timeout-ms}") Duration timeout) {
        return http(url, timeout).build();
    }

    /** Jellyfin's own header form; the key never travels in a URL (the {@code ?api_key=} form is legacy). */
    @Bean
    public WebClient jellyfinWebClient(@Value("${media-servers.jellyfin.url}") String url,
                                       @Value("${media-servers.jellyfin.api-key}") String apiKey,
                                       @Value("${media-servers.timeout-ms}") Duration timeout) {
        return http(url, timeout)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "MediaBrowser Token=\"" + apiKey + "\"")
                .build();
    }

    static WebClient.Builder http(String url, Duration timeout) {
        HttpClient client = HttpClient.create()
                .responseTimeout(timeout)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis());
        return WebClient.builder()
                .baseUrl(baseUrl(url))
                .clientConnector(new ReactorClientHttpConnector(client));
    }

    /** {@code http://host:4533/} and {@code http://host:4533} both work; blank (unset) gets a placeholder nothing calls. */
    static String baseUrl(String url) {
        String trimmed = url == null ? "" : url.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "http://unset.invalid" : trimmed;
    }
}
