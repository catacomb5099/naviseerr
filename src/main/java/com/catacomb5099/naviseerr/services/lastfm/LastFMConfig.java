package com.catacomb5099.naviseerr.services.lastfm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * @deprecated Search now runs through {@link com.catacomb5099.naviseerr.services.ytmusic.YtMusicService}.
 * Last.fm is retained on disk, unused, pending removal.
 */
@Deprecated(since = "2026-08-10", forRemoval = true)
@Configuration
public class LastFMConfig {
    @Value("${last-fm-service.url}")
    private String url;

    @Bean
    public WebClient lastFmWebClient() {
        return WebClient.builder()
                .baseUrl(url)
                .build();
    }

}
