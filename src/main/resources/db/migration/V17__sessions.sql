-- Sign in with Authentik (19-10-2026). A session is the random id in the browser's naviseerr_session
-- cookie; the row says who Authentik said it was (its stable subject id for this application, never a
-- name) and until when. Deleted on logout and by "Turn off Authentik login"; expired rows are swept at
-- each sign-in, so no scheduler is needed.
CREATE TABLE sessions (
    session_id TEXT        PRIMARY KEY,
    subject    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_expires_at ON sessions (expires_at);
