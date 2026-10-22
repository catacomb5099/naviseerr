package com.catacomb5099.naviseerr.mediaserver;

import java.util.List;

/** A media-server playlist as naviseerr's account sees it: the song ids in it, and whether the server already says it cannot be changed. */
public record MediaPlaylist(boolean notEditable, List<String> songIds) {}
