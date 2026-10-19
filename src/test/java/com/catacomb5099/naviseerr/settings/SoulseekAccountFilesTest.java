package com.catacomb5099.naviseerr.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two files exactly as docker/setup.sh writes them (the web password sits four spaces deep, the account two). */
class SoulseekAccountFilesTest {

    static final String SECRETS = """
            # Generated once by naviseerr's setup.
            SOULSEEK_USERNAME=naviseerr-ab12cd
            SOULSEEK_PASSWORD=Pw0Generated
            SLSKD_API_KEY=key123
            SLSKD_WEB_PASSWORD=web456
            CURATOR_TOKEN=tok789
            """;
    static final String YML = """
            # Written by naviseerr's setup on every start from .env; edits are overwritten.
            soulseek:
              username: 'naviseerr-ab12cd'
              password: 'Pw0Generated'
              listen_port: 50300
            directories:
              downloads: /downloads
            web:
              https:
                disabled: true
              authentication:
                password: 'web456'
                api_keys:
                  naviseerr:
                    key: 'key123'
                    role: readwrite
            """;

    static SoulseekAccountFiles setupShaped(Path data) throws IOException {
        Files.createDirectories(data.resolve("config"));
        Files.createDirectories(data.resolve("slskd"));
        Files.writeString(data.resolve("config/secrets.env"), SECRETS);
        Files.writeString(data.resolve("slskd/slskd.yml"), YML);
        return new SoulseekAccountFiles(data);
    }

    @Test
    void readsTheAccountFromSlskdYml_andKnowsAMadeUpName(@TempDir Path data) throws IOException {
        var account = setupShaped(data).read();
        assertEquals("naviseerr-ab12cd", account.username());
        assertEquals("Pw0Generated", account.password());
        assertTrue(account.generated());
        assertFalse(account.setInComposeFile());
    }

    @Test
    void writeChangesBothFiles_leavesEveryOtherLine_andDoublesAQuoteTheYamlWay(@TempDir Path data) throws IOException {
        var files = setupShaped(data);

        files.write("alex's music", "p@ss word");

        var account = files.read();
        assertEquals("alex's music", account.username());
        assertEquals("p@ss word", account.password());
        assertFalse(account.generated());
        List<String> yml = Files.readAllLines(data.resolve("slskd/slskd.yml"));
        assertEquals("  username: 'alex''s music'", yml.get(2));
        assertEquals("  password: 'p@ss word'", yml.get(3));
        assertEquals("    password: 'web456'", yml.get(11), "the web password is not the account's");
        assertEquals(YML.lines().count(), yml.size());
        List<String> env = Files.readAllLines(data.resolve("config/secrets.env"));
        assertEquals("SOULSEEK_USERNAME=alex's music", env.get(1));
        assertEquals("SOULSEEK_PASSWORD=p@ss word", env.get(2));
        assertEquals("SLSKD_API_KEY=key123", env.get(3));
        assertEquals(SECRETS.lines().count(), env.size());
    }

    @Test
    void writeKeepsTheOwnerOnlyMode(@TempDir Path data) throws IOException {
        var files = setupShaped(data);
        Files.setPosixFilePermissions(data.resolve("config/secrets.env"), PosixFilePermissions.fromString("rw-------"));

        files.write("me", "secret");

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(data.resolve("config/secrets.env"))));
    }

    @Test
    void anEmptyStoredName_isAnOwnAccount_setFromTheComposeFile(@TempDir Path data) throws IOException {
        var files = setupShaped(data);
        Files.writeString(data.resolve("config/secrets.env"), SECRETS.replace("naviseerr-ab12cd", ""));
        Files.writeString(data.resolve("slskd/slskd.yml"), YML.replace("naviseerr-ab12cd", "my-own-name"));

        var account = files.read();
        assertEquals("my-own-name", account.username());
        assertFalse(account.generated());
        assertTrue(account.setInComposeFile(), "setup leaves the stored name empty when the compose file's own wins");
    }

    @Test
    void aLeftoverTmpLink_isNotFollowed_andTheTmpIsGoneAfterwards(@TempDir Path data) throws IOException {
        var files = setupShaped(data);
        Path elsewhere = data.resolve("elsewhere.txt");
        Files.writeString(elsewhere, "untouched");
        Files.createSymbolicLink(data.resolve("slskd/slskd.yml.tmp"), elsewhere);

        files.write("me", "secret");

        assertEquals("untouched", Files.readString(elsewhere));
        assertFalse(Files.exists(data.resolve("slskd/slskd.yml.tmp")));
        assertEquals("me", files.read().username());
    }

    @Test
    void missingFiles_meanThePageIsOff(@TempDir Path data) {
        assertFalse(new SoulseekAccountFiles(data).exist());
    }
}
