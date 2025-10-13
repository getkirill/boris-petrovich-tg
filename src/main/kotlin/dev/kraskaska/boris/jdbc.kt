package dev.kraskaska.boris

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

class JdbcDb(url: String, user: String, password: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection(url, user, password)
    override fun close() {
        conn.close()
    }

    fun execute(s: String, args: PreparedStatement.() -> Unit = {}) {
        conn.prepareStatement(s).use { statement ->
            statement.args()
            statement.execute()
        }
    }

    fun <T> query(s: String, args: PreparedStatement.() -> Unit = {}, block: ResultSet.() -> T): Iterable<T> {
        val result = mutableListOf<T>()
        conn.prepareStatement(s).use { statement ->
            statement.args()
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    result.add(resultSet.block())
                }
            }
        }
        return result.toList()
    }

    fun <T> querySingle(s: String, args: PreparedStatement.() -> Unit = {}, block: ResultSet.() -> T): T? =
        query(s, args) { block() }.lastOrNull()

    fun isTrue(s: String, args: PreparedStatement.() -> Unit): Boolean = querySingle(s, args) { getBoolean(1) }!!
}