package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.services.slskd.DownloadQuality;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code /settings/download-quality}: the quality the Soulseek picker prefers for every download (songs,
 * playlists, albums), the setup's first screen and the first section of Settings. Spotify's Audio quality
 * list (Low, Normal, High, Very high, Lossless) and no format choice (the owner's call of 19-10-2026). Saved
 * the moment a row is chosen, nothing to test. Nothing chosen = {@link DownloadQuality#DEFAULT}, what the
 * picker effectively preferred until now, and the answer says so ({@code default: true}). The picker reads
 * the row once per completed search, so a change applies to the next download without a restart.
 */
@RestController
@RequestMapping("/settings/download-quality")
public class DownloadQualitySettingsController {

    static final String CHOOSE = "Choose Low, Normal, High, Very high or Lossless.";

    public record Change(String quality) {}

    /** {@code isDefault} ({@code default} on the wire): nothing chosen yet; {@code quality} is then what naviseerr did until now. */
    public record View(DownloadQuality quality, @JsonProperty("default") boolean isDefault) {}

    private final InstallSettingsRepository settings;

    public DownloadQualitySettingsController(InstallSettingsRepository settings) {
        this.settings = settings;
    }

    @GetMapping
    Mono<View> get() {
        return settings.find().map(s -> s.downloadQuality() == null
                ? new View(DownloadQuality.DEFAULT, true)
                : new View(s.downloadQuality(), false));
    }

    /** 200 with the saved choice; 400 for a word that is not one of the five, or none (nothing saved). */
    @PutMapping
    Mono<View> save(@RequestBody Change body) {
        for (DownloadQuality quality : DownloadQuality.values()) {
            if (quality.name().equals(body.quality())) {
                return settings.setDownloadQuality(quality).thenReturn(new View(quality, false));
            }
        }
        return Mono.error(new SettingsProblem(CHOOSE));
    }
}
