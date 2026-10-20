package com.catacomb5099.naviseerr.services.slskd;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
public class SlskdConfig {
    private final static String API_KEY_HEADER = "X-API-Key";

    @Value("${slskd-service.url}")
    private String url;
    @Value("${slskd-service.api_key}")
    private String apiKey;

    private Duration poolMaxIdleTime = Duration.ofSeconds(90);
    private Duration poolMaxLifeTime = Duration.ofMinutes(5);
    private Duration poolEvictInterval = Duration.ofSeconds(30);
    private Duration responseTimeout = Duration.ofSeconds(15);
    private Duration connectTimeout = Duration.ofSeconds(5);
    /** One pool for every slskd client this process builds (the install file's, your own after a save, each Save and test). */
    private final ConnectionProvider pool = ConnectionProvider.builder("slskd")
            .maxIdleTime(poolMaxIdleTime)
            .maxLifeTime(poolMaxLifeTime)
            .evictInBackground(poolEvictInterval)
            .build();

    // getSearchWithResponses fetches a search including every peer response, which for a popular
    // track on a live instance measured over 1 MiB decoded -- 4x the framework's 256 KiB default
    // in-memory buffer limit. WebClient.builder() here (rather than the Boot-injected
    // WebClient.Builder) never picks up Spring's codec configuration, so the limit must be set
    // explicitly. 16 MiB leaves headroom for a broader query while bounding the worst case at
    // download-task.batch-size in-flight refetches per pass.
    @Bean
    public WebClient slskdWebClient() {
        return build(url, apiKey);
    }

    /** The install file's slskd address (SLSKD_URL), the fallback when none was saved in the app. */
    public String url() {
        return url;
    }

    /**
     * A client for any slskd (the install file's at boot; your own, saved in Settings, from then on), same
     * pool, timeouts and buffer limit; a replaced client leaves nothing behind.
     */
    public WebClient build(String url, String apiKey) {
        HttpClient httpClient = HttpClient.create(pool)
                .responseTimeout(responseTimeout)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis());

        return WebClient.builder()
                .baseUrl(url)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
