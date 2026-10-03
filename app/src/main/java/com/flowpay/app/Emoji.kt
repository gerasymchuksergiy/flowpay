package com.flowpay.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * An emoji for every payment and purchase, picked from its name.
 *
 * The owner asked for them on 3 October 2026: «інтернет — то браузер емодзі, якщо
 * телефон — то трубка». A row of identical grey icons was most of what made the
 * screens read as monotonous, and a name is all a payment carries, so the name is
 * what decides. A person can always pick another one; the choice is stored on the
 * payment or the purchase ([Pay.emoji], [Order.emoji]) and wins over the guess.
 *
 * **The pictures.** The owner chose Apple's emoji. Those are Apple's artwork, and
 * this repository and every APK built from it are public, so the pictures are not
 * in either. The owner imports them once from a file of his own (Налаштування →
 * «Емодзі Apple»), they live in the app's private storage, and until then — or on
 * any other phone — the phone's own emoji font draws the same characters. See
 * [EmojiPack].
 */

// ------------------------------------------------------------ choosing one

/**
 * Words that give a payment away, most specific first.
 *
 * Order is the whole design: "YouTube Music" is music before it is YouTube, and
 * "Київстар ТБ" is television before it is a phone bill. A word matches at the
 * start of a word only, so «газ» does not find the gas bill inside «магазин».
 */
private val PAY_RULES: List<Pair<String, List<String>>> = listOf(
    "🎧" to listOf("youtube music", "spotify", "apple music", "deezer", "музик"),
    "📺" to listOf("youtube", "megogo", "мегого", "sweet.tv", "sweet tv", "oll.tv", "тб", "телебач"),
    "🍿" to listOf("netflix", "disney", "hbo", "prime video", "кіно"),
    "🤖" to listOf("chatgpt", "openai", "claude", "gemini", "copilot", "midjourney", "perplexity"),
    "☁️" to listOf("icloud", "google one", "dropbox", "onedrive", "хмар"),
    "🎨" to listOf("adobe", "figma", "canva", "photoshop", "lightroom"),
    "🎮" to listOf("playstation", "ps plus", "xbox", "game pass", "steam", "nintendo", "ігр"),
    "🌐" to listOf("інтернет", "internet", "wi-fi", "wifi", "воля", "тріолан", "датагруп", "datagroup", "ланет", "провайдер"),
    "📞" to listOf("мобільн", "телефон", "київстар", "kyivstar", "vodafone", "водафон", "lifecell", "лайфсел", "зв'язок", "зв’язок"),
    "🏠" to listOf("оренд", "квартир", "осбб", "житл", "іпотек"),
    "💡" to listOf("світло", "електр", "дтек", "комун"),
    "🔥" to listOf("газ"),
    "💧" to listOf("вода", "водокан"),
    "💪" to listOf("спортзал", "фітнес", "fitness", "gym", "басейн", "тренуван"),
    "🏦" to listOf("кредит", "розстрочк", "частинами", "позик", "борг"),
    "🛡️" to listOf("страхов", "автоцивілк", "осцпв", "каско"),
    "📚" to listOf("навчан", "англійськ", "duolingo", "книг", "освіт", "репетитор"),
    "🚗" to listOf("авто", "машин", "паркуван", "парковк", "пальне", "бензин"),
    "💊" to listOf("аптек", "ліки", "лікар", "медич", "стоматолог"),
    "🐾" to listOf("кіт", "кота", "собак", "корм", "ветерин")
)

/** What a payment nobody recognised gets: it is money, and it recurs. */
const val PAY_EMOJI_DEFAULT = "💳"

/** The same idea for things bought: what the thing is, not who sold it. */
private val ORDER_RULES: List<Pair<String, List<String>>> = listOf(
    "📱" to listOf("чохол", "телефон", "смартфон", "iphone", "galaxy", "redmi", "зарядк", "кабель", "павербанк", "power bank", "захисне скло"),
    "🎧" to listOf("навушник", "airpods", "гарнітур", "headphone", "earbuds"),
    "👟" to listOf("кросівк", "взутт", "кеди", "черевик", "nike", "adidas", "asics", "new balance"),
    "👕" to listOf("футболк", "куртк", "худі", "штани", "джинс", "сукн", "светр", "одяг"),
    "💻" to listOf("ноутбук", "laptop", "macbook", "модуль пам", "ssd", "клавіатур", "миш", "монітор", "відеокарт"),
    "🎮" to listOf("ігров", "steam", "playstation", "xbox", "nintendo", "геймпад", "джойстик"),
    "📚" to listOf("книг", "book"),
    "🧸" to listOf("іграшк", "lego", "лего"),
    "💄" to listOf("крем", "парфум", "косметик", "шампун", "помад")
)

/** A parcel that is just a parcel. */
const val ORDER_EMOJI_PARCEL = "📦"

/** A download that is not a game: a key, a licence, a subscription bought once. */
const val ORDER_EMOJI_DIGITAL = "🔑"

/** True when [word] appears in [text] at the start of a word. */
private fun startsWord(text: String, word: String): Boolean {
    var from = 0
    while (true) {
        val at = text.indexOf(word, from)
        if (at < 0) return false
        if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
        from = at + 1
    }
}

private fun firstRule(name: String, rules: List<Pair<String, List<String>>>): String? {
    val text = name.lowercase()
    return rules.firstOrNull { (_, words) -> words.any { startsWord(text, it) } }?.first
}

/** The emoji a payment's name suggests. */
fun payEmoji(name: String): String = firstRule(name, PAY_RULES) ?: PAY_EMOJI_DEFAULT

/** The emoji a purchase's name suggests, falling back on whether it travels. */
fun orderEmoji(name: String, digital: Boolean): String =
    firstRule(name, ORDER_RULES) ?: if (digital) ORDER_EMOJI_DIGITAL else ORDER_EMOJI_PARCEL

/** A wish with no photo yet: what the thing is, or a present. */
fun wishEmoji(name: String): String = firstRule(name, ORDER_RULES) ?: "🎁"

/** The picture on each card of the month's recap. */
fun recapEmoji(kind: RecapKind): String = when (kind) {
    RecapKind.OPENING -> "👀"
    RecapKind.DROPS_CAUGHT -> "📉"
    RecapKind.PRICES_HELD -> "🧊"
    RecapKind.LONGEST_WAIT -> "⏳"
    RecapKind.DEAREST_WISH -> "💎"
    RecapKind.PATIENCE_PAID -> "🎯"
    RecapKind.CHEAPEST_BOUGHT -> "🪙"
    RecapKind.MONTH_ON_MONTH -> "📊"
    RecapKind.COMMITTED_SHARE -> "🍰"
    RecapKind.STANDING_COSTS -> "🧾"
    RecapKind.YEAR_IN_WISHES -> "🎁"
    RecapKind.YEAR_IN_DOLLARS -> "💵"
    RecapKind.SUB_PRICE_MOVED -> "📈"
    RecapKind.TRIAL_ENDED -> "⌛"
    RecapKind.SUBS_STEADY -> "🧘"
    RecapKind.LABEL -> "✨"
}

/** What is drawn for a payment: the one picked by hand, else the guess. */
fun shownEmoji(pay: Pay): String = pay.emoji.ifBlank { payEmoji(pay.name) }

/** What is drawn for a purchase: the one picked by hand, else the guess. */
fun shownEmoji(order: Order): String = order.emoji.ifBlank { orderEmoji(order.name, order.digital) }

/**
 * The emoji offered in the picker, roughly in the order a person looks for them:
 * bills first, then things bought, then the rest.
 */
val EMOJI_CHOICES = listOf(
    "🌐", "📞", "📺", "🍿", "🎧", "☁️", "🎨", "🤖", "🏠", "💡", "🔥", "💧",
    "💪", "🏦", "🛡️", "📚", "🚗", "💊", "🐾", "🎮", "💳", "🔁", "🧾", "🎓",
    "📦", "📱", "💻", "👟", "👕", "🎁", "🔑", "🛒", "💄", "🧸", "🛋️", "⌚",
    "✈️", "🚕", "☕", "🍔", "🛍️", "💰", "🐷", "💵", "⭐", "❤️", "🎵", "📷"
)

/**
 * Text typed into the picker's own field, if it is an emoji.
 *
 * The keyboard's emoji panel is the way to anything the grid lacks, so this takes
 * what it inserts — one emoji, possibly several code points joined — and refuses
 * words. Nought letters, at least one pictograph, and short.
 */
fun typedEmoji(text: String): String? {
    val value = text.trim()
    if (value.isEmpty() || value.length > 16) return null
    val points = value.codePoints().toArray()
    if (points.any { Character.isLetter(it) || Character.isWhitespace(it) }) return null
    if (points.none { it >= 0x2000 && (Character.getType(it) == Character.OTHER_SYMBOL.toInt() || it >= 0x1F000) }) {
        return null
    }
    return value
}

// ------------------------------------------------------------ the pictures

/**
 * The file name an emoji's picture is kept under: its code points in hex, joined
 * with dashes, without the presentation selector U+FE0F.
 *
 * The selector is dropped on both sides because it comes and goes: a keyboard
 * inserts «☁️» with it, a list in code may hold «☁» without, and both mean the
 * same picture. "🌐" is "1f310"; "👨‍💻" is "1f468-200d-1f4bb".
 */
fun emojiKey(emoji: String): String =
    emoji.codePoints().toArray().filter { it != 0xFE0F }.joinToString("-") { Integer.toHexString(it) }

private val PACK_ENTRY = Regex("""^([0-9a-f]{2,6}(?:-[0-9a-f]{2,6}){0,9})\.png$""")

/**
 * The key a file inside an imported pack stands for, or null to skip it.
 *
 * Folders inside the archive are ignored, and so is anything not named like a
 * key — the file name is never used as a path, so a crafted name cannot write
 * outside the emoji folder.
 */
fun packEntryKey(entryName: String): String? {
    val name = entryName.substringAfterLast('/').substringAfterLast('\\').lowercase()
    val match = PACK_ENTRY.matchEntire(name) ?: return null
    return match.groupValues[1].split('-').filter { it != "fe0f" }.joinToString("-").ifEmpty { null }
}

/** One picture's ceiling. The owner's are 72 px and about 3 KB. */
private const val MAX_EMOJI_BYTES = 256 * 1024

/** The whole pack's ceiling. His is 11 MB for 3 370 pictures. */
private const val MAX_PACK_BYTES = 64L * 1024 * 1024

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** Up to [limit] bytes of the current entry, or null when it is longer than that. */
private fun InputStream.readUpTo(limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        out.write(buffer, 0, read)
        if (out.size() > limit) return null
    }
}

/**
 * The imported emoji pictures, in the app's own storage.
 *
 * A folder of PNGs named by [emojiKey]. Kept out of the backup on purpose: it is
 * not the owner's data, it is ten megabytes, and it comes back from the same
 * file he imported it from.
 */
object EmojiPack {
    private const val FOLDER = "emoji"

    /** Bumped when the pack changes, so everything drawn looks again. */
    var version by mutableIntStateOf(0)
        private set

    /** Decoded pictures, and a marker for keys the pack does not have. */
    private val cache = LruCache<String, Any>(512)
    private val missing = Any()

    private fun folder(context: Context) = File(context.filesDir, FOLDER)

    /** How many pictures are in place. Nought means none was imported. */
    fun count(context: Context): Int =
        folder(context).list()?.count { it.endsWith(".png") } ?: 0

    /** The picture for [emoji], or null to draw the character instead. */
    fun image(context: Context, emoji: String): ImageBitmap? {
        val key = emojiKey(emoji)
        if (key.isEmpty()) return null
        cache.get(key)?.let { return it as? ImageBitmap }
        val file = File(folder(context), "$key.png")
        val image = if (file.isFile) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
        cache.put(key, image ?: missing)
        return image
    }

    /**
     * Unpacks a zip of PNGs named by key and replaces the current pack with it.
     *
     * Written to a folder beside the real one and swapped in at the end, so a file
     * that turns out to be wrong halfway through leaves the old pack as it was.
     * Returns how many pictures went in; nought means the file held none and
     * nothing was changed. Run off the main thread.
     */
    fun import(context: Context, uri: Uri): Int {
        val staging = File(context.filesDir, "$FOLDER-new")
        staging.deleteRecursively()
        staging.mkdirs()
        var count = 0
        var total = 0L
        val stream = context.contentResolver.openInputStream(uri) ?: error("Файл не відкривається")
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val key = if (entry.isDirectory) null else packEntryKey(entry.name)
                val bytes = key?.let { zip.readUpTo(MAX_EMOJI_BYTES) }
                if (key != null && bytes != null && bytes.size > PNG_SIGNATURE.size &&
                    bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
                ) {
                    total += bytes.size
                    if (total > MAX_PACK_BYTES) {
                        staging.deleteRecursively()
                        error("Завеликий файл")
                    }
                    File(staging, "$key.png").writeBytes(bytes)
                    count++
                }
                zip.closeEntry()
            }
        }
        if (count == 0) {
            staging.deleteRecursively()
            return 0
        }
        val target = folder(context)
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        cache.evictAll()
        version++
        return count
    }

    /** Back to the phone's own emoji. */
    fun clear(context: Context) {
        folder(context).deleteRecursively()
        cache.evictAll()
        version++
    }
}

/**
 * One emoji at [size]: the imported picture when there is one, the phone's own
 * glyph when there is not.
 *
 * Decorative — the name beside it says the same thing — so it is silent to
 * TalkBack. The pictures are 72 px, sharp up to about 26 dp on this phone and
 * slightly soft above it, which at sticker size reads as a sticker.
 */
@Composable
fun EmojiGlyph(emoji: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = EmojiPack.version
    val image = remember(emoji, version) { EmojiPack.image(context, emoji) }
    Box(modifier.popInOnEnter().size(size).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.High)
        } else {
            val fontSize = with(LocalDensity.current) { (size * 0.8f).toSp() }
            Text(emoji, fontSize = fontSize, lineHeight = fontSize, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}
