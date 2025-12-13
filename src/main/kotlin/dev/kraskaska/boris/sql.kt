package dev.kraskaska.boris

import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent

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
    override fun cacheMessageForTraining(
        chat: Chat,
        message: ContentMessage<MessageContent>?
    ) {
        TODO("Not yet implemented")
    }

    override fun recallMessageForTraining(chat: Chat): ContentMessage<MessageContent>? {
        TODO("Not yet implemented")
    }

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
            """SELECT generate_chance FROM chat_config WHERE chat_id = ?;""",
            { setLong(1, chatId) }) { Config(chatId, getFloat(1)) }!!
    }

    override fun saveConfig(config: Config) {
        conn.execute("""UPDATE chat_config SET generate_chance = ? WHERE chat_id = ?;""") {
            setFloat(
                1,
                config.generationChance
            ); setLong(2, config.chatId)
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

    override val tokenCount: Int
        get() = conn.querySingle("SELECT COUNT(*) FROM token;") { getLong(1) }!!.toInt()

    override fun close() {
        conn.close()
    }
}