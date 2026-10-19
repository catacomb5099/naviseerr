package com.catacomb5099.naviseerr.mediaserver;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaServerExceptionTest {

    @Test
    void anUnreachableHost_isOneSentence_withTheAddressOnce() {
        var refused = new WebClientRequestException(new ConnectException("Connection refused: /127.0.0.1:1"),
                HttpMethod.GET, URI.create("http://127.0.0.1:1/api/v0/server"), new HttpHeaders());
        assertEquals("Could not reach 127.0.0.1:1: Connection refused. Is slskd running, and can this server reach it?",
                MediaServerException.transport("slskd", "http://127.0.0.1:1/api/v0/", refused).getMessage());

        var unknown = new WebClientRequestException(new UnknownHostException("Failed to resolve 'nas' [A(1)]"),
                HttpMethod.GET, URI.create("http://nas:4533/rest/startScan"), new HttpHeaders());
        assertEquals("Could not reach nas:4533: the name could not be found. Is Navidrome running, and can this server reach it?",
                MediaServerException.transport("Navidrome", "http://nas:4533", unknown).getMessage());
    }
}
