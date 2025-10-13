-- Token base table
CREATE TABLE token (
    id BIGSERIAL PRIMARY KEY AUTOINCREMENT,
    token_type TEXT NOT NULL CHECK (token_type IN ('marker', 'text', 'sticker'))
);

-- MarkerToken
CREATE TYPE marker_type AS ENUM ('START', 'END');

CREATE TABLE marker_token (
    id BIGINT PRIMARY KEY REFERENCES token(id) ON DELETE CASCADE,
    type marker_type NOT NULL
);

-- TextToken
CREATE TABLE text_token (
    id BIGINT PRIMARY KEY REFERENCES token(id) ON DELETE CASCADE,
    text TEXT NOT NULL
);

-- StickerToken
CREATE TABLE sticker_token (
    id BIGINT PRIMARY KEY REFERENCES token(id) ON DELETE CASCADE,
    sticker TEXT NOT NULL  -- adjust to UUID if FileId is UUID-like
);

-- Association
CREATE TABLE association (
    id BIGSERIAL PRIMARY KEY,
    context BIGINT[] NOT NULL,
    prediction BIGINT NOT NULL REFERENCES token(id) ON DELETE CASCADE,
    count BIGINT NOT NULL DEFAULT 0,
    UNIQUE (context, prediction)
);

-- Index for efficient array searches
CREATE INDEX idx_association_context ON association USING GIN (context);

INSERT INTO token (id, token_type) VALUES (1, 'marker'), (2, 'marker');
SELECT setval('token_id_seq', 2, TRUE);