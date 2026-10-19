package com.catacomb5099.naviseerr.services.ytmusic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
public class YtMusicConfig {
    @Value("${yt-music-service.url}")
    private String url;

    /**
     * Nothing idle is reused: uvicorn closes an idle keep-alive connection after 5 s (gunicorn's
     * UvicornWorker after 2 s), and a pooled connection taken just as the adapter closes it is reset.
     * The readiness probe behind GET /status has no retry, so one such reset read as a one-poll
     * outage on a healthy adapter (19-10-2026); execute() hid the same race behind its retries. A
     * fresh connection per idle second costs a local handshake, nothing more.
     */
    private static final Duration POOL_MAX_IDLE = Duration.ofSeconds(1);

    @Bean
    public WebClient ytMusicWebClient() {
        ConnectionProvider pool = ConnectionProvider.builder("ytmusic").maxIdleTime(POOL_MAX_IDLE).build();
        return WebClient.builder()
                .baseUrl(url)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create(pool)))
                .build();
    }

}
