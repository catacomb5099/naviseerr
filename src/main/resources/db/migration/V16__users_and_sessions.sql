-- Login (ask 10, 19-10-2026): who may use this install, and the browsers they are signed in from.
-- One account for now (the first user, created on the first-run screen); role is kept so more users
-- later need no migration. password_hash is bcrypt; NULL is reserved for a user a login proxy vouches for.
CREATE TABLE users (
    user_id       UUID        PRIMARY KEY,
    username      TEXT        NOT NULL,
    password_hash TEXT,
    role          TEXT        NOT NULL DEFAULT 'ADMIN' CHECK (role IN ('ADMIN', 'USER')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Names differ by letter, never by case: "Alex" and "alex" are one account.
CREATE UNIQUE INDEX users_username_lower ON users (lower(username));

-- A session is the random id in the browser's cookie. Deleted on logout; expired rows are swept at each login.
CREATE TABLE sessions (
    session_id TEXT        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_expires_at ON sessions (expires_at);
