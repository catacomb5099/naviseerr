package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class RadioRepositoryIT {

    @Autowired RadioRepository repository;

    @Test
    void aSavedRadioReadsBackExactly_songsInOrder() {
        UUID id = UUID.randomUUID();
        YoutubeCollectionInfo radio = new YoutubeCollectionInfo("7CTJcHjkq0E", List.of(
                new YoutubeSongInfo("q1", List.of("Earth, Wind & Fire"), List.of("UCewf"), "September",
                        "https://i.ytimg.com/vi/q1/hqdefault.jpg", 216, null),
                new YoutubeSongInfo("q2", List.of("Queen", "David Bowie"), List.of("UCq", ""), "Under Pressure",
                        null, null, null)),
                null, "Billie Jean radio", List.of("Michael Jackson"), List.of("UCmj"), null);

        repository.save(id, "7CTJcHjkq0E", radio).block();
        YoutubeCollectionInfo back = repository.find(id).block();

        assertNotNull(back);
        assertEquals(id.toString(), back.id(), "read back under the radio's own id, not the seed's");
        assertEquals("Billie Jean radio", back.name());
        assertEquals(List.of("Michael Jackson"), back.authorNames());
        assertEquals(List.of("UCmj"), back.authorIds());
        assertNull(back.imageUrl());
        assertEquals(radio.songs(), back.songs());
    }

    @Test
    void anUnknownIdIsEmpty() {
        assertNull(repository.find(UUID.randomUUID()).block());
    }
}
