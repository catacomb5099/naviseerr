package com.catacomb5099.naviseerr.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The one-time code that lets the first account be created (19-10-2026). An app reachable from the
 * internet must not let the first visitor become its owner: so, while no account exists, the server
 * makes one random code at start, prints it in its log (Jupyter and Grafana print a first-run token
 * or password the same way) and, when the install's data folder is known, saves it there owner-only.
 * {@code POST /auth/setup} needs it; the right code creates the only account and the code is void,
 * never printed again. A restart before that makes a new one.
 */
@Component
public class SetupCode {

    private static final Logger log = LoggerFactory.getLogger(SetupCode.class);
    /** Letters and digits that are not mistaken for one another when read off a screen (no 0/o, 1/l/i). */
    private static final String ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthRepository repository;
    private final boolean required;
    /** Where the code is also written, or null for the log only. */
    private final Path file;
    private volatile String code;

    public SetupCode(AuthRepository repository,
                     @Value("${auth.required:true}") boolean required,
                     @Value("${auth.setup-code-file:}") String file,
                     @Value("${install.data-dir:}") String dataDir) {
        this.repository = repository;
        this.required = required;
        this.file = !file.isBlank() ? Path.of(file)
                : !dataDir.isBlank() ? Path.of(dataDir, "config", "setup-code.txt")
                : null;
    }

    /** At start: a fresh code while there is no account, nothing (and no stale file) once there is one. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!required) return;
        if (Boolean.TRUE.equals(repository.anyUser().block())) {
            consumed();
            return;
        }
        code = generate();
        log.info("""

                ==========================================================================
                  Naviseerr has no account yet. Open the web app and enter this setup
                  code to create your account; nobody else can create one.

                      Setup code: {}
                {}
                ==========================================================================
                """, code, write());
    }

    /** True for the live code, however it was typed: case and dashes do not count. */
    public boolean matches(String typed) {
        String live = code;
        if (live == null || typed == null) return false;
        return MessageDigest.isEqual(squeeze(live).getBytes(StandardCharsets.UTF_8), squeeze(typed).getBytes(StandardCharsets.UTF_8));
    }

    /** The account exists now: the code is void and the file goes. */
    public void consumed() {
        code = null;
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete the spent setup code file {}: {}", file, e.getMessage());
        }
    }

    /** For tests: the live code, or null. */
    String current() {
        return code;
    }

    /** xxxx-xxxx-xxxx: 31^12 codes, and the login throttle allows a handful of guesses a minute. */
    private static String generate() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String squeeze(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Writes the code owner-only; the banner line saying where, or why not. Never fatal: the log has it. */
    private String write() {
        if (file == null) return "";
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());   // a bare file name has no parent until made absolute
            Files.writeString(file, code + System.lineSeparator());
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException windows) {
                // No POSIX permissions here; the folder's own access rules apply.
            }
            return "\n  Also saved to " + file + " (deleted once the account exists).\n";
        } catch (IOException e) {
            return "\n  (Could not save it to " + file + ": " + e.getMessage() + ")\n";
        }
    }
}
