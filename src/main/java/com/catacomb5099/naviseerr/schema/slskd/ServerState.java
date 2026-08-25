package com.catacomb5099.naviseerr.schema.slskd;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** slskd's own connection state to the Soulseek network -- {@code GET /server}. */
@Getter
@AllArgsConstructor
public class ServerState {
    String address;
    String ipEndPoint;
    String state;
    boolean isConnected;
    boolean isConnecting;
    boolean isLoggedIn;
    boolean isLoggingIn;
    boolean isTransitioning;
}
