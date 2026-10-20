package com.catacomb5099.naviseerr.auth;

/**
 * The one {@code login_settings} row: how to reach the owner's Authentik. Never serialised as such
 * ({@code clientSecret} must not leave the server); {@link LoginSettingsController.View} is what the API shows.
 */
record LoginSettings(String issuer, String clientId, String clientSecret, boolean enabled) {}
