package dev.kraskaska.boris.migrations

import dev.kraskaska.boris.JdbcDb

abstract class Migration(val id: Long) {
    abstract fun migrate(db: JdbcDb)
    override fun toString(): String {
        return "${javaClass.simpleName}(id=$id)"
    }

}

class ResourceFileBackedMigration(val resourcePath: String, id: Long) : Migration(id) {
    override fun migrate(db: JdbcDb) {
        db.execute(javaClass.getResource(resourcePath)!!.readText())
    }

    override fun toString(): String {
        return "ResourceFileBackedMigration(id=$id, resourcePath='$resourcePath')"
    }

}

val migrations: List<Migration> = listOf(
    ResourceFileBackedMigration("/2025_12_migrations.sql", 0),
    ResourceFileBackedMigration("/2025_12_initial_schema.sql", 1),
)

fun runMigrations(jdbcDb: JdbcDb) {
    println("Migrating database...")
    if (!jdbcDb.isTrue(
            """select exists (
                    select 1
                    from information_schema.tables
                    where table_schema = 'public'
                      and table_name   = 'schema_migrations'
                ) as exists;
                """
        )
    ) {
        println("Migrations table does not exist!")
        migrations[0].migrate(jdbcDb)
    }
    migrations.filter {
        !jdbcDb.isTrue(
            """select exists (
                select 1
                from schema_migrations
                where id = ?
                )"""
        ) { setLong(1, it.id) }
    }.forEach {
        println("Applying $it...")
        it.migrate(jdbcDb)
    }
}