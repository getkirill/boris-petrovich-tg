package dev.kraskaska.boris

import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.getUpdates
import dev.inmo.tgbotapi.extensions.api.send.media.sendSticker
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.replyWithSticker
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.extensions.utils.*
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.from
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.text
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.flushAccumulatedUpdates
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.retrieveAccumulatedUpdates
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.startGettingOfUpdatesByLongPolling
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.message.MarkdownV2ParseMode
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import dev.inmo.tgbotapi.utils.PreviewFeature
import dev.inmo.tgbotapi.utils.RiskFeature
import kotlinx.coroutines.delay
import kotlin.random.Random

@OptIn(RiskFeature::class, PreviewFeature::class)
suspend fun <BC : BehaviourContext> BC.handleInteraction(db: Database, message: CommonMessage<MessageContent>) {
    println("Received message! $message")
    val hasStartingSlash = message.text?.trim()?.startsWith("/") == true
    val isFromBot = message.from?.botOrNull() != null
    val hasCommands =
        message.content.asTextContent()?.textSources?.any { it.botCommandTextSourceOrNull() != null } == true
    val inDirectMessages = message.chat.asPrivateChat() != null
    val hasMyGenerateCommand = message.content.asTextContent()?.textSources?.any { textSource ->
        textSource.botCommandTextSourceOrNull()
            ?.let { botCommandTextSource -> botCommandTextSource.command == "generate" && (botCommandTextSource.username == getMe().username || inDirectMessages) } == true
    } == true
    if (isFromBot || ((hasCommands || hasStartingSlash) && !hasMyGenerateCommand)) {
        println("Refusing to process; message starts with slash, contains commands or is from bot.")
        return
    }
    val passesChance = Random.nextInt(0, 12) < 1
    val inReplyToMe = message.replyTo?.from?.id == getMe().id
    val hasMentionOfMe =
        message.content.asTextContent()?.textSources?.any { it.mentionTextSourceOrNull()?.username == getMe().username } == true
    val shouldAcknowledge = inReplyToMe || hasMentionOfMe || inDirectMessages || hasMyGenerateCommand || passesChance
    // TODO: reintroduce message caching for training
    val tokens =
        /*db.recallMessageForTraining(message.chat)?.let { cached -> cached.content.dev.kraskaska.boris.tokenize(db).toList().takeLast(dev.kraskaska.boris.Database.CONTEXT_WINDOW) + message.content.dev.kraskaska.boris.tokenize(db) } ?: */
        message.content.tokenize(db)
    if (!hasCommands) db.updateAssociations(tokens)
    if (db.associationCount <= 0) {
        reply(message, "_Boris has no associations\\. Please say something\\!_", MarkdownV2ParseMode)
        return
    }
    if (!shouldAcknowledge) {
        println("Refusing to acknowledge; none of the conditions for responding are true")
//        println("passesChance: $passesChance")
//        println("isFromBot: $isFromBot")
//        println("inDirectMessages: $inDirectMessages")
//        println("inReplyToMe: $inReplyToMe")
//        println("hasCommands: $hasCommands")
//        println("hasMentionOfMe: $hasMentionOfMe")
//        println("hasMyGenerateCommand: $hasMyGenerateCommand")
        return
    }
    val replyInfo =
        if (inReplyToMe || hasMentionOfMe || hasMyGenerateCommand) ReplyParameters(
            message.metaInfo
        ) else null
    val prediction = db.predictUntilEnd(tokens + listOf(MarkerToken.START)).drop(tokens.toList().size)
    println("Final prediction: $prediction")
    if (prediction[1] is StickerToken) sendSticker(
        message.chat,
        (prediction[1] as StickerToken).sticker,
        replyParameters = replyInfo
    )
    else {
        val textualPrediction = prediction.joinToString(" ") { if (it is TextToken) it.text else "" }
        println("Final textual prediction: $textualPrediction")
        if (textualPrediction.length > 4096) {
            send(
                message.chat,
                "_Boris has attempted to send message longer than *4096* characters \\(*${textualPrediction.length}*\\)_",
                MarkdownV2ParseMode,
                replyParameters = replyInfo,
            )
//            println(textualPrediction)
        } else
            send(
                message.chat,
                textualPrediction,
                replyParameters = replyInfo
            )
    }
}

@OptIn(PreviewFeature::class)
suspend fun main() {
    PostgresDatabase().use { db ->
        val bot = telegramBot(System.getenv("TG_TOKEN"))
        // eldritch horrrors
        bot.getUpdates().lastOrNull()?.updateId?.let { bot.getUpdates(it + 1) }
        bot.buildBehaviourWithLongPolling() {
            println(getMe())

            onCommand("start") {
                reply(
                    it,
                    "Привет\\! Меня зовут Борис Петрович\\.\nЯ читаю сообщения и пытаюсь сгенерировать свои на основе известных\\!\n\n_Бот читает все сообщения которые он получает, а также может сохранять часть сообщений для допольнительного контекста во время обучения\\.\nНемедленно кикнете/заблокируйте бота если вы не согласны с этим\\._",
                    MarkdownV2ParseMode
                )
            }
            onContentMessage {
                handleInteraction(db, it)
            }
            onCommand("stats") {
                reply(
                    it, """
                Tokens known: ${db.tokenCount}
                Associations known: ${db.associationCount}
            """.trimIndent()
                )
            }
            onCommand("tokenize", false) {
                val text = it.replyTo?.contentMessageOrNull()?.content?.tokenize(db) ?: it.content.textSources.drop(1)
                    .flatMap { it.asText.tokenize(db) }
                if (it.content.textSources[0].botCommandTextSourceOrThrow().username != getMe().username && it.chat.privateChatOrNull() == null) return@onCommand
                println("/tokenize $text")
                if (text.any { it is TextToken || it is StickerToken }) reply(
                    it, text
                        .joinToString(" ") { token -> if (token is MarkerToken) "${token.id} (${token.type.name})" else "${token.id} (${token::class.simpleName})" })
                else reply(
                    it,
                    "_Result is empty\\!_\nPlease provide correct argument after the slash command, or reply to message with this command\\.",
                    MarkdownV2ParseMode
                )
            }
            onCommand("untokenize", false) {
//            println(it.content.textSources[0].botCommandTextSourceOrNull())
                if (it.content.textSources[0].botCommandTextSourceOrThrow().username != getMe().username && it.chat.privateChatOrNull() == null) return@onCommand
                val text = it.content.textSources.drop(1).joinToString(" ") { source -> source.asText }.trim()
                if (text.isBlank()) reply(
                    it,
                    "_Result is empty\\!_\nPlease provide correct argument after the slash command\\.",
                    MarkdownV2ParseMode
                )
//            println("/untokenize $text")
                val tokens = text.split(" ").mapNotNull { it.toLongOrNull() }.mapNotNull { db.getToken(it) }
                if (tokens[0] is StickerToken) replyWithSticker(it, (tokens[0] as StickerToken).sticker)
                else if (tokens[1] is StickerToken) replyWithSticker(it, (tokens[1] as StickerToken).sticker)
                else reply(
                    it,
                    tokens.joinToString(" ") { if (it is TextToken) it.text else if (it is StickerToken) "[sticker]" else if (it is MarkerToken) "[marker ${it.type.name}]" else "" })
            }
        }.join()
    }
}
