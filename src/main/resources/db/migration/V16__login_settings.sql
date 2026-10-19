-- Login with Authentik (19-10-2026): how to reach the owner's Authentik, saved from the web app's
-- Settings page. One row at most. The client secret is stored as typed (Immich and Grafana keep theirs
-- the same way) and never leaves the server: GET /settings/login answers only whether one is set.
CREATE TABLE login_settings (
    one           BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (one),  -- the one-row trick: a second INSERT fails
    issuer        TEXT        NOT NULL,   -- https://auth.example.com/application/o/naviseerr/
    client_id     TEXT        NOT NULL,
    client_secret TEXT        NOT NULL,
    -- false until the first sign-in through Authentik succeeds; true = every route needs a session.
    enabled       BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
