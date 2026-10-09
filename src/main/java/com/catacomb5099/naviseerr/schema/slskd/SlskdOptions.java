package com.catacomb5099.naviseerr.schema.slskd;

/**
 * slskd's running configuration -- {@code GET /options}. Only the Soulseek username is read: the
 * configured name, known before any login (slskd masks the password as {@code *****}).
 */
public record SlskdOptions(Soulseek soulseek) {
    public record Soulseek(String username) {}
}
