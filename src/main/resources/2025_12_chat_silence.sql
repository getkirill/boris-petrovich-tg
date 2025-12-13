begin;
ALTER TABLE chat_config
    ADD COLUMN silence_until TIMESTAMP;
insert into schema_migrations (id)
values (4);
commit;