package dev.kraskaska.boris

import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.getUpdates
import dev.inmo.tgbotapi.extensions.api.send.media.sendDocument
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
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.message.MarkdownV2ParseMode
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import dev.inmo.tgbotapi.utils.PreviewFeature
import dev.inmo.tgbotapi.utils.RiskFeature
import dev.kraskaska.boris.Database.Companion.CONTEXT_WINDOW
import dev.kraskaska.boris.migrations.runMigrations
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlin.random.Random


@OptIn(RiskFeature::class, PreviewFeature::class)
suspend fun <BC : BehaviourContext> BC.handleInteraction(db: Database, message: CommonMessage<MessageContent>) {
    val config = db.getConfigForChat(message.chat.id.chatId.long)
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
    val passesChance = Random.nextFloat() < config.generationChance
    val inReplyToMe = message.replyTo?.from?.id == getMe().id
    val hasMentionOfMe =
        message.content.asTextContent()?.textSources?.any { it.mentionTextSourceOrNull()?.username == getMe().username } == true
    val shouldAcknowledge = inReplyToMe || hasMentionOfMe || inDirectMessages || hasMyGenerateCommand || passesChance
    // TODO: reintroduce message caching for training
    val tokens =
        /*db.recallMessageForTraining(message.chat)?.let { cached -> cached.content.dev.kraskaska.boris.tokenize(db).toList().takeLast(dev.kraskaska.boris.Database.CONTEXT_WINDOW) + message.content.dev.kraskaska.boris.tokenize(db) } ?: */
        if (!hasCommands) message.content.tokenize(db) else emptyList()
    if (!hasCommands) db.updateAssociations(tokens, message.chat.id.chatId.long)
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
    val replyInfo = if (inReplyToMe || hasMentionOfMe || hasMyGenerateCommand) ReplyParameters(
        message.metaInfo
    ) else null
    val prediction =
        db.predictUntilEnd(message.chat.id.chatId.long, tokens + listOf(MarkerToken.START)).drop(tokens.toList().size)
    println("Final prediction: $prediction")
    if (prediction[1] is StickerToken) sendSticker(
        message.chat, (prediction[1] as StickerToken).sticker, replyParameters = replyInfo
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
        } else send(
            message.chat, textualPrediction, replyParameters = replyInfo
        )
    }
}

@OptIn(PreviewFeature::class)
suspend fun main(args: Array<String>) {
    PostgresDatabase().use { db ->
        if (args.firstOrNull()?.lowercase() == "migrate") {
            runMigrations(db.conn)
            return@use
        }
        val bot = telegramBot(System.getenv("TG_TOKEN"))
        // eldritch horrrors
        bot.getUpdates().lastOrNull()?.updateId?.let { bot.getUpdates(it + 1) }
        bot.buildBehaviourWithLongPolling() {
            println(getMe())

            onCommand("start") {
                reply(
                    it,
                    "Привет\\! Меня зовут Борис Петрович\\.\nЯ читаю сообщения и пытаюсь сгенерировать свои на основе известных\\!\n\n_Бот читает все сообщения которые он получает, воспроизводит их, а также может сохранять часть сообщений для допольнительного контекста во время обучения\\. \nНемедленно кикнете/заблокируйте бота если вы не согласны с этим\\._",
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
                Associations known (for chat ${it.chat.id.chatId.long}): ${db.associationCountForChat(it.chat.id.chatId.long)}
                Dangling associations (pre-December 2025, no longer predictable): ${db.associationCountForChat(null)}
            """.trimIndent()
                )
            }
            onCommand("chance", false) {
                val config = db.getConfigForChat(it.chat.id.chatId.long)
                if (it.content.textSources.size < 2)
                    reply(
                        it, """
                        Configured chance: random < ${config.generationChance} (${
                            String.format(
                                "%.2f", config.generationChance * 100
                            )
                        }%)
                        Use /chance@boris_petrovich_bot <decimal> or /chance@boris_petrovich_bot <percentage>% or /chance@boris_petrovich_bot <decimal>/<decimal> to configure.
                    """.trimIndent()
                    )
                else {
                    val arg = it.content.textSources[1].asText.trim()
                    print(arg)
                    if (arg.matches("""[0-9]+(\.[0-9]+)?%""".toRegex())) {
                        config.generationChance = arg.substringBeforeLast("%").toFloat() / 100
                    } else if (arg.matches("""[0-9]+(\.[0-9]+)?/[0-9]+(\.[0-9]+)?""".toRegex())) {
                        config.generationChance = arg.split("/").let { it[0].toFloat() / it[1].toFloat() }
                    } else if (arg.toFloatOrNull() != null) {
                        config.generationChance = arg.toFloat()
                    } else {
                        reply(it, "Could not parse argument, configuration left unchanged.")
                        return@onCommand
                    }
                    db.saveConfig(config)
                    reply(it, """Configured chance: random < ${config.generationChance} (${
                        String.format(
                            "%.2f", config.generationChance * 100
                        )
                    }%)""")
                }
            }
            onCommand("tokenize", false) {
                val text = it.replyTo?.contentMessageOrNull()?.content?.tokenize(db) ?: it.content.textSources.drop(1)
                    .flatMap { it.asText.tokenize(db) }
                if (it.content.textSources[0].botCommandTextSourceOrThrow().username != getMe().username && it.chat.privateChatOrNull() == null) return@onCommand
                println("/tokenize $text")
                if (text.any { it is TextToken || it is StickerToken }) reply(
                    it,
                    text.joinToString(" ") { token -> if (token is MarkerToken) "${token.id} (${token.type.name})" else "${token.id}" })
                else reply(
                    it,
                    "_No valid argument\\!_\nPlease provide correct argument after the slash command, or reply to message with this command\\.",
                    MarkdownV2ParseMode
                )
            }
            onCommand("untokenize", false) {
//            println(it.content.textSources[0].botCommandTextSourceOrNull())
                if (it.content.textSources[0].botCommandTextSourceOrThrow().username != getMe().username && it.chat.privateChatOrNull() == null) return@onCommand
                val text = it.content.textSources.drop(1).joinToString(" ") { source -> source.asText }.trim()
                if (text.isBlank()) {
                    reply(
                        it,
                        "_No valid argument\\!_\nPlease provide correct argument after the slash command\\.",
                        MarkdownV2ParseMode
                    )
                    return@onCommand
                }
//            println("/untokenize $text")
                val tokens = text.split(" ").mapNotNull { it.toLongOrNull() }.mapNotNull { db.getToken(it) }
                if (tokens[0] is StickerToken) replyWithSticker(it, (tokens[0] as StickerToken).sticker)
                else if (tokens[1] is StickerToken) replyWithSticker(it, (tokens[1] as StickerToken).sticker)
                else reply(
                    it,
                    tokens.joinToString(" ") { if (it is TextToken) it.text else if (it is StickerToken) "[sticker]" else if (it is MarkerToken) "[marker ${it.type.name}]" else "" })
            }
            onCommand("predict", false) {
//            println(it.content.textSources[0].botCommandTextSourceOrNull())
                if (it.content.textSources[0].botCommandTextSourceOrThrow().username != getMe().username && it.chat.privateChatOrNull() == null) return@onCommand
                val text = it.content.textSources.drop(1).joinToString(" ") { source -> source.asText }.trim()
                if (text.isBlank()) reply(
                    it,
                    "_No valid argument\\!_\nPlease provide correct argument after the slash command\\.",
                    MarkdownV2ParseMode
                )
//            println("/untokenize $text")
                val tokens = text.split(" ").mapNotNull { it.toLongOrNull() }.mapNotNull { db.getToken(it) }
                val s = StringBuilder()
                var totalPredictions = 0
                s.appendLine("Possible predictions for given context:")
                (1..CONTEXT_WINDOW.coerceAtMost(tokens.count())).forEach { window ->
                    s.appendLine()
                    s.appendLine("Window $window:")
                    db.possiblePredictions(it.chat.id.chatId.long, tokens.takeLast(window)).let { predictions ->
                        totalPredictions += predictions.count()
                        predictions.forEach { prediction ->
                            s.appendLine("${prediction.prediction.id} - ${prediction.prediction}")
                        }
                        val totalCount = predictions.sumOf { it.count }
                        val worstPrediction = predictions.minBy { it.count }
                        val bestPrediction = predictions.maxBy { it.count }
                        val median = predictions.first {
                            ((worstPrediction.count + bestPrediction.count) / 2).let { median -> it.count == median || it.count - 1 == median || it.count + 1 == median }
                        }
                        s.appendLine(
                            "Worst chance - ${worstPrediction.prediction.id} - ${
                                String.format(
                                    "%.2f", worstPrediction.count.toDouble() / totalCount * 100
                                )
                            }%"
                        )
                        s.appendLine(
                            "Best chance - ${bestPrediction.prediction.id} - ${
                                String.format(
                                    "%.2f", bestPrediction.count.toDouble() / totalCount * 100
                                )
                            }%"
                        )
                        s.appendLine(
                            "Median chance - ${median.prediction.id} - ${
                                String.format(
                                    "%.2f", median.count.toDouble() / totalCount * 100
                                )
                            }%"
                        )
                    }
                }
                s.appendLine()
                s.appendLine("Total predictions: $totalPredictions")
                val final = s.toString()
                if (final.length > 4096) {
                    reply(
                        it,
                        "Too many predictions to fit in 4096 characters. There are $totalPredictions possible predictions (context windows 1-${
                            CONTEXT_WINDOW.coerceAtMost(
                                tokens.count()
                            )
                        }). Full excerpt will be sent as file as soon as possible."
                    )
                    try {
                        sendDocument(
                            it.chat,
                            InputFile.fromInput("predictions.txt") { final.byteInputStream().asSource().buffered() })
                    } catch (e: Throwable) {
                        reply(it, "There was an error in sending the document.")
                        e.printStackTrace()
                    }
                } else {
                    reply(it, final)
                }
            }
            onCommand("unpredict", false) {
//            println(it.content.textSources[0].botCommandTextSourceOrNull())
                if (it.content.textSources[0].botCommandTextSourceOrThrow().username != getMe().username && it.chat.privateChatOrNull() == null) return@onCommand
                val text = it.content.textSources.drop(1).first().asText.trim()
                if (text.isBlank()) reply(
                    it,
                    "_No valid argument\\!_\nPlease provide correct argument after the slash command\\.",
                    MarkdownV2ParseMode
                )
                println("/unpredict $text")
                val prediction = text.toLongOrNull()?.let { db.getToken(it) }!!
                val s = StringBuilder()
                var totalPredictions = 0
                s.appendLine("Possible contexts for given prediction:")
                db.possibleContexts(it.chat.id.chatId.long, prediction).let { associations ->
                    totalPredictions += associations.count()
                    associations.forEach { prediction ->
                        s.appendLine(prediction.context.joinToString(" ") { token -> if (token is MarkerToken) "[marker ${token.type.name}]" else if (token is StickerToken) "[sticker ${token.id}]" else if (token is TextToken) token.text else "${token.id}" } + " (${prediction.count})")
                    }
                    val totalCount = associations.sumOf { it.count }
                    val worstPrediction = associations.minBy { it.count }
                    val bestPrediction = associations.maxBy { it.count }
                    s.appendLine(
                        "Worst chance - ${worstPrediction.context.joinToString(" ") { token -> if (token is MarkerToken) "[marker ${token.type.name}]" else if (token is StickerToken) "[sticker ${token.id}]" else if (token is TextToken) token.text else "${token.id}" }} - ${
                            String.format(
                                "%.2f", worstPrediction.count.toDouble() / totalCount * 100
                            )
                        }%"
                    )
                    s.appendLine(
                        "Best chance - ${bestPrediction.context.joinToString(" ") { token -> if (token is MarkerToken) "[marker ${token.type.name}]" else if (token is StickerToken) "[sticker ${token.id}]" else if (token is TextToken) token.text else "${token.id}" }} - ${
                            String.format(
                                "%.2f", bestPrediction.count.toDouble() / totalCount * 100
                            )
                        }%"
                    )
                }

                s.appendLine()
                s.appendLine("Total contexts: $totalPredictions")
                val final = s.toString()
                if (final.length > 4096) {
                    reply(
                        it,
                        "Too many contexts to fit in 4096 characters. There are $totalPredictions possible contexts. Full excerpt will be sent as file as soon as possible."
                    )
                    try {
                        sendDocument(
                            it.chat,
                            InputFile.fromInput("contexts.txt") { final.byteInputStream().asSource().buffered() })
                    } catch (e: Throwable) {
                        reply(it, "There was an error in sending the document.")
                        e.printStackTrace()
                    }
                } else {
                    reply(it, final)
                }
            }
        }.join()
    }
}