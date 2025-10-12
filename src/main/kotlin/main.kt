import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.send.media.sendSticker
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.extensions.utils.*
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.from
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.text
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.flushAccumulatedUpdates
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.message.MarkdownV2ParseMode
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import dev.inmo.tgbotapi.utils.PreviewFeature
import dev.inmo.tgbotapi.utils.RiskFeature
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
    if (hasStartingSlash || isFromBot || (hasCommands && !hasMyGenerateCommand)) {
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
        /*db.recallMessageForTraining(message.chat)?.let { cached -> cached.content.tokenize(db).toList().takeLast(Database.CONTEXT_WINDOW) + message.content.tokenize(db) } ?: */
        message.content.tokenize(db)
    db.updateAssociations(tokens)
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

suspend fun main() {
//    val sqdb = SQLiteDatabase(File("test.sqlite"))
//    sqdb.associationCount
    val db = InMemoryDatabase()
    val bot = telegramBot(System.getenv("TG_TOKEN"))

    bot.buildBehaviourWithLongPolling {
        flushAccumulatedUpdates()
        println(getMe())

        onCommand("start") {
            reply(
                it,
                "Привет\\! Меня зовут Борис Петрович\\.\nЯ читаю сообщения и пытаюсь сгенерировать свои на основе известных\\!\n\n_Бот читает все сообщения которые он получает, а также может сохранять часть сообщений для допольнительного контекста во время обучения\\.\nНемедленно кикнете/заблокируйте бота если вы не согласны с этим\\._",
                MarkdownV2ParseMode
            )
        }
        onContentMessage {
//            if (it.content.text.startsWith("/") == true) return@onText
//            val tokens = it.content.tokenize(db)
//            db.updateAssociations(tokens)
//            val prediction = db.predictUntilEnd(listOf(MarkerToken.START))
//            if (prediction[1] is StickerToken) sendSticker(
//                it.chat,
//                (prediction[1] as StickerToken).sticker,
//                replyParameters = if (it.replyTo?.optionallyFromUserMessageOrNull()?.from?.id == getMe().id) ReplyParameters(
//                    it.metaInfo
//                ) else null
//            )
//            else send(
//                it.chat,
//                prediction.joinToString(" ") { if (it is TextToken) it.text else "" },
//                replyParameters = if (it.replyTo?.optionallyFromUserMessageOrNull()?.from?.id == getMe().id) ReplyParameters(
//                    it.metaInfo
//                ) else null
//            )
            handleInteraction(db, it)
        }
//        onSticker {
////            val tokens = it.content.tokenize(db)
////            db.updateAssociations(tokens)
////            val prediction = db.predictUntilEnd(listOf(MarkerToken.START))
////            if (prediction[1] is StickerToken) sendSticker(
////                it.chat,
////                (prediction[1] as StickerToken).sticker,
////                replyParameters = if (it.replyTo?.optionallyFromUserMessageOrNull()?.from?.id == getMe().id) ReplyParameters(
////                    it.metaInfo
////                ) else null
////            )
////            else send(
////                it.chat,
////                prediction.joinToString(" ") { if (it is TextToken) it.text else "" },
////                replyParameters = if (it.replyTo?.optionallyFromUserMessageOrNull()?.from?.id == getMe().id) ReplyParameters(
////                    it.metaInfo
////                ) else null
////            )
//            handleInteraction(db, it)
//        }
        onCommand("stats") {
            reply(
                it, """
                Tokens known: ${db.tokenCount}
                Associations known: ${db.associationCount}
            """.trimIndent()
            )
        }
//        onCommand("generate") {
////            if (it.from?.botOrNull() != null) return@onCommand
////
////            val prediction = db.predictUntilEnd(listOf(MarkerToken.START))
////            if (prediction[1] is StickerToken) sendSticker(
////                it.chat,
////                (prediction[1] as StickerToken).sticker,
////                replyParameters = if (it.replyTo?.optionallyFromUserMessageOrNull()?.from?.id == getMe().id) ReplyParameters(
////                    it.metaInfo
////                ) else null
////            )
////            else send(
////                it.chat,
////                prediction.joinToString(" ") { if (it is TextToken) it.text else "" },
////                replyParameters = if (it.replyTo?.optionallyFromUserMessageOrNull()?.from?.id == getMe().id) ReplyParameters(
////                    it.metaInfo
////                ) else null
////            )
//            handleInteraction(db, it)
//        }
//        retrieveAccumulatedUpdates(this).join()
    }.join()
}