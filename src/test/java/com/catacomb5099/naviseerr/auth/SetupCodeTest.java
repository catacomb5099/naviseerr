package com.catacomb5099.naviseerr.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SetupCodeTest {

    private final AuthRepository repository = mock(AuthRepository.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    @TempDir Path dir;

    @BeforeEach
    void captureLog() {
        logged.start();
        ((Logger) LoggerFactory.getLogger(SetupCode.class)).addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        ((Logger) LoggerFactory.getLogger(SetupCode.class)).detachAppender(logged);
    }

    @Test
    void noAccountYet_printsTheBannerWithTheCode_andSavesItOwnerOnly() throws Exception {
        when(repository.anyUser()).thenReturn(Mono.just(false));
        SetupCode setup = new SetupCode(repository, true, "", dir.toString());

        setup.onReady();

        String code = setup.current();
        assertNotNull(code);
        assertTrue(code.matches("[23456789a-hj-km-np-z]{4}-[23456789a-hj-km-np-z]{4}-[23456789a-hj-km-np-z]{4}"), code);
        assertEquals(1, logged.list.size());
        String banner = logged.list.get(0).getFormattedMessage();
        assertTrue(banner.contains("Setup code: " + code), banner);
        assertTrue(banner.contains("nobody else can create one"), banner);
        Path file = dir.resolve("config/setup-code.txt");
        assertEquals(code, Files.readString(file).strip());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertTrue(banner.contains("Also saved to " + file), banner);
    }

    @Test
    void theCodeIsForgivingAboutCaseAndDashes_butNotAboutAnythingElse() {
        when(repository.anyUser()).thenReturn(Mono.just(false));
        SetupCode setup = new SetupCode(repository, true, "", "");
        setup.onReady();
        String code = setup.current();

        assertTrue(setup.matches(code));
        assertTrue(setup.matches(code.toUpperCase().replace("-", " ")));
        assertTrue(setup.matches("  " + code.replace("-", "") + "\n"));
        assertFalse(setup.matches(code.substring(1)));
        assertFalse(setup.matches(""));
        assertFalse(setup.matches(null));
    }

    @Test
    void theRightCodeIsSpentOnce_andTheFileGoesWithIt() throws Exception {
        when(repository.anyUser()).thenReturn(Mono.just(false));
        Path file = dir.resolve("elsewhere/code.txt");
        SetupCode setup = new SetupCode(repository, true, file.toString(), dir.toString());
        setup.onReady();
        String code = setup.current();
        assertTrue(Files.exists(file), "the explicit file wins over the data folder");
        assertFalse(Files.exists(dir.resolve("config/setup-code.txt")));

        setup.consumed();

        assertFalse(setup.matches(code), "void after use");
        assertNull(setup.current());
        assertFalse(Files.exists(file));
    }

    @Test
    void anAccountExists_noBanner_noCode_andAStaleFileIsRemoved() throws Exception {
        when(repository.anyUser()).thenReturn(Mono.just(true));
        Path stale = dir.resolve("config/setup-code.txt");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "old-code-here\n");
        SetupCode setup = new SetupCode(repository, true, "", dir.toString());

        setup.onReady();

        assertNull(setup.current());
        assertTrue(logged.list.isEmpty(), "nothing printed once the account exists");
        assertFalse(Files.exists(stale));
        assertFalse(setup.matches("old-code-here"));
    }

    @Test
    void loginOff_nothingHappens_andTheDatabaseIsNotAsked() {
        SetupCode setup = new SetupCode(repository, false, "", dir.toString());

        setup.onReady();

        assertNull(setup.current());
        assertTrue(logged.list.isEmpty());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }
}
