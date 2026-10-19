package com.catacomb5099.naviseerr.settings;

/**
 * Something about a setting the person can fix, as ONE plain sentence for the Settings page or the
 * first-run setup ("Enter the address of Navidrome.", "Navidrome refused the username or password.").
 * Answered as 400 {@code {message}} by {@link SettingsProblemAdvice}; nothing is saved when one is
 * raised. No stack trace: these are expected, not bugs.
 */
public class SettingsProblem extends RuntimeException {
    public SettingsProblem(String message) {
        super(message, null, false, false);
    }
}
