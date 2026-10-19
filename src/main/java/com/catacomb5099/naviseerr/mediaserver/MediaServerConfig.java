package com.catacomb5099.naviseerr.mediaserver;

import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * The HTTP recipe for the self-hoster's music servers (Navidrome, Jellyfin): short connect timeout,
 * the per-request timeout from {@code media-servers.timeout-ms}. Since 19-10-2026 the clients build
 * their {@code WebClient} from this whenever their address changes (the install file at boot, then
 * whatever the Settings page saves), so there is no bean fixed at boot any more.
 */
public final class MediaServerConfig {

    /** The base URL of a server that is not configured: nothing ever calls it. */
    static final String UNSET = "http://unset.invalid";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private MediaServerConfig() {}

    static WebClient.Builder http(String url, Duration timeout) {
        HttpClient client = HttpClient.create()
                .responseTimeout(timeout)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis());
        return WebClient.builder()
                .baseUrl(baseUrl(url))
                .clientConnector(new ReactorClientHttpConnector(client));
    }

    /** {@code http://host:4533/} and {@code http://host:4533} both work; blank (unset) gets a placeholder nothing calls. */
    public static String baseUrl(String url) {
        String trimmed = url == null ? "" : url.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? UNSET : trimmed;
    }
}
