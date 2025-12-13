begin;
ALTER TABLE association
    ADD COLUMN chat_id BIGINT;
ALTER TABLE association
    DROP CONSTRAINT IF EXISTS association_context_prediction_key;
ALTER TABLE association
    ADD CONSTRAINT association_context_prediction_chat_id_key
        UNIQUE (chat_id, context, prediction);
insert into schema_migrations (id)
values (2);
commit;