package com.catacomb5099.naviseerr.services.ytmusic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class YtMusicConfig {
    @Value("${yt-music-service.url}")
    private String url;

    @Bean
    public WebClient ytMusicWebClient() {
        return WebClient.builder()
                .baseUrl(url)
                .build();
    }

}
