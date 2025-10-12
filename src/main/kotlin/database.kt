import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent

abstract class Database {
    abstract fun cacheMessageForTraining(chat: Chat, message: ContentMessage<MessageContent>?)
    abstract fun recallMessageForTraining(chat: Chat): ContentMessage<MessageContent>?
    abstract fun findOrMakeTextTokenFor(segment: String): TextToken
    abstract fun findOrMakeStickerTokenFor(sticker: FileId): StickerToken

    abstract fun updateAssociations(tokens: Iterable<Token>)
    protected abstract val associations: Iterable<Association>
    abstract val associationCount: Int
    abstract val tokenCount: Int

    open fun possiblePredictions(context: Iterable<Token>) =
        if (context.last() == MarkerToken.END) emptyList() else
            associations.filter { it.context.toList() == context.toList() }

    open fun predictToken(context: Iterable<Token>) =
        possiblePredictions(context).filter { !(context.last() == MarkerToken.START && it.prediction == MarkerToken.END) }
            .weightedRandom()

    open fun predictUntilEnd(token: Iterable<Token>): MutableList<Token> {
        println("Predicting tokens until end from starting context ${token.joinToString()}")
        val list = token.toMutableList()
        do {
            for (window in CONTEXT_WINDOW.coerceAtMost(list.size) downTo 1) {
                println("Possible predictions ($window): ${possiblePredictions(list.takeLast(window)).map { "${it.prediction} (${it.count})" }}")
                if (possiblePredictions(list.takeLast(window)).isNotEmpty()) {
                    list += predictToken(list.takeLast(window)).prediction
                    break
                }
            }
            println("Chosen prediction: ${list.last()}")
            println("Context: $list")
        } while (list.last() != MarkerToken.END)
        return list
    }

    companion object {
        /**
         * Maximum context window. CONTEXT_WINDOW tokens predict 1 token
         */
        const val CONTEXT_WINDOW = 5
    }
}

open class InMemoryDatabase : Database() {
    private val messageCache = mutableMapOf<Long, ContentMessage<MessageContent>>()
    private var nextId = 3L // 1 and 2 reserved for markers
    private val textTokenList = mutableListOf<TextToken>()
    private val stickerTokenList = mutableListOf<StickerToken>()
    private val _associations = mutableListOf<Association>()
    override fun cacheMessageForTraining(chat: Chat, message: ContentMessage<MessageContent>?) {
        if (message == null) messageCache.remove(chat.id.chatId.long)
        else messageCache[chat.id.chatId.long] = message
    }

    override fun recallMessageForTraining(chat: Chat): ContentMessage<MessageContent>? {
        return messageCache[chat.id.chatId.long]
    }

    override fun findOrMakeTextTokenFor(segment: String): TextToken {
        return textTokenList.firstOrNull { it.text == segment } ?: nextId.let {
            nextId++; TextToken(
                it, segment
            ).apply { textTokenList.add(this) }
        }
    }

    override fun findOrMakeStickerTokenFor(sticker: FileId): StickerToken {
        return stickerTokenList.firstOrNull { it.sticker == sticker } ?: nextId.let {
            nextId++; StickerToken(
            it, sticker
        ).apply { stickerTokenList.add(this) }
        }
    }

    override fun updateAssociations(tokens: Iterable<Token>) {
        if (tokens.toList().isEmpty()) return
        (2..(CONTEXT_WINDOW + 1)).forEach { window ->
            tokens.windowed(window).forEach { context ->
                println(
                    "Associating context ${
                        context.subList(
                            0, window - 1
                        )
                    } with ${context[window - 1]}"
                )
                (_associations.firstOrNull {
                    it.context == context.subList(
                        0, window - 1
                    ) && it.prediction == context[window - 1]
                } ?: Association(
                    context.subList(0, window - 1), context[window - 1], 0
                ).apply { _associations.add(this) }).count += 1
            }
        }
//        println("Association updated.\n"+associations.joinToString("\n"))
    }

    override val associations: Iterable<Association> = _associations
    override val associationCount: Int
        get() = _associations.size
    override val tokenCount: Int
        get() = textTokenList.size + stickerTokenList.size + 2
}

//class PrimitiveFileDatabase(val file: File) : InMemoryDatabase(), AutoCloseable {
//    init {
//        if (file.exists()) {
//            TODO()
//        } else {
//            // leave everything default
//        }
//    }
//
//    override fun close() {
//        val stream = DataOutputStream(FileOutputStream(file))
//        stream.writeLong(FILE_VERSION)
//
//    }
//
//    companion object {
//        val FILE_VERSION = 20251011L
//    }
//}