begin;
ALTER TABLE chat_config
    ADD COLUMN context_window INTEGER NOT NULL DEFAULT 5;
insert into schema_migrations (id)
values (6);
commit;