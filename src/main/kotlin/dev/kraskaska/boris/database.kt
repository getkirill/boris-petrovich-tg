package dev.kraskaska.boris

import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent

abstract class Database {
    abstract fun cacheMessageForTraining(chat: Chat, message: ContentMessage<MessageContent>?)
    abstract fun recallMessageForTraining(chat: Chat): ContentMessage<MessageContent>?
    open fun getToken(id: Long): Token? = null
    abstract fun findOrMakeTextTokenFor(segment: String): TextToken
    abstract fun findOrMakeStickerTokenFor(sticker: FileId): StickerToken
    abstract fun findOrMakeAssociation(context: Iterable<Token>, prediction: Token): Association

    open fun updateAssociations(tokens: Iterable<Token>) {
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
                findOrMakeAssociation(context.subList(
                    0, window - 1
                ), context[window - 1]).count += 1
            }
        }
//        println("dev.kraskaska.boris.Association updated.\n"+associations.joinToString("\n"))
    }
    protected abstract val associations: Iterable<Association>
    abstract val associationCount: Int
    abstract val tokenCount: Int

    open fun possiblePredictions(context: Iterable<Token>): Iterable<Association> =
        if (context.last() == MarkerToken.END) emptyList() else
            associations.filter { it.context.toList() == context.toList() }
    open fun possibleContexts(prediction: Token): Iterable<Association> =
        associations.filter { it.prediction == prediction }

    open fun predictToken(context: Iterable<Token>) =
        possiblePredictions(context).filter { !(context.last() == MarkerToken.START && it.prediction == MarkerToken.END) }
            .weightedRandom()

    open fun predictUntilEnd(token: Iterable<Token>): MutableList<Token> {
        println("Predicting tokens until end from starting context ${token.joinToString()}")
        val list = token.toMutableList()
        do {
            for (window in CONTEXT_WINDOW.coerceAtMost(list.size) downTo 1) {
                println("Possible predictions ($window): ${possiblePredictions(list.takeLast(window)).map { "${it.prediction} (${it.count})" }}")
                if (possiblePredictions(list.takeLast(window)).toList().isNotEmpty()) {
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

    override fun getToken(id: Long): Token? =
        textTokenList.firstOrNull { it.id == id } ?: stickerTokenList.firstOrNull { it.id == id }

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

    override fun findOrMakeAssociation(context: Iterable<Token>, prediction: Token): Association {
        return (_associations.firstOrNull {
            it.context == context.toList() && it.prediction == prediction
        } ?: Association(
            context.toList(), prediction, 0
        ).apply { _associations.add(this) })
    }

    override val associations: Iterable<Association> = _associations
    override val associationCount: Int
        get() = _associations.size
    override val tokenCount: Int
        get() = textTokenList.size + stickerTokenList.size + 2
}