package com.catacomb5099.naviseerr.auth;

/**
 * Something about the Authentik connection the person can fix, as ONE plain sentence for the Settings
 * page ("Could not reach auth.example.com: connection refused. Is Authentik running..."). Answered as
 * 400 {@code {message}}. No stack trace: these are expected, not bugs.
 */
public class LoginProblem extends RuntimeException {
    public LoginProblem(String message) {
        super(message, null, false, false);
    }
}
