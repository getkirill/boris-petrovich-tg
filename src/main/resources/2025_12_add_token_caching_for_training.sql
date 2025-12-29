begin;
CREATE TABLE token_cache
(
    chat_id     BIGINT PRIMARY KEY,
    tokens BIGINT[]
);
insert into schema_migrations (id)
values (5);
commit;