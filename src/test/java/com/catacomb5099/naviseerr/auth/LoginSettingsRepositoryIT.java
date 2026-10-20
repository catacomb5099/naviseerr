package com.catacomb5099.naviseerr.auth;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class LoginSettingsRepositoryIT {

    @Autowired LoginSettingsRepository repository;
    @Autowired R2dbcEntityTemplate template;

    /** The context (and its database) is shared with the other ITs: leave the table as found. */
    @BeforeEach
    @AfterEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM login_settings").fetch().rowsUpdated().block();
    }

    @Test
    void noRow_isOpen_andFindIsEmpty() {
        assertNull(repository.find().block());
        assertEquals(Boolean.FALSE, repository.isEnabled().block(), "never connected = open");
    }

    @Test
    void save_writesOneRow_aSecondSaveReplacesIt_andEnabledSurvivesTheReplace() {
        repository.save("https://auth.example.com/application/o/naviseerr/", "id-1", "secret-1").block();
        LoginSettings first = repository.find().block();
        assertNotNull(first);
        assertEquals("id-1", first.clientId());
        assertEquals("secret-1", first.clientSecret());
        assertFalse(first.enabled(), "saving never turns the login on");

        repository.setEnabled(true).block();
        assertEquals(Boolean.TRUE, repository.isEnabled().block());

        repository.save("https://auth.example.com/application/o/music/", "id-2", "secret-2").block();
        LoginSettings second = repository.find().block();
        assertNotNull(second);
        assertEquals("https://auth.example.com/application/o/music/", second.issuer());
        assertEquals("secret-2", second.clientSecret());
        assertTrue(second.enabled(), "a changed address does not switch the door off");
        assertEquals(1L, template.getDatabaseClient().sql("SELECT count(*) AS n FROM login_settings")
                .map(row -> row.get("n", Long.class)).one().block(), "one row, ever");

        repository.setEnabled(false).block();
        assertEquals(Boolean.FALSE, repository.isEnabled().block());
    }
}
