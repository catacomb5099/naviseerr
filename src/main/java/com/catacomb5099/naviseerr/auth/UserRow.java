package com.catacomb5099.naviseerr.auth;

import java.util.UUID;

/** One {@code users} row. {@code passwordHash} is never serialised: this record stays inside the auth package. */
public record UserRow(UUID userId, String username, String passwordHash, String role) {}
