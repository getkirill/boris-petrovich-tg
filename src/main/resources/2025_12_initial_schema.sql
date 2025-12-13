-- schema was made before migrations framework, so it's more lenient with pre-existing structure.
begin;

-- Token base table
CREATE TABLE IF NOT EXISTS token
(
    id
        BIGSERIAL
        PRIMARY
            KEY,
    token_type
        TEXT
        NOT
            NULL
        CHECK (
            token_type
                IN
            (
             'marker',
             'text',
             'sticker'
                ))
);
DO
$$
    BEGIN
        -- MarkerToken
        CREATE TYPE marker_type AS ENUM ('START', 'END');
    EXCEPTION
        WHEN duplicate_object THEN null;
    END
$$;

CREATE TABLE IF NOT EXISTS marker_token
(
    id
         BIGINT
        PRIMARY
            KEY
        REFERENCES
            token
                (
                 id
                    ) ON DELETE CASCADE,
    type marker_type NOT NULL
);

-- TextToken
CREATE TABLE IF NOT EXISTS text_token
(
    id
         BIGINT
        PRIMARY
            KEY
        REFERENCES
            token
                (
                 id
                    ) ON DELETE CASCADE,
    text TEXT NOT NULL
);

-- StickerToken
CREATE TABLE IF NOT EXISTS sticker_token
(
    id
            BIGINT
        PRIMARY
            KEY
        REFERENCES
            token
                (
                 id
                    ) ON DELETE CASCADE,
    sticker TEXT NOT NULL -- adjust to UUID if FileId is UUID-like
);

-- Association
CREATE TABLE IF NOT EXISTS association
(
    id
          BIGSERIAL
        PRIMARY
            KEY,
    context
          BIGINT[]
                 NOT
                     NULL,
    prediction
          BIGINT
                 NOT
                     NULL
        REFERENCES
            token
                (
                 id
                    ) ON DELETE CASCADE,
    count BIGINT NOT NULL DEFAULT 0,
    UNIQUE
        (
         context,
         prediction
            )
);

-- Index for efficient array searches
CREATE INDEX IF NOT EXISTS idx_association_context ON association USING GIN (context);

INSERT INTO token (id, token_type)
VALUES (1, 'marker'),
       (2, 'marker')
on conflict do nothing;

select setval('token_id_seq', 2, true)
where (select last_value
       from token_id_seq) < 2;

insert into schema_migrations (id)
values (1);
commit;