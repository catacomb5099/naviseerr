package com.catacomb5099.naviseerr.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The two files the install's {@code setup} step writes the Soulseek account into, under the data
 * folder ({@code NAVISEERR_DATA_DIR}): {@code config/secrets.env} (the account setup carries from one
 * start to the next) and {@code slskd/slskd.yml} (what slskd logs in with). Both are rewritten line by
 * line, everything else in them left as it was, so setup's next run renders the same account again.
 * The yml value is a YAML single-quoted scalar, as setup writes it: a quote inside is doubled.
 */
public final class SoulseekAccountFiles {

    /** setup names a made-up account {@code naviseerr-} plus six letters or digits (docker/setup.sh). */
    static final Pattern GENERATED_NAME = Pattern.compile("naviseerr-[A-Za-z0-9]{6}");
    private static final Pattern YML_USERNAME = Pattern.compile("^  username: (.*)$");
    private static final Pattern YML_PASSWORD = Pattern.compile("^  password: (.*)$");
    private static final Pattern ENV_USERNAME = Pattern.compile("^SOULSEEK_USERNAME=(.*)$");
    private static final Pattern ENV_PASSWORD = Pattern.compile("^SOULSEEK_PASSWORD=(.*)$");

    private final Path secrets;
    private final Path slskdYml;

    public SoulseekAccountFiles(Path dataDir) {
        this.secrets = dataDir.resolve("config/secrets.env");
        this.slskdYml = dataDir.resolve("slskd/slskd.yml");
    }

    /** False on a developer run or an install older than the whole-data-folder mount: the page is then off. */
    public boolean exist() {
        return Files.isRegularFile(secrets) && Files.isRegularFile(slskdYml);
    }

    /** The account slskd logs in with (from slskd.yml, the one file that always holds the effective one). */
    public Account read() throws IOException {
        String username = null;
        String password = null;
        for (String line : Files.readAllLines(slskdYml)) {
            var u = YML_USERNAME.matcher(line);
            var p = YML_PASSWORD.matcher(line);
            if (u.matches() && username == null) username = unquote(u.group(1));
            // Two-space indent: slskd.yml's other password (web.authentication) sits four deep.
            if (p.matches() && password == null) password = unquote(p.group(1));
        }
        return new Account(username == null ? "" : username, password == null ? "" : password);
    }

    /** Rewrites the account in both files; the yml first, since slskd reads that one. */
    public void write(String username, String password) throws IOException {
        rewrite(slskdYml, YML_USERNAME, "  username: " + quote(username), YML_PASSWORD, "  password: " + quote(password));
        rewrite(secrets, ENV_USERNAME, "SOULSEEK_USERNAME=" + username, ENV_PASSWORD, "SOULSEEK_PASSWORD=" + password);
    }

    private static void rewrite(Path file, Pattern a, String lineA, Pattern b, String lineB) throws IOException {
        List<String> out = new ArrayList<>();
        boolean sawA = false;
        boolean sawB = false;
        for (String line : Files.readAllLines(file)) {
            if (!sawA && a.matcher(line).matches()) { out.add(lineA); sawA = true; }
            else if (!sawB && b.matcher(line).matches()) { out.add(lineB); sawB = true; }
            else out.add(line);
        }
        if (!sawA) out.add(lineA);
        if (!sawB) out.add(lineB);
        // Whole file at once under a temporary name, then an atomic move: a crash mid-write never
        // leaves slskd half a config. The owner-only mode setup gave the file is carried over.
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, out);
        try {
            Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException ignored) {
            // Not a POSIX file system (Windows): the default mode stands.
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static String unquote(String yaml) {
        String v = yaml.trim();
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1).replace("''", "'");
        }
        return v;
    }

    /** The account; {@code generated} when the name is one setup makes up, so the password may be shown. */
    public record Account(String username, String password) {
        public boolean generated() {
            return GENERATED_NAME.matcher(username).matches();
        }
    }
}
