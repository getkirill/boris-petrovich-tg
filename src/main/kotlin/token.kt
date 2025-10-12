import dev.inmo.tgbotapi.extensions.utils.extensions.raw.file_id
import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.types.message.content.MessageContent
import dev.inmo.tgbotapi.types.message.content.StickerContent
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlin.random.Random

sealed class Token {
    abstract val id: Long
    override fun equals(other: Any?): Boolean {
        return other is Token && id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

class MarkerToken(override val id: Long, val type: MarkerType) : Token() {
    enum class MarkerType {
        START, END
    }

    companion object {
        val START = MarkerToken(1, MarkerType.START)
        val END = MarkerToken(2, MarkerType.END)
    }

    override fun toString(): String {
        return "MarkerToken(id=$id, type=$type)"
    }

}

class TextToken(override val id: Long, val text: String) : Token() {
    override fun toString(): String {
        return "TextToken(id=$id, text='$text')"
    }
}
class StickerToken(override val id: Long, val sticker: FileId) : Token() {
    override fun toString(): String {
        return "StickerToken(id=$id, sticker=$sticker)"
    }
}

//fun Iterable<Token>.collectIntoTelegramMessage(): RegularTextSource {
//    val list = toMutableList()
//    println(list)
//    if (list.first() is MarkerToken && (list.first() as MarkerToken).type == MarkerToken.MarkerType.START) list.removeFirst()
//    if (list.last() is MarkerToken && (list.last() as MarkerToken).type == MarkerToken.MarkerType.END) list.removeLast()
//    println(list)
//    println(list.joinToString { if (it is TextToken) it.text else "" })
////    if(list.any { it is StickerToken }) {
////        if(list.size > 1)
////            throw IllegalStateException("Sticker token ${list.first { it is StickerToken }.id} must be on its own!")
////        val sticker = list.first { it is StickerToken } as StickerToken
////    }
//    val txt = list.joinToString { if (it is TextToken) it.text else "" }
//    return RegularTextSource(txt)
//}

fun MessageContent.tokenize(db: Database): Iterable<Token> {
    if(this is TextContent) {
        return this.text.tokenize(db)
    }
    if(this is StickerContent) {
        return this.media.file_id.tokenize(db)
    }
    return emptyList()
}

fun String.tokenize(db: Database): Iterable<Token> {
    return listOf(MarkerToken.START) + split(Regex(" +")).map { db.findOrMakeTextTokenFor(it) } + listOf(MarkerToken.END)
}
fun FileId.tokenize(db: Database): Iterable<Token> {
    return listOf(MarkerToken.START, db.findOrMakeStickerTokenFor(this), MarkerToken.END)
}

class Association(val context: List<Token>, val prediction: Token, var count: Long) {
    override fun toString(): String {
        return "Association(context=$context, prediction=$prediction, count=$count)"
    }

    override fun equals(other: Any?): Boolean {
        return other is Association && context == other.context && prediction == other.prediction && count == other.count
    }

    override fun hashCode(): Int {
        var result = count.hashCode()
        result = 31 * result + context.hashCode()
        result = 31 * result + prediction.hashCode()
        return result
    }
}

fun Iterable<Association>.weightedRandom(): Association {
    val total = sumOf { it.count }
    var currentWeightSum = 0L
    val random = Random.nextLong(total)
    forEach {
        currentWeightSum += it.count
        if (currentWeightSum > random) {
            return it
        }
    }
    TODO()
}