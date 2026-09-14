package com.catacomb5099.naviseerr.services.ytmusic.model;

import java.util.List;

/**
 * One track, as ytmusic-adapter describes it. The provider-facing answer to "what is this id?",
 * reduced to the three fields the download pipeline needs: an id to store, a name to search
 * Soulseek with, and the artists that name is disambiguated by.
 *
 * <p>Populated from two differently-shaped adapter responses — {@code GET /v1/songs/{videoId}},
 * whose {@code author} is a single string, and the {@code tracks[]} of an album or playlist, whose
 * {@code artists[]} is a list. {@link #authorNames()} is a list so both flatten into it without the
 * caller having to know which shape it came from.
 *
 * @param authorNames never null; empty when the provider named no artist.
 */
public record YoutubeSongInfo(String id, List<String> authorNames, String name) {
}
