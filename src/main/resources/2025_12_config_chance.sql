begin;
CREATE TABLE IF NOT EXISTS chat_config
(
    chat_id
        BIGSERIAL
        PRIMARY KEY
        NOT NULL,
    generate_chance REAL NOT NULL DEFAULT 0.0833333333
);
insert into schema_migrations (id)
values (3);
commit;