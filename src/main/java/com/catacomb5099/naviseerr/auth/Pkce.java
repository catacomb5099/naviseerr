package com.catacomb5099.naviseerr.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The random values of a sign-in (RFC 7636, "Proof Key for Code Exchange"): a verifier the browser
 * never sees, its SHA-256 challenge sent to Authentik with the first request, the verifier itself sent
 * with the second, so a stolen authorization code is useless on its own. The same generator makes the
 * state, the nonce and the session ids: 32 random bytes as base64url, 43 characters, no padding.
 */
final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Pkce() {}

    static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** {@code code_challenge} for {@code code_challenge_method=S256}. */
    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
