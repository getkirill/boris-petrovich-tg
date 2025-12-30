package dev.kraskaska.boris

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.chat.members.getChatMember
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
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
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.*
import dev.inmo.tgbotapi.extensions.utils.extensions.isAdministrator
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.document
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.from
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.message
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.reply_to_message
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.text
import dev.inmo.tgbotapi.extensions.utils.types.buttons.copyTextButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.flatInlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.chat.PreviewChat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.message.MarkdownV2ParseMode
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.message.content.MessageContent
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.utils.PreviewFeature
import dev.inmo.tgbotapi.utils.RiskFeature
import dev.inmo.tgbotapi.utils.row
import dev.kraskaska.boris.Database.Companion.CONTEXT_WINDOW
import dev.kraskaska.boris.migrations.runMigrations
import korlibs.time.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.datetime.Clock
import kotlinx.datetime.format.char
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.nio.file.Files
import kotlin.random.Random
import kotlin.time.DurationUnit
import kotlin.time.toDuration

val dateTimeFormat = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    dayOfMonth()

    char(' ')

    hour()
    char(':')
    minute()
    char(':')
    second()

    chars(" UTC")
}

@OptIn(RiskFeature::class, PreviewFeature::class)
suspend fun <BC : BehaviourContext> BC.handleInteraction(db: Database, message: CommonMessage<MessageContent>) {
    val config = db.getConfigForChat(message.chat.id.chatId.long)
    println("Received message! $message")
    val isSilenced = config.silenceUntil?.let { Clock.System.now() < it } == true
    val hasStartingSlash = message.text?.trim()?.startsWith("/") == true
    val isFromBot = message.from?.botOrNull() != null
    val hasCommands =
        message.content.asTextContent()?.textSources?.any { it.botCommandTextSourceOrNull() != null } == true
    val inDirectMessages = message.chat.asPrivateChat() != null
    val hasMyGenerateCommand = message.content.asTextContent()?.textSources?.any { textSource ->
        textSource.botCommandTextSourceOrNull()
            ?.let { botCommandTextSource -> botCommandTextSource.command == "generate" && (botCommandTextSource.username == getMe().username || inDirectMessages) } == true
    } == true
    if (((hasCommands || hasStartingSlash) && !hasMyGenerateCommand)) {
        println("Refusing to process; message starts with slash or contains commands.")
        return
    }
    val passesChance = Random.nextFloat() < config.generationChance
    val inReplyToMe = message.replyTo?.from?.id == getMe().id
    val hasMentionOfMe =
        message.content.asTextContent()?.textSources?.any { it.mentionTextSourceOrNull()?.username == getMe().username } == true
    val shouldAcknowledge =
        (inReplyToMe || hasMentionOfMe || inDirectMessages || hasMyGenerateCommand || (passesChance && !isSilenced)) && !isFromBot
    // TODO: reintroduce message caching for training
    val tokens =
        /*db.recallMessageForTraining(message.chat)?.let { cached -> cached.content.dev.kraskaska.boris.tokenize(db).toList().takeLast(dev.kraskaska.boris.Database.CONTEXT_WINDOW) + message.content.dev.kraskaska.boris.tokenize(db) } ?: */
        if (!hasCommands) (db.recallTokensForTraining(message.chat.id.chatId.long)
            ?: emptyList()) + message.content.tokenize(db) else emptyList()
    if (!hasCommands) db.updateAssociations(tokens, message.chat.id.chatId.long, config.contextWindow)
    if (!hasCommands) db.cacheTokensForTraining(message.chat.id.chatId.long, message.content.tokenize(db))
    if (!shouldAcknowledge) {
        println("Refusing to acknowledge; none of the conditions for responding are true")
        return
    }
    if (db.associationCountForChat(message.chat.id.chatId.long) <= 0) {
        reply(message, "_Boris has no associations\\. Please say something\\!_", MarkdownV2ParseMode)
        return
    }
    val replyInfo = if (inReplyToMe || hasMentionOfMe || hasMyGenerateCommand) ReplyParameters(
        message.metaInfo
    ) else null
    val predictStart = Clock.System.now()
    val prediction =
        db.predictUntilEnd(message.chat.id.chatId.long, tokens + listOf(MarkerToken.START), config.contextWindow).drop(tokens.toList().size)
    val time = Clock.System.now() - predictStart
    println("Final prediction: $prediction")
    db.cacheTokensForTraining(message.chat.id.chatId.long, prediction) // boris is now the last message
    if (prediction[1] is StickerToken) sendSticker(
        message.chat, (prediction[1] as StickerToken).sticker, replyParameters = replyInfo, replyMarkup = flatInlineKeyboard { copyTextButton("Generated in ${time.seconds} seconds", "${time.seconds}") }
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
            message.chat, textualPrediction, replyParameters = replyInfo, replyMarkup = flatInlineKeyboard { copyTextButton("Generated in ${time.seconds} seconds", "${time.seconds}") }
        )
    }
}

@OptIn(PreviewFeature::class)
suspend fun main(args: Array<String>) {
    PostgresDatabase().use { db ->
        val activeJobs = mutableMapOf<Long, Job>()
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
            onCommand("debugcached") {
                reply(
                    it,
                    db.recallTokensForTraining(it.chat.id.chatId.long)
                        ?.joinToString { if (it is TextToken) it.text else if (it is StickerToken) "[sticker]" else if (it is MarkerToken) "[marker ${it.type.name}]" else "" }
                        ?: "No cached message tokens")
            }
            onCommand("wipe") { message ->
                if (!(message.chat.isAdmin(bot, message.from!!))) {
                    reply(message, "Not administrator.")
                    return@onCommand
                }
                reply(
                    message, """
                    Are you sure you want to wipe ${db.associationCountForChat(message.chat.id.chatId.long)} associations?
                    This action cannot be undone.
                """.trimIndent(), replyMarkup = inlineKeyboard {
                        row {
                            dataButton("Wipe", "wipe")
                        }
                    })
            }
            onDataCallbackQuery("wipe") { dataCallbackQuery ->
                if (dataCallbackQuery.message?.reply_to_message?.from != dataCallbackQuery.from) {
                    answerCallbackQuery(
                        dataCallbackQuery,
                        "You are not the initiator of the /wipe command!",
                        showAlert = true
                    )
                    return@onDataCallbackQuery
                }
                db.wipeAssociationsForChat(dataCallbackQuery.message!!.chat.id.chatId.long)
                send(dataCallbackQuery.message!!.chat, "Chat associations have been wiped.")
                answerCallbackQuery(dataCallbackQuery, "Wiped associations.")
            }
            onCommand("stats") {
                reply(
                    it, """
                Tokens known: ${db.tokenCount}
                Associations known: ${db.associationCount}
                Associations known for chat ${it.chat.id.chatId.long}: ${db.associationCountForChat(it.chat.id.chatId.long)}
                Associations wiped: ${db.associationCountForChat(-1)}
                Dangling associations (pre-December 2025, no longer predictable): ${db.associationCountForChat(null)}
            """.trimIndent()
                )
            }
            onCommand("top") {
                val leaderboard = db.leaderboard(10)
                val thisChatLeaderboard = db.leaderboardPositionFor(it.chat.id.chatId.long)
                reply(
                    it, """
                    |Top 10 of chats by biggest amount of associations:
                    |${leaderboard.joinToString("\n") { "#${it.globalPosition} - [${if (it.chatId == 0L) "Dangling associations" else if (it.chatId == -1L) "Wiped associations" else it.chatId}] - ${it.count} associations" }}
                    |
                    |${if (thisChatLeaderboard.globalPosition != -1) "This chat (${thisChatLeaderboard.chatId}) position in leaderboard - #${thisChatLeaderboard.globalPosition} - ${thisChatLeaderboard.count} associations" else ""}
                """.trimMargin("|")
                )
            }
            onCommand("silence", false) { message ->
                if (!(message.chat.isAdmin(bot, message.from!!))) {
                    reply(message, "Not administrator.")
                    return@onCommand
                }
                val relatimeRegex =
                    """^((?<days>[0-9]+(\.[0-9]+)?)\s*da?y?s?)?\s*?((?<hours>[0-9]+(\.[0-9]+)?)\s*ho?u?r?s?)?\s*?((?<minutes>[0-9]+(\.[0-9]+)?)mi?n?u?t?e?s?)?\s*?((?<seconds>[0-9]+(\.[0-9]+)?)\s*se?c?o?n?d?s?)?$""".toRegex()
                val config = db.getConfigForChat(message.chat.id.chatId.long)
                if (message.content.textSources.size < 2) reply(
                    message, """
                        ${
                        if (config.silenceUntil?.let { Clock.System.now() < it } == true) "Silenced until ${
                            (config.silenceUntil as Instant).toLocalDateTime(
                                TimeZone.UTC
                            ).format(dateTimeFormat)
                        }" else if (config.generationChance <= 0f) "Generation chance is zero or less." else "Not silenced."
                    }
                    """.trimIndent(), replyMarkup = if (config.generationChance > 0) inlineKeyboard {
                        row { dataButton("+1 hour", "silence:hour") }
                        row { dataButton("+1 days", "silence:day") }
                        row { dataButton("+7 days", "silence:week") }
                        row { dataButton("Silence forever", "silence:forever") }
                    } else null)
                else {
                    val relatime =
                        relatimeRegex.find(message.content.textSources.drop(1).joinToString("") { it.asText }.trim())
                    println(message.content.textSources.drop(1).joinToString("") { it.asText }.trim())
                    println(relatime)
                    val duration = ((relatime?.groups["days"]?.value?.toFloat()
                        ?: 0f) * 24 * 60 * 60 + (relatime?.groups["hours"]?.value?.toFloat()
                        ?: 0f) * 60 * 60 + (relatime?.groups["minutes"]?.value?.toFloat()
                        ?: 0f) * 60 + (relatime?.groups["seconds"]?.value?.toFloat() ?: 0f)).toInt().toDuration(
                        DurationUnit.SECONDS
                    )
                    println("Silencing for $duration seconds")
                    if (config.silenceUntil == null || Clock.System.now() >= config.silenceUntil!!) {
                        config.silenceUntil = Clock.System.now() + duration
                    } else {
                        config.silenceUntil = config.silenceUntil!! + duration
                    }
                    reply(
                        message, "Silenced until ${
                            (config.silenceUntil as Instant).toLocalDateTime(
                                TimeZone.UTC
                            ).format(dateTimeFormat)
                        }"
                    )
                    db.saveConfig(config)
                }
            }
            onCommand("unsilence") { message ->
                if (!(message.chat.ifPublicChat {
                        bot.getChatMember(
                            it,
                            message.from!!
                        ).isAdministrator
                    } ?: true)) {
                    reply(message, "Cannot unsilence - not administrator.")
                    return@onCommand
                }
                val config = db.getConfigForChat(message.chat.id.chatId.long)
                config.silenceUntil = null
                db.saveConfig(config)
                if (config.generationChance <= 0) {
                    reply(
                        message,
                        "Generation chance is zero or less. Use /chance@boris_petrovich_bot to configure it."
                    )
                } else {
                    reply(message, "Unsilenced.")
                }
            }
            onDataCallbackQuery("silence:hour") { dataCallbackQuery ->
                val config = db.getConfigForChat(dataCallbackQuery.message!!.chat.id.chatId.long)
                if (!(dataCallbackQuery.message!!.chat.isAdmin(bot, dataCallbackQuery.from))) {
                    answerCallbackQuery(dataCallbackQuery, "Cannot silence - not administrator.", showAlert = true)
                    return@onDataCallbackQuery
                }
                if (config.silenceUntil == null || Clock.System.now() >= config.silenceUntil!!) {
                    config.silenceUntil = Clock.System.now() + 1.toDuration(DurationUnit.HOURS)
                } else {
                    config.silenceUntil = config.silenceUntil!! + 1.toDuration(DurationUnit.HOURS)
                }
                db.saveConfig(config)
                answerCallbackQuery(dataCallbackQuery, "Silenced until ${config.silenceUntil}")
                bot.editMessageText(
                    dataCallbackQuery.message!! as ContentMessage<TextContent>, text = "Silenced until ${
                        (config.silenceUntil as Instant).toLocalDateTime(
                            TimeZone.UTC
                        ).format(dateTimeFormat)
                    }"
                )
            }
            onDataCallbackQuery("silence:day") { dataCallbackQuery ->
                val config = db.getConfigForChat(dataCallbackQuery.message!!.chat.id.chatId.long)
                if (!(dataCallbackQuery.message!!.chat.isAdmin(bot, dataCallbackQuery.from))) {
                    answerCallbackQuery(dataCallbackQuery, "Cannot silence - not administrator.", showAlert = true)
                    return@onDataCallbackQuery
                }
                if (config.silenceUntil == null || Clock.System.now() >= config.silenceUntil!!) {
                    config.silenceUntil = Clock.System.now() + 1.toDuration(DurationUnit.DAYS)
                } else {
                    config.silenceUntil = config.silenceUntil!! + 1.toDuration(DurationUnit.DAYS)
                }
                db.saveConfig(config)
                answerCallbackQuery(dataCallbackQuery, "Silenced until ${config.silenceUntil}")
                bot.editMessageText(
                    dataCallbackQuery.message!! as ContentMessage<TextContent>, text = "Silenced until ${
                        (config.silenceUntil as Instant).toLocalDateTime(
                            TimeZone.UTC
                        ).format(dateTimeFormat)
                    }"
                )
            }
            onDataCallbackQuery("silence:week") { dataCallbackQuery ->
                val config = db.getConfigForChat(dataCallbackQuery.message!!.chat.id.chatId.long)
                if (!(dataCallbackQuery.message!!.chat.isAdmin(bot, dataCallbackQuery.from))) {
                    answerCallbackQuery(dataCallbackQuery, "Cannot silence - not administrator.", showAlert = true)
                    return@onDataCallbackQuery
                }
                if (config.silenceUntil == null || Clock.System.now() >= config.silenceUntil!!) {
                    config.silenceUntil = Clock.System.now() + 7.toDuration(DurationUnit.DAYS)
                } else {
                    config.silenceUntil = config.silenceUntil!! + 7.toDuration(DurationUnit.DAYS)
                }
                db.saveConfig(config)
                answerCallbackQuery(dataCallbackQuery, "Silenced until ${config.silenceUntil}")
                bot.editMessageText(
                    dataCallbackQuery.message!! as ContentMessage<TextContent>, text = "Silenced until ${
                        (config.silenceUntil as Instant).toLocalDateTime(
                            TimeZone.UTC
                        ).format(dateTimeFormat)
                    }"
                )
            }
            onDataCallbackQuery("silence:forever") { dataCallbackQuery ->
                val config = db.getConfigForChat(dataCallbackQuery.message!!.chat.id.chatId.long)
                if (!(dataCallbackQuery.message!!.chat.isAdmin(bot, dataCallbackQuery.from))) {
                    answerCallbackQuery(dataCallbackQuery, "Cannot silence - not administrator.", showAlert = true)
                    return@onDataCallbackQuery
                }
                config.generationChance = 0f;
                db.saveConfig(config)
                answerCallbackQuery(dataCallbackQuery, "Silenced forever - chance reduced to 0.")
                reply(dataCallbackQuery.message!!, "Silenced forever - chance reduced to 0.")
            }
            onCommand("contextwindow", false) { message ->
                if(!(message.chat.isAdmin(bot, message.from!!))) {
                    reply(message, "Not administrator.")
                    return@onCommand
                }
                val config = db.getConfigForChat(message.chat.id.chatId.long)
                if (message.content.textSources.size < 2) {
                    reply(
                        message, """
                        Context window: ${config.contextWindow}
                    """.trimIndent()
                    )
                    return@onCommand
                }
                val arg = message.content.textSources[1].asText.trim().toIntOrNull()
                if(arg == null) {
                    reply(message, "Failed to interpret argument as 32 bit integer.")
                    return@onCommand
                }
                config.contextWindow = arg.coerceIn(1, 25)
                db.saveConfig(config)
                reply(message, "Context window set to $arg")
            }
            onCommand("debugingest") { message ->
                var config = db.getConfigForChat(message.chat.id.chatId.long)
                if(message.reply_to_message == null) {
                    reply(message, "Send this command as a reply to a bin file")
                    return@onCommand
                }
                if(message.reply_to_message!!.document == null) {
                    reply(message, "Reply does not contain a document")
                    return@onCommand
                }
                if(message.reply_to_message!!.document!!.fileName?.endsWith(".bin") != true) {
                    reply(message, "Not a binary")
                    return@onCommand
                }
                val tempFile = Files.createTempFile("boris_ingestdata_", ".bin").toFile()
                val replyMarkup = inlineKeyboard {
                    row {
                        dataButton("Cancel ingestion", "cancelingest")
                    }
                }
                val mesg = reply(message, "Downloading data...", replyMarkup = replyMarkup)
                bot.downloadFile(message.reply_to_message!!.document!!, tempFile)
                bot.editMessageText(mesg, "Data downloaded. Beginning ingestion.", replyMarkup = replyMarkup)
                activeJobs[message.chat.id.chatId.long] = launch(Dispatchers.IO) {
                    println("Starting coroutine...")
                    val start = Clock.System.now()
                    var processed = 0
                    var nextTarget = 5
                    var lastStr: String? = null
                    suspend fun ingest(str: String) {
//                        println("Ingesting...")
                        val tokens = (lastStr?.tokenize(db) ?: emptyList()) + str.tokenize(db)
                        db.updateAssociations(tokens, message.chat.id.chatId.long, config.contextWindow)
                        processed++
                        if(processed % 100 == 0) {
                            config = db.getConfigForChat(message.chat.id.chatId.long)
                            bot.editMessageText(mesg, "Ingested $processed messages\nSpeed: ${
                                processed / (Clock.System.now() - start).seconds
                            } messsages/second", replyMarkup = replyMarkup)
                        }
                        lastStr = str
                    }
                    FileInputStream(tempFile).use {
                        BufferedInputStream(it).use {
                            println("Reading...")
                            val buf = ByteArray(4096)
                            var leftover = ByteArray(0)
                            var bytesRead: Int
                            while (it.read(buf).also { bytesRead = it } != -1) {
                                println("Read $bytesRead")
                                val data = leftover + buf.copyOf(bytesRead)
                                var start = 0
                                for (i in data.indices) {
                                    if (data[i] == 0.toByte()) {
                                        val str = data.copyOfRange(start, i).toString(Charsets.UTF_8)
                                        ingest(str)
                                        start = i + 1
                                    }
                                }
                                leftover = if (start < data.size) data.copyOfRange(start, data.size) else ByteArray(0)
                            }

                            // handle any remaining bytes
                            if (leftover.isNotEmpty()) {
                                val str = leftover.toString(Charsets.UTF_8)
                                ingest(str)
                            }
                        }
                    }
                    send(mesg.chat, "Ingestion finished. Total processed: $processed")
                    editMessageText(mesg, "Ingestion finished. Total processed: $processed")
                }
            }
            onDataCallbackQuery("cancelingest") { dataCallbackQuery ->
                val chat = dataCallbackQuery.message!!.chat.id.chatId.long
                activeJobs[chat]?.cancel()
                activeJobs.remove(chat)
                editMessageText(
                    dataCallbackQuery.message!! as ContentMessage<TextContent>, "Job cancelled.",
                )
            }
            onCommand("chance", false) { message ->
                val config = db.getConfigForChat(message.chat.id.chatId.long)
                if (message.content.textSources.size < 2) reply(
                    message, """
                        Configured chance: random < ${config.generationChance} (${
                        String.format(
                            "%.2f", config.generationChance * 100
                        )
                    }%)
                        Use /chance@boris_petrovich_bot <decimal> or /chance@boris_petrovich_bot <percentage>% or /chance@boris_petrovich_bot <decimal>/<decimal> to configure.
                    """.trimIndent()
                )
                else {
                    if (!(message.chat.isAdmin(bot, message.from!!))) {
                        reply(message, "Not administrator.")
                        return@onCommand
                    }
                    val arg = message.content.textSources[1].asText.trim()
                    print(arg)
                    if (arg.matches("""[0-9]+(\.[0-9]+)?%""".toRegex())) {
                        config.generationChance = arg.substringBeforeLast("%").toFloat() / 100
                    } else if (arg.matches("""[0-9]+(\.[0-9]+)?/[0-9]+(\.[0-9]+)?""".toRegex())) {
                        config.generationChance = arg.split("/").let { it[0].toFloat() / it[1].toFloat() }
                    } else if (arg.toFloatOrNull() != null) {
                        config.generationChance = arg.toFloat()
                    } else {
                        reply(message, "Could not parse argument, configuration left unchanged.")
                        return@onCommand
                    }
                    db.saveConfig(config)
                    reply(
                        message, """Configured chance: random < ${config.generationChance} (${
                            String.format(
                                "%.2f", config.generationChance * 100
                            )
                        }%)"""
                    )
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
                val config = db.getConfigForChat(it.chat.id.chatId.long)
//            println(it.content.textSources[0].botCommandTextSourceOrNull())
                val contextWindow = config.contextWindow
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
                (1..contextWindow.coerceAtMost(tokens.count())).forEach { window ->
                    s.appendLine()
                    s.appendLine("Window $window:")
                    db.possiblePredictions(it.chat.id.chatId.long, tokens.takeLast(window)).let { predictions ->
                        totalPredictions += predictions.count()
                        predictions.forEach { prediction ->
                            s.appendLine("${prediction.prediction.id} - ${if (prediction.prediction is MarkerToken) "[marker ${prediction.prediction.type.name}]" else if (prediction.prediction is StickerToken) "[sticker ${prediction.prediction.id}]" else if (prediction.prediction is TextToken) prediction.prediction.text else "${prediction.prediction.id}"}")
                        }
                        val totalCount = predictions.sumOf { it.count }
                        val worstPrediction = predictions.minByOrNull { it.count }
                        val bestPrediction = predictions.maxByOrNull { it.count }
                        val median = predictions.firstOrNull {
                            (((worstPrediction?.count ?: 0)+ (bestPrediction?.count ?: 0)) / 2).let { median -> it.count == median || it.count - 1 == median || it.count + 1 == median }
                        }
                        if(worstPrediction != null)s.appendLine(
                            "Worst chance - ${worstPrediction.prediction.id} - ${
                                String.format(
                                    "%.2f", worstPrediction.count.toDouble() / totalCount * 100
                                )
                            }%"
                        )
                        if(bestPrediction != null)s.appendLine(
                            "Best chance - ${bestPrediction.prediction.id} - ${
                                String.format(
                                    "%.2f", bestPrediction.count.toDouble() / totalCount * 100
                                )
                            }%"
                        )
                        if (median != null) s.appendLine(
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
                            contextWindow.coerceAtMost(
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

suspend fun PreviewChat.isAdmin(bot: TelegramBot, member: User) = this.ifPublicChat {
    bot.getChatMember(
        it,
        member
    ).isAdministrator
} ?: true