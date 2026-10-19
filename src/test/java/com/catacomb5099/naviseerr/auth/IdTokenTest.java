package com.catacomb5099.naviseerr.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IdTokenTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String ISSUER = "https://auth.example.com/application/o/naviseerr/";
    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    /** header.payload.signature with any header and signature: only the payload is read. */
    static String jwt(String payloadJson) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    static String good(String aud) {
        return jwt("{\"iss\":\"" + ISSUER + "\",\"sub\":\"subject-42\",\"aud\":" + aud + ",\"exp\":" + NOW.plusSeconds(300).getEpochSecond()
                + ",\"iat\":" + NOW.getEpochSecond() + ",\"nonce\":\"n1\",\"email\":\"never@read\"}");
    }

    @Test
    void aGoodToken_isReadAndPasses_audAsStringOrArray() {
        IdToken token = IdToken.parse(good("\"client-1\""), JSON).checked(ISSUER, "client-1", "n1", NOW);
        assertEquals("subject-42", token.subject());
        assertEquals(List.of("client-1"), token.audiences());
        assertEquals(NOW.plusSeconds(300), token.expiresAt());

        IdToken array = IdToken.parse(good("[\"other\",\"client-1\"]"), JSON).checked(ISSUER, "client-1", "n1", NOW);
        assertEquals(List.of("other", "client-1"), array.audiences());
    }

    @Test
    void eachMismatch_isRefused_withTheClaimNamed() {
        IdToken token = IdToken.parse(good("\"client-1\""), JSON);
        assertRefused(() -> token.checked("https://other.example/application/o/x/", "client-1", "n1", NOW), "the issuer");
        assertRefused(() -> token.checked(ISSUER, "client-2", "n1", NOW), "the audience");
        assertRefused(() -> token.checked(ISSUER, "client-1", "n2", NOW), "the nonce");
        assertRefused(() -> token.checked(ISSUER, "client-1", "n1", NOW.plusSeconds(301)), "the token has expired");
        assertRefused(() -> IdToken.parse(jwt("{\"iss\":\"" + ISSUER + "\",\"aud\":\"client-1\",\"nonce\":\"n1\",\"exp\":9999999999}"), JSON)
                .checked(ISSUER, "client-1", "n1", NOW), "no subject");
    }

    @Test
    void somethingThatIsNotAToken_isRefused_notAnException() {
        assertRefused(() -> IdToken.parse("not-a-token", JSON), "it did not answer with a token");
        assertRefused(() -> IdToken.parse("a.!!!.c", JSON), "its token could not be read");
        assertRefused(() -> IdToken.parse("a." + Base64.getUrlEncoder().withoutPadding().encodeToString("<html>".getBytes()) + ".c", JSON),
                "its token could not be read");
        assertRefused(() -> IdToken.parse(null, JSON), "it did not answer with a token");
    }

    private static void assertRefused(Runnable check, String what) {
        LoginProblem problem = assertThrows(LoginProblem.class, check::run);
        assertEquals("Authentik's answer did not match this sign-in (" + what + "). Go back and try again.", problem.getMessage());
    }
}
