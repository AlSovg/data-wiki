package com.datawiki.auth;

import java.security.Principal;
import java.util.UUID;

/** The authenticated user's id: the JWT subject. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
