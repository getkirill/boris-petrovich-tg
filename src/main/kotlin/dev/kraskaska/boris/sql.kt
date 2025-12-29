package dev.kraskaska.boris

import dev.inmo.tgbotapi.requests.abstracts.FileId
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import java.sql.Timestamp

class PostgresDatabase(
    val url: String = System.getenv("POSTGRES_URL")!!,
    val username: String = System.getenv("POSTGRES_USER")!!,
    val password: String = System.getenv("POSTGRES_PASSWORD")!!
) : Database(), AutoCloseable {
    class DbAssociation(
        chatId: Long,
        context: List<Token>,
        prediction: Token,
        count: Long,
        val update: DbAssociation.(Long) -> Unit
    ) :
        Association(chatId, context, prediction, count) {
        override var count: Long = count
            get() = super.count
            set(value) {
                field = value;
                this.update(value);
            }
    }

    val conn = JdbcDb("jdbc:$url", username, password)


    override fun findOrMakeTextTokenFor(segment: String): TextToken =
        conn.querySingle("SELECT id FROM text_token WHERE text = ?;", { setString(1, segment) }) {
            TextToken(
                getLong(1),
                segment
            )
        }
            ?: conn.querySingle(
                """
                WITH inserted_token AS (
                    INSERT INTO token (token_type)
                    VALUES ('text')
                    RETURNING id
                )
                INSERT INTO text_token (id, text)
                SELECT
                    id,
                    ?
                FROM inserted_token
                RETURNING id;
            """.trimIndent(), { setString(1, segment) }
            ) {
                TextToken(
                    getLong(1),
                    segment
                )
            }!!

    override fun findOrMakeStickerTokenFor(sticker: FileId): StickerToken =
        conn.querySingle("SELECT id FROM sticker_token WHERE sticker = ?;", { setString(1, sticker.fileId) }) {
            StickerToken(
                getLong(1),
                sticker
            )
        }
            ?: conn.querySingle(
                """
                WITH new_token AS (
                    INSERT INTO token (token_type)
                    VALUES ('sticker')
                    RETURNING id
                )
                INSERT INTO sticker_token (id, sticker)
                SELECT id, ?
                FROM new_token
                RETURNING id;
            """.trimIndent(), { setString(1, sticker.fileId) }
            ) {
                StickerToken(
                    getLong(1),
                    sticker
                )
            }!!

    override fun findOrMakeAssociation(chatId: Long, context: Iterable<Token>, prediction: Token): Association {
        if (!conn.isTrue(
                "SELECT EXISTS(SELECT 1 FROM association WHERE context = ? AND prediction = ? AND chat_id = ?);"
            ) {
                setArray(1, conn.conn.createArrayOf("BIGINT", context.map { it.id }.toList().toTypedArray()))
                setLong(2, prediction.id)
                setLong(3, chatId)
            }
        ) {
            conn.execute(
                "INSERT INTO association(context,prediction,count,chat_id) VALUES (?,?,0,?);"
            ) {
                setArray(1, conn.conn.createArrayOf("BIGINT", context.map { it.id }.toList().toTypedArray()))
                setLong(2, prediction.id)
                setLong(3, chatId)
            }
        }
        return DbAssociation(
            chatId,
            context.toList(),
            prediction,
            conn.querySingle(
                "SELECT count FROM association WHERE context = ? AND prediction = ? AND chat_id = ?;",
                {
                    setArray(1, conn.conn.createArrayOf("BIGINT", context.map { it.id }.toList().toTypedArray()))
                    setLong(2, prediction.id)
                    setLong(3, chatId)
                }
            ) { getLong(1) }!!
        ) {
            conn.execute(
                "UPDATE association SET count = ? WHERE context = ? AND prediction = ? AND chat_id = ?;"
            ) {
                setLong(1, it)
                setArray(2, conn.conn.createArrayOf("BIGINT", context.map { it.id }.toList().toTypedArray()))
                setLong(3, prediction.id)
                setLong(4, chatId)
            }
        }
    }

    override fun cacheTokensForTraining(
        chatId: Long,
        tokens: Iterable<Token>?
    ) {
        conn.execute(
            """
            INSERT INTO token_cache (chat_id, tokens) 
            VALUES (?, ?)
            ON CONFLICT (chat_id) 
            DO UPDATE SET tokens = EXCLUDED.tokens;
        """.trimIndent(),
            {
                setLong(1, chatId);
                if (tokens != null) setArray(
                    2,
                    conn.conn.createArrayOf("BIGINT", tokens!!.map { it.id }.toList().toTypedArray())
                ) else setNull(2, java.sql.Types.ARRAY)
            })
    }

    override fun recallTokensForTraining(chatId: Long): Iterable<Token>? = conn.querySingle("""
        SELECT tokens FROM token_cache WHERE chat_id = ?;
    """.trimIndent(), {setLong(1, chatId)}) {
        val arr = getArray(1).array as? Array<Long>? ?: return@querySingle null
        arr.map { getToken(it)!! }
    }

    override fun getToken(id: Long): Token? =
        if (id == 1L) MarkerToken.START
        else if (id == 2L) MarkerToken.END
        else
            conn.querySingle("SELECT token_type FROM token WHERE id = ?;", { setLong(1, id) }) {
                when (val token = getString("token_type")) {
                    "text" -> conn.querySingle("SELECT text FROM text_token WHERE id = ?;", { setLong(1, id) }) {
                        TextToken(
                            id,
                            getString("text")
                        )
                    }

                    "sticker" -> conn.querySingle(
                        "SELECT sticker FROM sticker_token WHERE id = ?;",
                        { setLong(1, id) }) {
                        StickerToken(
                            id,
                            FileId(getString("sticker"))
                        )
                    }

                    "marker" -> error("All possible marker tokens were handled before reaching database.")
                    else -> error("Impossible token type $token for $id")
                }
            }

    override fun possiblePredictions(chatId: Long, context: Iterable<Token>): Iterable<Association> =
        if (context.last() == MarkerToken.END) emptyList() else
            conn.query("SELECT prediction, count FROM association WHERE context = ? AND chat_id = ?;", {
                setArray(
                    1,
                    conn.conn.createArrayOf("BIGINT", context.map { it.id }.toList().toTypedArray())
                )
                setLong(2, chatId)
            }) {
                DbAssociation(
                    chatId,
                    context.toList(),
                    getToken(getLong(1)) ?: error("Could not find token while getting possible predictions!"),
                    getLong(2)
                ) {
                    conn.execute(
                        "UPDATE association SET count = ? WHERE context = ? AND prediction = ? AND chat_id = ?;"
                    ) {
                        setLong(1, it)
                        setArray(2, conn.conn.createArrayOf("BIGINT", context.map { it.id }.toList().toTypedArray()))
                        setLong(3, prediction.id)
                        setLong(4, chatId)
                    }
                }
            }

    override fun possibleContexts(chatId: Long, prediction: Token): Iterable<Association> =
        conn.query("SELECT context, count FROM association WHERE prediction = ? AND chat_id = ?;", {
            setLong(
                1,
                prediction.id
            )
            setLong(2, chatId)
        }) {
            DbAssociation(
                chatId,
                (getArray(1).array as Array<Long>).map { getToken(it)!! },
                prediction,
                getLong(2)
            ) {
                conn.execute(
                    "UPDATE association SET count = ? WHERE context = ? AND prediction = ? AND chat_id = ?;"
                ) {
                    setLong(1, it)
                    setArray(2, conn.conn.createArrayOf("BIGINT", context.map { it.id }.toList().toTypedArray()))
                    setLong(3, prediction.id)
                    setLong(4, chatId)
                }
            }
        }

    override fun getConfigForChat(chatId: Long): Config {
        if (!conn.isTrue("""SELECT EXISTS(SELECT 1 FROM chat_config WHERE chat_id = ?);""") { setLong(1, chatId) }) {
            conn.execute("""INSERT INTO chat_config(chat_id) VALUES (?);""") { setLong(1, chatId) }
        }
        return conn.querySingle(
            """SELECT generate_chance, silence_until FROM chat_config WHERE chat_id = ?;""",
            { setLong(1, chatId) }) { Config(chatId, getFloat(1), getTimestamp(2)?.toInstant()?.toKotlinInstant()) }!!
    }

    override fun saveConfig(config: Config) {
        conn.execute("""UPDATE chat_config SET generate_chance = ?, silence_until = ? WHERE chat_id = ?;""") {
            setFloat(
                1,
                config.generationChance
            )
            setTimestamp(2, config.silenceUntil?.let { Timestamp.from(it.toJavaInstant()) })
            setLong(3, config.chatId)
        }
    }

    override val associations: Iterable<Association>
        get() = conn.query(
            """
            SELECT id, context, prediction, count
            FROM association;
        """.trimIndent()
        ) {
            val associationId = getLong("id")
            DbAssociation(
                0,
                (getArray("context").array as Array<Long>).map { tokenId ->
                    getToken(tokenId)
                        ?: error("Token $tokenId doesn't exist but is referred in association $associationId context.")
                }.toList(),
                getLong("prediction").let { tokenId ->
                    getToken(tokenId)
                        ?: error("Token $tokenId doesn't exist but is referred in association $associationId prediction.")
                }, getLong("count")
            ) {
                conn.execute("UPDATE association SET count = ? WHERE id = ?;") {
                    setLong(1, it); setLong(
                    2,
                    associationId
                )
                }
            }
        }
    override val associationCount: Int
        get() = conn.querySingle("SELECT COUNT(*) FROM association;") { getLong(1) }!!.toInt()

    override fun associationCountForChat(id: Long?): Int =
        if (id == null) conn.querySingle("SELECT COUNT(*) FROM association WHERE chat_id IS NULL;") { getLong(1) }!!
            .toInt() else conn.querySingle(
            "SELECT COUNT(*) FROM association WHERE chat_id = ?;",
            { setLong(1, id) }) { getLong(1) }!!.toInt()

    override fun wipeAssociationsForChat(id: Long) {
        conn.execute(
            """
            BEGIN;

            DELETE FROM association a
            USING association b
            WHERE a.chat_id = ?
              AND b.chat_id = -1
              AND a.context = b.context
              AND a.prediction = b.prediction;
            
            UPDATE association
            SET chat_id = -1
            WHERE chat_id = ?;
            
            COMMIT;
        """.trimIndent(), { setLong(1, id); setLong(2, id) })
    }

    override fun leaderboard(n: Int): Iterable<LeaderboardEntry> = conn.query(
        """
            SELECT
                chat_id,
                RANK() OVER (ORDER BY COUNT(*) DESC) as rank,
                COUNT(*) AS total_rows
            FROM
                association
            GROUP BY
                chat_id
            ORDER BY
                rank ASC,
                chat_id ASC
            LIMIT 10;
            """
    ) { LeaderboardEntry(getLong(1), getInt(2), getInt(3)) }

    override fun leaderboardPositionFor(chatId: Long): LeaderboardEntry = conn.querySingle(
        """
        WITH Leaderboard AS (
            SELECT 
                chat_id, 
                COUNT(*) AS total_rows,
                RANK() OVER (ORDER BY COUNT(*) DESC) as position
            FROM 
                association
            GROUP BY 
                chat_id
        )
        SELECT 
            position, 
            total_rows
        FROM 
            Leaderboard
        WHERE 
            chat_id = ?--; -- Replace with your target chatid
    """.trimIndent(), { setLong(1, chatId) }) { LeaderboardEntry(chatId, getInt(1), getInt(2)) }
        ?: Database.LeaderboardEntry(chatId, -1, 0)

    override val tokenCount: Int
        get() = conn.querySingle("SELECT COUNT(*) FROM token;") { getLong(1) }!!.toInt()

    override fun close() {
        conn.close()
    }
}