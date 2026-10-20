package com.catacomb5099.naviseerr.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PkceTest {

    /** RFC 7636 Appendix B: the verifier and its S256 challenge, byte for byte. */
    @Test
    void theChallengeIsTheRfcVector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void randomValues_are43UrlSafeCharacters_andNeverRepeat() {
        String a = Pkce.random();
        String b = Pkce.random();
        assertEquals(43, a.length(), "32 bytes, base64url, no padding");
        assertTrue(a.matches("[A-Za-z0-9_-]+"), a);
        assertNotEquals(a, b);
    }
}
