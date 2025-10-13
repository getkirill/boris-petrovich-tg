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
        context: List<Token>,
        prediction: Token,
        count: Long,
        val update: DbAssociation.(Long) -> Unit
    ) :
        Association(context, prediction, count) {
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
        conn.querySingle("SELECT id FROM text_token WHERE text = ?;", { setString(1, segment) }) { TextToken(getLong(1), segment) }
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

    override fun findOrMakeAssociation(context: Iterable<Token>, prediction: Token): Association {
        if (!conn.isTrue(
                "SELECT EXISTS(SELECT 1 FROM association WHERE context @> ? AND prediction = ?);"
            ) {
                setArray(1, conn.conn.createArrayOf("BIGINT", context.map {it.id}.toList().toTypedArray()))
                setLong(2, prediction.id)
            }
        ) {
            conn.execute(
                "INSERT INTO association(context,prediction,count) VALUES (?,?,0);"
            ) {
                setArray(1, conn.conn.createArrayOf("BIGINT", context.map {it.id}.toList().toTypedArray()))
                setLong(2, prediction.id)
            }
        }
        return DbAssociation(
            context.toList(),
            prediction,
            conn.querySingle(
                "SELECT count FROM association WHERE context @> ? AND prediction = ?",
                {
                    setArray(1, conn.conn.createArrayOf("BIGINT", context.map {it.id}.toList().toTypedArray()))
                    setLong(2, prediction.id)
                }
            ) { getLong(1) }!!
        ) {
            conn.execute(
                "UPDATE association SET count = ? WHERE context @> ? AND prediction = ?;"
            ) {
                setLong(1, it)
                setArray(2, conn.conn.createArrayOf("BIGINT", context.map {it.id}.toList().toTypedArray()))
                setLong(3, prediction.id)
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

                    "sticker" -> conn.querySingle("SELECT sticker FROM sticker_token WHERE id = ?;", { setLong(1, id) }) {
                        StickerToken(
                            id,
                            FileId(getString("sticker"))
                        )
                    }

                    "marker" -> error("All possible marker tokens were handled before reaching database.")
                    else -> error("Impossible token type $token for $id")
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
    override val tokenCount: Int
        get() = conn.querySingle("SELECT COUNT(*) FROM token;") { getLong(1) }!!.toInt()

    override fun close() {
        conn.close()
    }
}