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
    abstract fun findOrMakeAssociation(chatId: Long, context: Iterable<Token>, prediction: Token): Association

    open fun updateAssociations(tokens: Iterable<Token>, chatId: Long) {
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
                findOrMakeAssociation(chatId, context.subList(
                    0, window - 1
                ), context[window - 1]).count += 1
            }
        }
//        println("dev.kraskaska.boris.Association updated.\n"+associations.joinToString("\n"))
    }
    protected abstract val associations: Iterable<Association>
    abstract val associationCount: Int
    abstract fun associationCountForChat(id: Long? = null): Int
    abstract fun wipeAssociationsForChat(id: Long)

    data class LeaderboardEntry(val chatId: Long, val globalPosition: Int, val count: Int)
    abstract fun leaderboard(n: Int): Iterable<LeaderboardEntry>
    abstract fun leaderboardPositionFor(chatId: Long): LeaderboardEntry
    abstract val tokenCount: Int

    open fun possiblePredictions(chatId: Long, context: Iterable<Token>): Iterable<Association> =
        if (context.last() == MarkerToken.END) emptyList() else
            associations.filter { it.context.toList() == context.toList() }
    open fun possibleContexts(chatId: Long, prediction: Token): Iterable<Association> =
        associations.filter { it.prediction == prediction }

    open fun predictToken(chatId: Long, context: Iterable<Token>) =
        possiblePredictions(chatId, context).filter { !(context.last() == MarkerToken.START && it.prediction == MarkerToken.END) }
            .weightedRandom()

    open fun predictUntilEnd(chatId: Long, token: Iterable<Token>): MutableList<Token> {
        println("Predicting tokens until end from starting context ${token.joinToString()}")
        val list = token.toMutableList()
        do {
            for (window in CONTEXT_WINDOW.coerceAtMost(list.size) downTo 1) {
                println("Possible predictions ($window): ${possiblePredictions(chatId, list.takeLast(window)).map { "${it.prediction} (${it.count})" }}")
                if (possiblePredictions(chatId, list.takeLast(window)).toList().isNotEmpty()) {
                    list += predictToken(chatId, list.takeLast(window)).prediction
                    break
                }
            }
            println("Chosen prediction: ${list.last()}")
            println("Context: $list")
        } while (list.last() != MarkerToken.END)
        return list
    }

    abstract fun getConfigForChat(chatId: Long): Config
    abstract fun saveConfig(config: Config)

    companion object {
        /**
         * Maximum context window. CONTEXT_WINDOW tokens predict 1 token
         */
        const val CONTEXT_WINDOW = 5
    }
}