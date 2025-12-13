begin;
create table if not exists schema_migrations
(
    id
        bigint
        primary
            key,
    applied_at
        timestamptz
        not
            null
        default
            now()
);
insert into schema_migrations (id)
values (0);
commit;