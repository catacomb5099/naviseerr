package com.catacomb5099.naviseerr.auth;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The claims of the ID token Authentik's token endpoint answers with, and the checks OpenID Connect
 * Core 3.1.3.7 asks for. The signature is NOT checked, on purpose: the token arrives straight from the
 * token endpoint over the connection we opened to it (step 6 of that section allows TLS to stand in for
 * the signature then), which also means an Authentik provider signing with HS256 and one with RS256
 * work alike. A LAN install reaching Authentik over plain http trusts its own network for this.
 */
record IdToken(String subject, String issuer, List<String> audiences, String nonce, Instant expiresAt) {

    /** The middle part of {@code header.payload.signature}, decoded and read; nothing is verified yet. */
    static IdToken parse(String jwt, ObjectMapper objectMapper) {
        String[] parts = jwt == null ? new String[0] : jwt.split("\\.");
        if (parts.length < 2) throw mismatch("it did not answer with a token");
        JsonNode claims;
        try {
            claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (IllegalArgumentException | JacksonException unreadable) {
            throw mismatch("its token could not be read");
        }
        List<String> audiences = new ArrayList<>();
        JsonNode aud = claims.path("aud");
        if (aud.isArray()) aud.forEach(a -> audiences.add(a.asString()));
        else if (aud.isString()) audiences.add(aud.asString());
        JsonNode exp = claims.path("exp");
        return new IdToken(claims.path("sub").asString(null), claims.path("iss").asString(null), audiences,
                claims.path("nonce").asString(null), exp.isNumber() ? Instant.ofEpochSecond(exp.asLong()) : null);
    }

    /** This token, once it is for us (the issuer we asked, our client id, this sign-in's nonce) and still valid. */
    IdToken checked(String expectedIssuer, String clientId, String expectedNonce, Instant now) {
        if (subject == null || subject.isBlank()) throw mismatch("no subject");
        if (!expectedIssuer.equals(issuer)) throw mismatch("the issuer");
        if (!audiences.contains(clientId)) throw mismatch("the audience");
        if (expiresAt == null || !expiresAt.isAfter(now)) throw mismatch("the token has expired");
        if (expectedNonce == null || !expectedNonce.equals(nonce)) throw mismatch("the nonce");
        return this;
    }

    private static LoginProblem mismatch(String what) {
        return new LoginProblem("Authentik's answer did not match this sign-in (" + what + "). Go back and try again.");
    }
}
