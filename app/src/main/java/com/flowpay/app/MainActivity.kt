package com.flowpay.app

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.app.DownloadManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.glance.appwidget.updateAll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

data class Wish(
    val id: String,
    val name: String,
    val url: String,
    val image: String,
    val price: Double,
    val targetPrice: Double = 0.0,
    val category: String = "Інше",
    val history: List<PricePoint>,
    /** Epoch day the price was last checked, so time spans can be stated honestly. */
    val checkedDay: Long = 0L,
    /** Money already put aside for this item. */
    val saved: Double = 0.0,
    /** What the plan is to add each month. */
    val monthlyPlan: Double = 0.0,
    /** Buy-by date as an epoch day. Zero means the plan runs from a monthly sum. */
    val deadline: Long = 0L,
    /** The last price a notification announced, so the next one has to beat it. */
    val notifiedPrice: Double = 0.0,
    /**
     * Which of a page's prices this wish follows, by the name the shop gave it.
     *
     * Empty on a page that states one price, which is most of them. On a page of
     * editions or sizes it is the anchor that keeps later checks on the same one.
     */
    val variant: String = ""
)

data class Pay(
    val name: String,
    val amount: Double,
    val day: Int = 1,
    /** "UAH" or "USD". Rent is commonly quoted and paid in dollars. */
    val currency: String = UAH,
    /**
     * Days of notice before the charge. Nought means the morning of.
     *
     * A reminder that arrives on the day a subscription renews is too late to do
     * anything about it, which is the one thing a subscription tracker is for.
     */
    val warnDays: Int = DEFAULT_WARN_DAYS
)
data class Order(
    val id: String,
    val name: String,
    val url: String,
    val status: String,
    val tracking: String = "",
    val image: String = "",
    val price: Double = 0.0,
    /** The carrier's own wording plus where it saw the parcel last. */
    val statusDetail: String = "",
    /** When the carrier was last asked, as epoch millis. Zero means never. */
    val checkedAt: Long = 0L,
    /** The carrier reported a refusal, a return, or a number it does not know. */
    val problem: Boolean = false,
    /** Epoch day free storage ends. Zero means the carrier has not said. */
    val paidStorageFrom: Long = 0L,
    /** Epoch day the carrier expects to deliver. Zero means unknown. */
    val scheduledDelivery: Long = 0L,
    /** Cash on delivery still owed. */
    val amountToPay: Double = 0.0
)

class MainActivity : ComponentActivity() {
    /**
     * What the app was opened to do, once. Held as state rather than read from
     * [getIntent] inside the composition, because a share that arrives while the
     * app is already open comes through [onNewIntent] and nothing would recompose.
     */
    private var command by mutableStateOf<AppCommand?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        command = commandOf(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val notificationsGranted = android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!notificationsGranted) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        PriceWorker.schedule(this)
        ReminderWorker.schedule(this)
        setContent { FlowPayApp(this, command) { command = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        command = commandOf(intent)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app is the moment the home screen is about to be looked at,
        // and by then anything edited in this session has already been saved.
        lifecycleScope.launch { FlowPayWidget().updateAll(this@MainActivity) }
    }

    // Read as a CharSequence: a text/html share arrives as a styled Spanned, and
    // getStringExtra answers null for one rather than the text inside it.
    private fun commandOf(intent: Intent?): AppCommand? =
        appCommand(intent?.action, intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
}

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("flowpay", Context.MODE_PRIVATE)

    fun wishes(): List<Wish> = jsonList("w") { o ->
        val recorded = o.optJSONArray("h") ?: JSONArray()
        Wish(
            id = o.optString("id", System.currentTimeMillis().toString()),
            name = cleanProductTitle(o.optString("n", "Товар")).ifBlank { "Товар" },
            url = o.optString("u"),
            image = o.optString("i"),
            price = o.optDouble("p", 0.0),
            targetPrice = o.optDouble("t", 0.0),
            category = o.optString("c", "Інше"),
            // Histories written before dates existed are bare numbers. They are read
            // as points with an unknown day rather than being thrown away.
            history = (0 until recorded.length()).mapNotNull { index ->
                recorded.optJSONObject(index)?.let { point ->
                    PricePoint(point.optDouble("p", 0.0), point.optLong("d", 0L))
                } ?: recorded.optDouble(index, 0.0).takeIf { it > 0 }?.let { PricePoint(it, 0L) }
            }.filter { it.price > 0 },
            checkedDay = o.optLong("cd", 0L),
            notifiedPrice = o.optDouble("np", 0.0),
            variant = o.optString("v"),
            saved = o.optDouble("s", 0.0),
            monthlyPlan = o.optDouble("m", 0.0),
            deadline = o.optLong("dl", 0L)
        )
    }

    fun saveWishes(items: List<Wish>) = save("w", items.map {
        JSONObject().put("id", it.id).put("n", it.name).put("u", it.url).put("i", it.image)
            .put("p", it.price).put("t", it.targetPrice).put("c", it.category)
            .put(
                "h",
                JSONArray().apply {
                    it.history.forEach { point ->
                        put(JSONObject().put("p", point.price).put("d", point.day))
                    }
                }
            )
            .put("cd", it.checkedDay)
            .put("s", it.saved).put("m", it.monthlyPlan).put("dl", it.deadline)
            .put("np", it.notifiedPrice).put("v", it.variant)
    })

    fun pays(): List<Pay> = jsonList("pay") {
        Pay(
            it.optString("n"),
            it.optDouble("a"),
            it.optInt("d", 1),
            // Entries saved before currencies existed were all hryvnia.
            it.optString("cur", UAH).ifBlank { UAH },
            // Entries saved before the warning existed got a day's notice from the
            // worker itself, so that is what they keep.
            it.optInt("wd", DEFAULT_WARN_DAYS)
        )
    }

    fun savePays(items: List<Pay>) = save("pay", items.map {
        JSONObject().put("n", it.name).put("a", it.amount).put("d", it.day).put("cur", it.currency)
            .put("wd", it.warnDays)
    })

    fun orders(): List<Order> = jsonList("orders") {
        Order(
            it.optString("id"), it.optString("n"), it.optString("u"),
            it.optString("s", ORDERED), it.optString("t"),
            it.optString("i"), it.optDouble("p", 0.0),
            it.optString("sd"), it.optLong("ca", 0L),
            it.optBoolean("pr", false), it.optLong("ps", 0L),
            it.optLong("sdl", 0L), it.optDouble("atp", 0.0)
        )
    }
    fun saveOrders(items: List<Order>) = save("orders", items.map {
        JSONObject().put("id", it.id).put("n", it.name).put("u", it.url)
            .put("s", it.status).put("t", it.tracking).put("i", it.image).put("p", it.price)
            .put("sd", it.statusDetail).put("ca", it.checkedAt)
            .put("pr", it.problem).put("ps", it.paidStorageFrom)
            .put("sdl", it.scheduledDelivery).put("atp", it.amountToPay)
    })

    /** Epoch day the payment reminder last ran, so a day is never repeated. */
    fun lastReminderDay(): Long = prefs.getLong("reminded", 0L)

    fun saveLastReminderDay(day: Long) = prefs.edit { putLong("reminded", day) }

    /** How the wishlist is ordered, remembered between sessions. */
    fun wishSort(): WishSort = wishSortFrom(prefs.getString("wish_sort", "") ?: "")

    fun saveWishSort(sort: WishSort) = prefs.edit { putString("wish_sort", sort.name) }

    /** Monthly income, used to work out what is free after the standing costs. */
    fun income(): Double = prefs.getFloat("income", 0f).toDouble()

    fun saveIncome(value: Double) = prefs.edit { putFloat("income", value.toFloat()) }

    /**
     * Last known exchange rate and when it was fetched.
     *
     * Monobank allows roughly one request a minute per address, so asking on every
     * visit to the tab earns a rejection and the screen went blank. The last good
     * rate is kept and shown with its timestamp instead.
     */
    fun fxRate(): Pair<FxRate, Long> {
        val buy = prefs.getFloat("fx_buy", 0f).toDouble()
        val sell = prefs.getFloat("fx_sell", 0f).toDouble()
        if (sell <= 0.0) return FxRate() to 0L
        // A rate saved before the app knew about sources can only have come from
        // Monobank, which is the only feed there was.
        val source = prefs.getString("fx_src", SOURCE_MONOBANK) ?: SOURCE_MONOBANK
        return FxRate(buy, sell, source, prefs.getString("fx_day", "").orEmpty()) to
            prefs.getLong("fx_at", 0L)
    }

    fun saveFxRate(rate: FxRate, atMillis: Long) = prefs.edit {
        putFloat("fx_buy", rate.buy.toFloat())
        putFloat("fx_sell", rate.sell.toFloat())
        putString("fx_src", rate.source)
        putString("fx_day", rate.date)
        putLong("fx_at", atMillis)
    }

    /**
     * One exchange-rate reading per day, for the chart on the currency screen.
     *
     * Kept apart from [fxRate], which is the current figure and is overwritten on
     * every refresh. Nothing recorded a series before this, so on every existing
     * phone this starts empty and the screen says so.
     */
    fun rateHistory(): List<PricePoint> =
        jsonList("fxh") { PricePoint(it.optDouble("p", 0.0), it.optLong("d", 0L)) }
            .filter { it.price > 0 }

    fun saveRateHistory(points: List<PricePoint>) = save("fxh", points.map {
        JSONObject().put("p", it.price).put("d", it.day)
    })

    fun exportJson(): String = JSONObject()
        .put("version", 1)
        .put("wishes", JSONArray(prefs.getString("w", "[]")))
        .put("payments", JSONArray(prefs.getString("pay", "[]")))
        .put("orders", JSONArray(prefs.getString("orders", "[]")))
        .toString(2)

    fun importJson(text: String) {
        val root = JSONObject(text)
        val wishes = root.getJSONArray("wishes")
        val payments = root.optJSONArray("payments") ?: JSONArray()
        val orders = root.optJSONArray("orders") ?: JSONArray()
        prefs.edit {
            putString("w", wishes.toString())
            putString("pay", payments.toString())
            putString("orders", orders.toString())
        }
    }

    private fun <T> jsonList(key: String, map: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).map { map(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(key: String, values: List<JSONObject>) {
        prefs.edit { putString(key, JSONArray(values).toString()) }
    }
}

fun isSupportedWebUrl(value: String): Boolean = runCatching {
    val url = URL(value.trim())
    url.protocol in setOf("http", "https") && url.host.isNotBlank()
}.getOrDefault(false)

fun refreshedWish(previous: Wish, current: Wish, today: Long = LocalDate.now().toEpochDay()): Wish =
    previous.copy(
        image = current.image.ifBlank { previous.image },
        price = current.price,
        history = appendPrice(previous.history, current.price, today),
        checkedDay = today
    )

/**
 * Re-reads a page and follows the variant this wish was set to.
 *
 * Kept apart from [refreshedWish] because the interesting case is the one where
 * nothing is returned: a page that no longer carries the followed variant must
 * leave the stored price alone rather than swap in a neighbouring edition.
 */
fun refreshedFromPage(previous: Wish, html: String, today: Long = LocalDate.now().toEpochDay()): Wish? {
    val match = matchOffer(extractOffers(html), previous.variant, previous.price)
    val offer = (match as? OfferMatch.Found)?.offer ?: return null
    return previous.copy(
        price = offer.price,
        history = appendPrice(previous.history, offer.price, today),
        checkedDay = today
    )
}

/** Fetches a shop page. Separate from parsing it, so both uses share one request. */
suspend fun pageHtml(link: String): String = withContext(Dispatchers.IO) {
    val normalizedLink = link.trim()
    require(isSupportedWebUrl(normalizedLink)) { "Вкажіть коректне HTTP або HTTPS посилання" }
    val connection = URL(normalizedLink).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
    connection.inputStream.bufferedReader().use { it.readText() }
}

suspend fun product(link: String): Wish {
    val normalizedLink = link.trim()
    val html = pageHtml(normalizedLink)
    return parseProduct(
        html,
        normalizedLink,
        System.currentTimeMillis().toString(),
        LocalDate.now().toEpochDay()
    )
}

/**
 * Re-reads a wish's page and follows the variant it was set to.
 *
 * Null means the reading is not usable — the page lost its prices, or lost the
 * edition being followed — and a null must leave the stored price alone. Silently
 * adopting a neighbouring variant's price would corrupt the history a verdict is
 * computed from, and nothing on screen would show it had happened.
 */
suspend fun refreshed(previous: Wish): Wish? =
    refreshedFromPage(previous, pageHtml(previous.url))

data class UpdateInfo(val versionCode: Int, val versionName: String, val downloadUrl: String)

/**
 * The rate, from whichever source will answer.
 *
 * Monobank is asked first because a bank's own buy and sell prices are what money
 * actually changes hands at. But it allows roughly one request a minute per
 * address and answers a 429 after that, and a rate tracker that goes blank the
 * second time you open it is not a rate tracker. The National Bank publishes the
 * official rate with no key and no limit, so it takes over — and the figure is
 * labelled with where it came from, because the two are not the same number.
 */
suspend fun usdRate(): FxRate = withContext(Dispatchers.IO) {
    val market = runCatching { monobankRate() }.getOrNull()
    if (market != null && market.sell > 0) return@withContext market
    runCatching { nbuRate() }.getOrNull() ?: FxRate()
}

private fun monobankRate(): FxRate {
    // Rate limited, so a hung request must not sit forever.
    val connection = URL("https://api.monobank.ua/bank/currency").openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    // A 429 body is not a rate feed, and parsing it would throw rather than fall back.
    if (connection.responseCode !in 200..299) return FxRate()
    val body = connection.inputStream.bufferedReader().use { it.readText() }
    return parseUsdRate(body)
}

private fun nbuRate(): FxRate {
    val connection = URL(
        "https://bank.gov.ua/NBUStatService/v1/statdirectory/exchangenew?valcode=USD&json"
    ).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    if (connection.responseCode !in 200..299) return FxRate()
    val body = connection.inputStream.bufferedReader().use { it.readText() }
    return parseNbuRate(body)
}

suspend fun latestUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
    val connection = URL("https://api.github.com/repos/gerasymchuksergiy/flowpay/releases/latest")
        .openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("User-Agent", "FlowPay-Android")
    if (connection.responseCode !in 200..299) return@withContext null
    val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    val tag = release.optString("tag_name")
    val code = releaseVersionCode(release.optString("body"), tag) ?: return@withContext null
    val assets = release.optJSONArray("assets") ?: return@withContext null
    val all = (0 until assets.length()).map { assets.getJSONObject(it) }
    val chosen = pickApkAsset(all.map { it.optString("name") }, tag) ?: return@withContext null
    val apk = all.first { it.optString("name") == chosen }
    val downloadUrl = apk.optString("browser_download_url")
    val downloadUri = downloadUrl.toUri()
    if (downloadUri.scheme != "https" || downloadUri.host != "github.com") return@withContext null
    UpdateInfo(code, tag.ifBlank { "нова версія" }, downloadUrl)
}

/**
 * Asks Nova Poshta where a parcel is.
 *
 * Their tracking method answers with an empty API key, so this needs no
 * registration and no secret in the APK. Returns null when the number is not
 * theirs or the call failed, which is deliberately different from a parcel that
 * simply has not moved.
 */
suspend fun parcelStatus(number: String): ParcelStatus? = withContext(Dispatchers.IO) {
    val clean = number.filter { !it.isWhitespace() }
    if (detectCarrier(clean) != CARRIER_NOVA_POSHTA) return@withContext null
    val body = JSONObject()
        .put("apiKey", "")
        .put("modelName", "TrackingDocument")
        .put("calledMethod", "getStatusDocuments")
        .put(
            "methodProperties",
            JSONObject().put(
                "Documents",
                JSONArray().put(JSONObject().put("DocumentNumber", clean).put("Phone", ""))
            )
        )
        .toString()
    val connection = URL("https://api.novaposhta.ua/v2.0/json/").openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Content-Type", "application/json")
    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    if (connection.responseCode !in 200..299) return@withContext null
    parseNovaPoshtaStatus(connection.inputStream.bufferedReader().use { it.readText() })
}

fun installUpdate(context: Context, url: String, onMessage: (String) -> Unit) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        )
        onMessage("Дозвольте встановлення для FlowPay і натисніть «Оновити» ще раз")
        return
    }
    val manager = context.getSystemService(DownloadManager::class.java)
    val downloadUri = url.toUri()
    require(downloadUri.scheme == "https" && downloadUri.host == "github.com") {
        "Некоректне джерело оновлення"
    }
    val request = DownloadManager.Request(downloadUri)
        .setTitle("Оновлення FlowPay")
        .setDescription("Завантаження нової версії")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            "FlowPay-update-${System.currentTimeMillis()}.apk"
        )
    val id = manager.enqueue(request)
    onMessage("Завантаження почалося")
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
            runCatching { receiverContext.unregisterReceiver(this) }
            val apk = manager.getUriForDownloadedFile(id)
            if (apk == null) {
                onMessage("Не вдалося завантажити APK")
                return
            }
            receiverContext.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    ContextCompat.registerReceiver(
        context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
        ContextCompat.RECEIVER_NOT_EXPORTED
    )
}


@Composable
fun FlowPayApp(context: Context, command: AppCommand? = null, onCommandHandled: () -> Unit = {}) {
    val store = remember { Store(context) }
    var tab by remember { mutableIntStateOf(TAB_WISHES) }
    var wishes by remember { mutableStateOf(store.wishes()) }
    var pays by remember { mutableStateOf(store.pays()) }
    var orders by remember { mutableStateOf(store.orders()) }
    var adding by remember { mutableStateOf(false) }
    var openedWish by remember { mutableStateOf<String?>(null) }

    // Both belong to whichever tab is showing, so leaving a tab clears them.
    LaunchedEffect(tab) {
        adding = false
        openedWish = null
    }

    val notices = remember { SnackbarHostState() }
    val noticeScope = rememberCoroutineScope()
    // One line at a time: a share is a sequence of two or three of these, and
    // queued snackbars would still be reporting the fetch after it finished.
    fun say(text: String) = noticeScope.launch {
        notices.currentSnackbarData?.dismiss()
        notices.showSnackbar(text)
    }

    // Declared after the effect above so that when a command arrives on another
    // tab, the tab switch clears that tab's dialogs first and this runs second.
    LaunchedEffect(command, tab) {
        if (command == null) return@LaunchedEffect
        // Every command belongs to the wishlist. Switching costs a pass through
        // this effect, which is why it returns and waits for the new tab.
        if (tab != TAB_WISHES) {
            tab = TAB_WISHES
            return@LaunchedEffect
        }
        when (command) {
            is AppCommand.AddWish -> adding = true
            is AppCommand.RefreshPrices -> {
                if (wishes.isEmpty()) {
                    say(refreshMessage(0, 0))
                } else {
                    say("Перевіряю ціни…")
                    val fetched = wishes.map { runCatching { refreshed(it) }.getOrNull() }
                    val result = applyFollowed(wishes, fetched)
                    wishes = result.wishes
                    store.saveWishes(result.wishes)
                    say(refreshMessage(result.updated, result.wishes.size))
                }
            }
            is AppCommand.AddShared -> when (val link = sharedLink(command.text, wishes)) {
                SharedLink.Missing -> say("У повідомленні немає посилання")
                is SharedLink.Known -> say("«${link.wish.name}» вже у списку")
                is SharedLink.New -> {
                    // The link is saved before the page is read, so a shop that
                    // blocks the fetch costs a name and a price, never the item.
                    val id = System.currentTimeMillis().toString()
                    val saved = wishes + placeholderWish(link.url, id)
                    wishes = saved
                    store.saveWishes(saved)
                    say("Додано до бажань, шукаю ціну…")
                    val fetched = runCatching { product(link.url) }.getOrNull()
                    if (fetched == null) {
                        say("Сторінка не читається — посилання збережено")
                    } else {
                        // The shop's title replaces the placeholder, but the row
                        // keeps its id so nothing else has to be told it changed.
                        val filled = wishes.map {
                            if (it.id == id) refreshedWish(it, fetched).copy(name = fetched.name) else it
                        }
                        wishes = filled
                        store.saveWishes(filled)
                        say("Додано: ${fetched.name}")
                    }
                }
            }
        }
        onCommandHandled()
    }

    // System back closes the item page before it leaves the app.
    BackHandler(enabled = openedWish != null) { openedWish = null }

    // Recomputed whenever expenses change, so the wishlist plan and the expenses
    // screen never disagree about what is free this month.
    val usdSell = remember { store.fxRate().first }.sell
    val monthBudget = budget(remember(pays) { store.income() }, monthlyTotal(pays, usdSell))

    val addLabel = when {
        // An item page has its own actions, and the button would cover them.
        openedWish != null -> null
        tab == TAB_WISHES -> "Додати бажання"
        tab == TAB_PAYMENTS -> "Додати витрату"
        tab == TAB_ORDERS -> "Додати покупку"
        else -> null
    }

    // Read once, so the pill cannot change its mind about what is due partway
    // through a session that happens to cross midnight.
    val today = remember { LocalDate.now() }
    // Suppressed on an item page for the same reason as the action button: that
    // page is one thing at a time, and the pill would be a second one.
    val note = if (openedWish == null) statusNote(orders, pays, wishes, today, usdSell) else null

    // The bar is chrome, and chrome should yield to content. It moves all the
    // way or not at all: following the finger left it resting half off screen,
    // with its labels cut and its icons crowding the system buttons.
    val density = LocalDensity.current
    val barTravel = with(density) { Space.navBar.toPx() } +
        WindowInsets.navigationBars.getBottom(density)
    val barDown = remember { mutableStateOf(false) }
    val barHidden by animateFloatAsState(
        if (barDown.value) barTravel else 0f,
        label = "bottom bar"
    )
    val barScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // A threshold, so a stray pixel of movement does not flap the bar.
                if (available.y < -8f) barDown.value = true
                if (available.y > 8f) barDown.value = false
                return Offset.Zero
            }
        }
    }

    FlowPayTheme {
        Scaffold(
            modifier = Modifier.nestedScroll(barScroll),
            containerColor = AppBackground,
            contentColor = TextPrimary,
            snackbarHost = { SnackbarHost(notices) },
            floatingActionButton = {
                addLabel?.let { label ->
                    // Collapses to a plus once you scroll. Its full width was
                    // covering the bottom of whichever card sat under it, three
                    // separate times; the label is needed once, not always.
                    ExtendedFloatingActionButton(
                        onClick = { adding = true },
                        expanded = !barDown.value,
                        containerColor = Accent,
                        contentColor = AccentInk,
                        shape = Radius.pill,
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text(label, fontWeight = Type.medium) }
                    )
                }
            },
            bottomBar = {
                Column(Modifier.offset { IntOffset(0, barHidden.roundToInt()) }) {
                    // An edge, because a translucent bar and the list showing
                    // through it are otherwise one continuous grey.
                    Box(Modifier.fillMaxWidth().height(Dp.Hairline).background(HairLine))
                    NavigationBar(
                        // Translucent rather than a slab: the list keeps running
                        // underneath, which is what says there is more of it.
                        containerColor = SurfaceLow.copy(alpha = 0.82f),
                        tonalElevation = 0.dp
                    ) {
                        val tabs = listOf(
                            Icons.Default.FavoriteBorder to "Бажання",
                            Icons.Default.SwapVert to "Курс",
                            Icons.Default.ReceiptLong to "Платежі",
                            Icons.Default.LocalShipping to "Покупки",
                            Icons.Default.Insights to "Огляд"
                        )
                        tabs.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(item.first, item.second) },
                                label = {
                                    Text(
                                        item.second,
                                        fontSize = Type.navLabelSize,
                                        letterSpacing = Type.navLabelTracking,
                                        fontWeight = Type.medium,
                                        maxLines = 1
                                    )
                                },
                                // Labelling only the active tab makes the row change
                                // width as you switch, which reads as a glitch.
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = AccentSoft,
                                    selectedIconColor = Accent,
                                    selectedTextColor = Accent,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    // Everything but the bottom. The navigation bar is translucent
                    // and the lists run underneath it, so its height is left to
                    // navClearance() inside each list rather than cut out here.
                    .padding(
                        top = padding.calculateTopPadding(),
                        start = padding.calculateStartPadding(LocalLayoutDirection.current),
                        end = padding.calculateEndPadding(LocalLayoutDirection.current)
                    )
            ) {
                // Above the screen rather than over it: the pill and a screen's own
                // compact title both want the top strip, and one covering the other
                // would leave you unable to read either.
                StatusPill(note) { target -> tab = target }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        TAB_WISHES -> WishlistScreen(
                            items = wishes,
                            save = { wishes = it; store.saveWishes(it) },
                            store = store,
                            context = context,
                            adding = adding,
                            setAdding = { adding = it },
                            opened = openedWish,
                            setOpened = { openedWish = it },
                            // A bought wish becomes a parcel, and the tab follows it
                            // so the move is visible rather than something to go
                            // looking for.
                            freeCash = monthBudget.free,
                            onBought = { order ->
                                val next = orders + order
                                orders = next
                                store.saveOrders(next)
                                tab = TAB_ORDERS
                            }
                        )
                        TAB_RATE -> CalculatorScreen(store)
                        TAB_PAYMENTS -> PaymentsScreen(pays, { pays = it; store.savePays(it) }, store, adding) { adding = it }
                        TAB_ORDERS -> OrdersScreen(orders, { orders = it; store.saveOrders(it) }, context, adding) { adding = it }
                        else -> SettingsScreen(
                            summary = overview(wishes, pays, orders, monthBudget.income, usdSell),
                            store = store
                        ) {
                            wishes = store.wishes()
                            pays = store.pays()
                            orders = store.orders()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The large title at the top of a screen.
 *
 * It is always item zero of its list and nothing else shares that item, because
 * [CollapsingTitle] measures this block to know when it has scrolled far enough to
 * hand the screen's name over to the compact bar. Put anything else in item zero
 * and the handover happens far too late.
 */
@Composable
fun ScreenHeader(
    kicker: String,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Zero where the list around it already supplies the screen margin. */
    inset: Dp = Space.screen
) {
    // Overline, title and subtitle form one group, at most 8dp apart, followed by a
    // 32dp break. That break is what gives the screen a readable shape.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = inset, end = inset, top = Space.md, bottom = Space.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                kicker,
                color = Accent,
                fontSize = Type.overlineSize,
                fontWeight = Type.strong,
                letterSpacing = Type.overlineTracking
            )
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(Space.sm))
        Text(
            title,
            fontSize = Type.screenTitleSize,
            lineHeight = Type.screenTitleLine,
            letterSpacing = Type.screenTitleTracking,
            fontWeight = Type.strong
        )
        subtitle?.let {
            Text(
                it,
                color = TextSecondary,
                fontSize = Type.bodySize,
                lineHeight = Type.bodyLine,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
    }
}

@Composable
fun WishlistScreen(
    items: List<Wish>,
    save: (List<Wish>) -> Unit,
    store: Store,
    context: Context,
    adding: Boolean,
    setAdding: (Boolean) -> Unit,
    // Held by id rather than by value so the page keeps showing the live item
    // after a price refresh or a change to the savings plan.
    opened: String?,
    setOpened: (String?) -> Unit,
    freeCash: Double,
    onBought: (Order) -> Unit
) {
    var editing by remember { mutableStateOf<Wish?>(null) }
    var sort by remember { mutableStateOf(store.wishSort()) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val openedWish = opened?.let { id -> items.firstOrNull { it.id == id } }
    // The list and the page live in one composition, so the photo can travel
    // between them: the card image grows into the page header instead of being
    // replaced by a second copy of itself.
    SharedTransitionLayout {
        AnimatedContent(openedWish, label = "wish") { shown ->
            if (shown != null) {
                WishDetailScreen(
                    wish = shown,
                    visibility = this@AnimatedContent,
                    context = context,
                    onBack = { setOpened(null) },
                    onChange = { changed -> save(items.map { if (it.id == changed.id) changed else it }) },
                    onEdit = { editing = shown },
                    onDelete = { save(items - shown); setOpened(null) },
                    freeCash = freeCash,
                    onBought = { trackingNumber ->
                        onBought(
                            Order(
                                id = shown.id,
                                name = shown.name,
                                url = shown.url,
                                status = "Замовлено",
                                tracking = trackingNumber,
                                image = shown.image,
                                price = shown.price
                            )
                        )
                        save(items - shown)
                        setOpened(null)
                    }
                )
            } else {
                // The same control in the large header and in the compact bar, so
                // refreshing prices stays reachable once you are down the grid.
                val refreshAction: @Composable () -> Unit = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                refreshing = true
                                val fetched = items.map { runCatching { refreshed(it) }.getOrNull() }
                                val result = applyFollowed(items, fetched)
                                save(result.wishes)
                                refreshing = false
                                message = refreshMessage(result.updated, items.size)
                            }
                        },
                        enabled = !refreshing && items.isNotEmpty()
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                        } else {
                            Icon(Icons.Default.Refresh, "Оновити ціни", tint = TextSecondary)
                        }
                    }
                }
                val gridState = rememberLazyGridState()
                Box {
                    // Two columns, the way a shop lists goods. One wish per full-width
                    // row meant a photograph, a chart and four lines of text for every
                    // item, and half a screen spent on one of them.
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = Space.screen,
                            end = Space.screen,
                            // The navigation bar is translucent and the grid runs
                            // under it, so its height is left here rather than cut
                            // out of the scaffold.
                            bottom = navClearance() + Space.fabClearance
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Space.md),
                        verticalArrangement = Arrangement.spacedBy(Space.md)
                    ) {
                        // Item zero is the header alone: that is the block the compact
                        // bar measures to know when to take over.
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ScreenHeader(
                                kicker = "FLOWPAY",
                                title = "Мої бажання",
                                subtitle = "Ціна, ціль та історія в одному місці",
                                // The grid already supplies the screen margin.
                                inset = 0.dp,
                                trailing = refreshAction
                            )
                        }
                        message?.let { text ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text,
                                    Modifier.padding(bottom = Space.lg),
                                    color = TextSecondary,
                                    fontSize = Type.captionSize
                                )
                            }
                        }
                        if (items.size > 1) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                // Five chips in a row ran off the right edge with nothing
                                // to say they scrolled, so the last options were simply
                                // invisible. One line names the current order instead.
                                var sortOpen by remember { mutableStateOf(false) }
                                Box {
                                    Row(
                                        Modifier.clickable { sortOpen = true },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Сортування",
                                            color = TextSecondary,
                                            fontSize = Type.captionSize
                                        )
                                        Spacer(Modifier.width(Space.sm))
                                        Text(
                                            sort.label,
                                            color = Accent,
                                            fontSize = Type.captionSize,
                                            fontWeight = Type.medium
                                        )
                                        Icon(
                                            Icons.Default.ArrowDropDown,
                                            "Змінити порядок",
                                            tint = Accent
                                        )
                                    }
                                    DropdownMenu(sortOpen, { sortOpen = false }) {
                                        WishSort.entries.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option.label) },
                                                onClick = {
                                                    sort = option
                                                    store.saveWishSort(option)
                                                    sortOpen = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (items.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                EmptyInvite(
                                    "Ще нічого не хочеться",
                                    "Вставте посилання на товар. FlowPay візьме назву, фото й ціну, " +
                                        "далі стежить за ціною сам і скаже, коли вигідно купувати."
                                )
                            }
                        }
                        items(sortWishes(items, sort), key = { it.id }) { wish ->
                            WishCard(wish, this@AnimatedContent) { setOpened(wish.id) }
                        }
                    }
                    CollapsingTitle("Мої бажання", gridState, trailing = refreshAction)
                }
            }
        }
    }
    if (adding) AddWishSheet({ setAdding(false) }, { wish -> save(items + wish); setAdding(false) })
    editing?.let { selected ->
        EditWishSheet(selected, { editing = null }) { changed ->
            save(items.map { if (it.id == changed.id) changed else it })
            editing = null
        }
    }
}

@Composable
fun AddWishSheet(close: () -> Unit, add: (Wish) -> Unit) {
    var link by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Інше") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FormSheet(
        title = "Новий товар",
        confirmLabel = if (loading) "Зчитую…" else "Додати",
        confirmEnabled = isSupportedWebUrl(link) && !loading,
        onConfirm = {
            scope.launch {
                loading = true; error = null
                runCatching { product(link).copy(targetPrice = target.replace(',', '.').toDoubleOrNull() ?: 0.0, category = category) }
                    .onSuccess(add).onFailure { error = it.message ?: "Не вдалося прочитати сторінку" }
                loading = false
            }
        },
        onDismiss = close
    ) {
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Посилання на товар") })
        NumberField("Цільова ціна, ₴ (необов'язково)", target) { target = it }
        OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth().padding(top = Space.md), label = { Text("Категорія") })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Space.sm)) }
    }
}

@Composable
fun EditWishSheet(wish: Wish, close: () -> Unit, save: (Wish) -> Unit) {
    var name by remember { mutableStateOf(wish.name) }
    var target by remember { mutableStateOf(wish.targetPrice.takeIf { it > 0 }?.toString().orEmpty()) }
    var category by remember { mutableStateOf(wish.category) }
    FormSheet(
        title = "Редагувати товар",
        confirmLabel = "Зберегти",
        confirmEnabled = true,
        onConfirm = {
            save(
                wish.copy(
                    name = name.ifBlank { wish.name },
                    targetPrice = target.replace(',', '.').toDoubleOrNull() ?: 0.0,
                    category = category.ifBlank { "Інше" }
                )
            )
        },
        onDismiss = close
    ) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Назва") })
        NumberField("Цільова ціна, ₴", target) { target = it }
        OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth().padding(top = Space.md), label = { Text("Категорія") })
    }
}

/** A heading that sits closer to its own content than to whatever came before. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = Space.screen).padding(top = Space.xxl, bottom = Space.md),
        fontSize = Type.sectionSize,
        lineHeight = Type.sectionLine,
        fontWeight = Type.medium
    )
}

/** One number of the savings plan, muted while there is nothing to show yet. */
@Composable
fun PlanTile(label: String, value: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
        shape = Radius.sm
    ) {
        Column(Modifier.padding(Space.lg)) {
            Text(label, color = TextSecondary, fontSize = Type.captionSize)
            Spacer(Modifier.height(Space.xs))
            Text(
                value,
                fontSize = Type.sectionSize,
                lineHeight = Type.sectionLine,
                fontWeight = if (muted) Type.regular else Type.strong,
                color = if (muted) TextDisabled else TextPrimary
            )
        }
    }
}

/**
 * The page behind a wishlist card.
 *
 * It exists to answer one question the list cannot: what would it take to actually
 * buy this. The plan works from the target price when one is set, and from the
 * current price otherwise, so the number on screen is always the sum that matters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTransitionScope.WishDetailScreen(
    wish: Wish,
    visibility: AnimatedVisibilityScope,
    context: Context,
    onBack: () -> Unit,
    onChange: (Wish) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    freeCash: Double,
    onBought: (String) -> Unit
) {
    // Keyed on the item, so opening a different one does not inherit these boxes.
    var savedText by remember(wish.id) { mutableStateOf(amountText(wish.saved)) }
    var monthlyText by remember(wish.id) { mutableStateOf(amountText(wish.monthlyPlan)) }
    var deadlineDay by remember(wish.id) { mutableLongStateOf(wish.deadline) }
    // Which end of the plan is known: the monthly sum, or the date.
    var byDate by remember(wish.id) { mutableStateOf(wish.deadline > 0L) }
    var pickingDate by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val today = remember { LocalDate.now() }
    val goal = wishGoal(wish)
    val deadlineDate = if (deadlineDay > 0L) LocalDate.ofEpochDay(deadlineDay) else null
    val monthsLeft = deadlineDate?.let { monthsUntil(today, it) } ?: 0
    val plan = if (byDate && deadlineDate != null) {
        deadlinePlan(goal, parseAmount(savedText), today, deadlineDate)
    } else {
        savingsPlan(goal, parseAmount(savedText), parseAmount(monthlyText))
    }
    val change = priceChangePercent(wish)
    val insight = priceInsight(wish.history, wish.price, wish.checkedDay)

    // Persist only when something the user typed or picked actually changed.
    LaunchedEffect(savedText, monthlyText, deadlineDay) {
        val saved = parseAmount(savedText)
        val monthly = parseAmount(monthlyText)
        if (saved != wish.saved || monthly != wish.monthlyPlan || deadlineDay != wish.deadline) {
            onChange(wish.copy(saved = saved, monthlyPlan = monthly, deadline = deadlineDay))
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = navClearance() + Space.huge)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = Space.sm, end = Space.sm, top = Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = TextPrimary)
                }
                Text(
                    wish.category.uppercase(),
                    color = Accent,
                    fontSize = Type.overlineSize,
                    fontWeight = Type.strong,
                    letterSpacing = Type.overlineTracking
                )
                Spacer(Modifier.weight(1f))
                IconButton(onEdit) { Icon(Icons.Default.Edit, "Редагувати", tint = TextSecondary) }
                IconButton(onDelete) { Icon(Icons.Default.DeleteOutline, "Видалити", tint = TextSecondary) }
            }

            if (wish.image.isNotBlank()) {
                PhotoHeader(
                    imageUrl = wish.image,
                    description = wish.name,
                    modifier = Modifier.padding(horizontal = Space.screen),
                    height = 240.dp,
                    overlayNumber = "%+.0f%%".format(change)
                    .takeIf { change <= -1.0 && wish.history.size > 1 },
                    chip = verdictLabel(insight.verdict)
                        .takeIf { insight.verdict != BuyVerdict.UNKNOWN },
                    chipIcon = Icons.Default.Bolt,
                    chipColor = when (insight.verdict) {
                        BuyVerdict.GOOD -> Accent
                        BuyVerdict.POOR -> Negative
                        else -> TextPrimary
                    }
                ) { imageModifier ->
                    AsyncImage(
                        wish.image, wish.name,
                        imageModifier
                            .sharedElement(
                                rememberSharedContentState("wish-photo-${wish.id}"),
                                animatedVisibilityScope = visibility
                            )
                            .clip(Radius.lg)
                            .background(SurfaceRaised),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Column(Modifier.padding(horizontal = Space.screen).padding(top = Space.lg)) {
                Text(
                    wish.name,
                    fontSize = Type.sectionSize,
                    lineHeight = Type.sectionLine,
                    fontWeight = Type.medium
                )
                Spacer(Modifier.height(Space.sm))
                Row(verticalAlignment = Alignment.Bottom) {
                    FigureWithTarget(
                        value = money(wish.price),
                        target = wish.targetPrice.takeIf { it > 0 }?.let { "ціль ${money(it)}" },
                        valueSize = Type.heroSize
                    )
                    Spacer(Modifier.width(Space.md))
                    Text(
                        "%+.1f%%".format(change),
                        color = if (change <= 0) Accent else Negative,
                        fontSize = Type.captionSize,
                        fontWeight = Type.strong,
                        modifier = Modifier.padding(bottom = Space.sm)
                    )
                }
            }

            SectionTitle("План накопичення")
            Column(Modifier.padding(horizontal = Space.screen)) {
                HeroPanel(
                    label = if (plan.reached) "Сума зібрана" else "Залишилось зібрати",
                    value = money(plan.remaining),
                    caption = "${money(plan.saved)} з ${money(plan.goal)}",
                    muted = plan.reached,
                    trailing = {
                        ProgressRing(plan.progress, diameter = 84.dp, stroke = 9.dp) {
                            Text(
                                "${(plan.progress * 100).toInt()}%",
                                color = if (plan.reached) TextSecondary else AccentInk,
                                fontSize = Type.captionSize,
                                fontWeight = Type.strong
                            )
                        }
                    }
                )

                NumberField("Вже відкладено, ₴", savedText) { savedText = it }

                // The plan can be read from either end. Say what you can put aside
                // and it answers when; say when you want it and it answers how much.
                Row(
                    Modifier.fillMaxWidth().padding(top = Space.lg),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    FilterChip(
                        !byDate,
                        { byDate = false; deadlineDay = 0L },
                        { Text("Знаю суму", fontSize = Type.captionSize) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        byDate,
                        { byDate = true },
                        { Text("Знаю дату", fontSize = Type.captionSize) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // The link between the two halves of the app: what the expenses
                // screen says is spare is the most that can go here each month.
                if (!byDate && freeCash > 0) {
                    val fromFree = savingsPlan(goal, parseAmount(savedText), freeCash)
                    Row(
                        Modifier.fillMaxWidth().padding(top = Space.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Вільно після витрат ${money(freeCash)} на місяць",
                                color = TextSecondary,
                                fontSize = Type.captionSize,
                                lineHeight = Type.captionLine
                            )
                            if (!fromFree.reached) {
                                Text(
                                    "цією сумою — ${monthsLabel(fromFree.months)}",
                                    color = TextSecondary,
                                    fontSize = Type.captionSize
                                )
                            }
                        }
                        TextButton({ monthlyText = amountText(freeCash) }) { Text("Взяти") }
                    }
                }

                if (byDate) {
                    OutlinedButton(
                        { pickingDate = true },
                        Modifier.fillMaxWidth().padding(top = Space.md),
                        shape = Radius.sm,
                        border = BorderStroke(1.dp, HairLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Icon(Icons.Default.CalendarMonth, null)
                        Text(
                            if (deadlineDate != null) "  Купити до ${formatDate(deadlineDate)}"
                            else "  Обрати дату покупки"
                        )
                    }
                } else {
                    NumberField("Відкладаю щомісяця, ₴", monthlyText) { monthlyText = it }
                }

                Spacer(Modifier.height(Space.lg))
                when {
                    plan.reached -> PlanTile(
                        "Можна купувати",
                        "Гроші вже є",
                        Modifier.fillMaxWidth()
                    )

                    byDate && deadlineDate == null -> PlanTile(
                        "Скільки відкладати",
                        "Оберіть дату покупки",
                        Modifier.fillMaxWidth(),
                        muted = true
                    )

                    byDate && monthsLeft == 0 -> PlanTile(
                        "Менше місяця до дати",
                        "Потрібно ${money(plan.remaining)} одразу",
                        Modifier.fillMaxWidth()
                    )

                    plan.needsRate -> PlanTile(
                        "Скільки чекати",
                        "Впишіть щомісячну суму",
                        Modifier.fillMaxWidth(),
                        muted = true
                    )

                    byDate -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            PlanTile("Відкладати щомісяця", money(plan.monthly), Modifier.weight(1f))
                            PlanTile("Внесків до дати", monthsLabel(monthsLeft), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(Space.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            PlanTile("Це щотижня", money(plan.weekly), Modifier.weight(1f))
                            PlanTile("Це щодня", money(plan.daily), Modifier.weight(1f))
                        }
                    }

                    else -> {
                        // The daily figure leads because it is the one people act on.
                        // Field work on a savings app found the same amount framed
                        // per day rather than per month quadrupled sign-ups.
                        PlanTile("Це ${money(plan.daily)} на день", monthsLabel(plan.months), Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Space.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            PlanTile("Щотижня", money(plan.weekly), Modifier.weight(1f))
                            PlanTile(
                                "Готово",
                                formatDate(readyDate(plan.months, today)),
                                Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            SectionTitle("Історія ціни")
            Column(Modifier.padding(horizontal = Space.screen)) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
                    shape = Radius.md
                ) {
                    Column(Modifier.padding(Space.lg)) {
                        PriceBars(wish.history, Modifier.fillMaxWidth().height(120.dp))
                        Spacer(Modifier.height(Space.md))
                        Text(
                            verdictLabel(insight.verdict),
                            color = when (insight.verdict) {
                                BuyVerdict.GOOD -> Accent
                                BuyVerdict.POOR -> Negative
                                else -> TextSecondary
                            },
                            fontSize = Type.cardTitleSize,
                            fontWeight = Type.medium
                        )
                        Text(
                            when (insight.verdict) {
                                BuyVerdict.UNKNOWN ->
                                    "Потрібно щонайменше два тижні спостережень і дві зміни ціни"
                                BuyVerdict.GOOD ->
                                    if (insight.atLowest) "Це найнижча ціна за весь час спостережень"
                                    else "Ціна в нижній частині свого діапазону"
                                BuyVerdict.FAIR -> "Ціна в середині свого діапазону"
                                BuyVerdict.POOR ->
                                    "Раніше ціна опускалась на ${"%.0f".format(insight.offHighest)}% нижче за максимум"
                            },
                            color = TextSecondary,
                            fontSize = Type.captionSize,
                            lineHeight = Type.captionLine,
                            modifier = Modifier.padding(top = Space.xs)
                        )
                        if (insight.changes > 1) {
                            val lowDay = lowestPointDay(wish.history)
                            Text(
                                "Найнижча ${money(insight.lowest)}" +
                                    (lowDay?.let { " — ${formatDate(LocalDate.ofEpochDay(it))}" } ?: "") +
                                    " · найвища ${money(insight.highest)}",
                                color = TextSecondary,
                                fontSize = Type.captionSize,
                                lineHeight = Type.captionLine,
                                modifier = Modifier.padding(top = Space.sm)
                            )
                        }
                        Text(
                            listOfNotNull(
                                changesLabel(insight.changes),
                                insight.daysTracked.takeIf { it > 0 }?.let { daysLabel(it) }
                            ).joinToString(" за "),
                            color = TextDisabled,
                            fontSize = Type.captionSize
                        )
                    }
                }

                Spacer(Modifier.height(Space.xl))
                // Buying is what the whole page is for, so it gets the filled button
                // and the full width. Everything else here is secondary.
                Button(
                    { buying = true },
                    Modifier.fillMaxWidth(),
                    shape = Radius.sm
                ) {
                    Icon(Icons.Default.ShoppingCartCheckout, null)
                    Text("  Я купив це")
                }
                Spacer(Modifier.height(Space.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    OutlinedButton(
                        {
                            scope.launch {
                                refreshing = true
                                message = null
                                runCatching { refreshed(wish) }
                                    .onSuccess { updated ->
                                        if (updated == null) {
                                            message = "Цей варіант більше не вказано на сторінці"
                                        } else {
                                            onChange(updated)
                                            message = "Ціну оновлено"
                                        }
                                    }
                                    .onFailure { message = "Не вдалося прочитати сторінку" }
                                refreshing = false
                            }
                        },
                        Modifier.weight(1f),
                        enabled = !refreshing,
                        shape = Radius.sm,
                        border = BorderStroke(1.dp, HairLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                        } else {
                            Icon(Icons.Default.Refresh, null)
                        }
                        Text(" Оновити")
                    }
                    OutlinedButton(
                        { context.startActivity(Intent(Intent.ACTION_VIEW, wish.url.toUri())) },
                        Modifier.weight(1f),
                        shape = Radius.sm,
                        border = BorderStroke(1.dp, HairLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) { Text("До магазину ↗") }
                }
                message?.let {
                    Text(
                        it,
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        modifier = Modifier.padding(top = Space.md)
                    )
                }
            }
        }
    }

    if (buying) {
        BoughtDialog(wish, { buying = false }) { trackingNumber ->
            buying = false
            onBought(trackingNumber)
        }
    }

    if (pickingDate) {
        val millisPerDay = 86_400_000L
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (deadlineDate ?: today.plusMonths(3)).toEpochDay() * millisPerDay,
            selectableDates = object : SelectableDates {
                // A deadline in the past cannot be planned for.
                override fun isSelectableDate(utcTimeMillis: Long) =
                    utcTimeMillis / millisPerDay >= today.toEpochDay()
            }
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton({
                    // The picker works in UTC midnights, so this is an exact day.
                    state.selectedDateMillis?.let { deadlineDay = it / millisPerDay }
                    pickingDate = false
                }) { Text("Обрати") }
            },
            dismissButton = { TextButton({ pickingDate = false }) { Text("Скасувати") } }
        ) {
            DatePicker(state)
        }
    }
}

/**
 * One wish in a two-column grid.
 *
 * Shaped like a shop listing rather than a report: a square photograph, the name
 * in two lines, the price. The chart, the savings plan and how stale the reading
 * is all live on the item's own page, one tap away. Carrying them here cost half
 * a screen for a single item.
 */
@Composable
fun SharedTransitionScope.WishCard(
    wish: Wish,
    visibility: AnimatedVisibilityScope,
    onOpen: () -> Unit
) {
    val change = priceChangePercent(wish)
    val plan = savingsPlan(wishGoal(wish), wish.saved, wish.monthlyPlan)
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceBase),
        shape = Radius.md
    ) {
        Box {
            if (wish.image.isNotBlank()) {
                AsyncImage(
                    wish.image,
                    wish.name,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .sharedElement(
                            rememberSharedContentState("wish-photo-${wish.id}"),
                            animatedVisibilityScope = visibility
                        )
                        .background(SurfaceRaised),
                    // Cropped square rather than the whole photograph: shop pictures
                    // come in every proportion, and a grid only holds together when
                    // every tile is the same shape.
                    contentScale = ContentScale.Crop
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(AppBackground.copy(alpha = 0.14f))
                )
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(SurfaceRaised))
            }
            // The one thing worth knowing without opening the item: it got cheaper.
            if (wish.history.size > 1 && change <= -1.0) {
                Text(
                    "%+.0f%%".format(change),
                    color = AccentInk,
                    fontSize = Type.captionSize,
                    fontWeight = Type.strong,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Space.sm)
                        .background(Accent, Radius.pill)
                        .padding(horizontal = Space.sm, vertical = 2.dp)
                )
            }
        }
        Column(Modifier.padding(Space.md)) {
            Text(
                wish.name,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(Space.xs))
            Text(money(wish.price), fontSize = Type.cardTitleSize, fontWeight = Type.strong)
            if (wish.targetPrice > 0) {
                Text(
                    "ціль ${money(wish.targetPrice)}",
                    color = TextSecondary,
                    fontSize = Type.overlineSize
                )
            }
            if (wish.saved > 0 || wish.monthlyPlan > 0) {
                Spacer(Modifier.height(Space.sm))
                PillProgress(plan.progress, height = 4.dp)
            }
        }
    }
}


@Composable
fun CalculatorScreen(store: Store) {
    var amount by remember { mutableStateOf("") }
    var hryvniaToDollar by remember { mutableStateOf(true) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf("+") }
    val cached = remember { store.fxRate() }
    var rate by remember { mutableStateOf(cached.first) }
    var fetchedAt by remember { mutableLongStateOf(cached.second) }
    var loading by remember { mutableStateOf(false) }
    var rateError by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(store.rateHistory()) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            rateError = false
            runCatching { usdRate() }
                .onSuccess { fresh ->
                    if (fresh.sell > 0) {
                        rate = fresh
                        fetchedAt = System.currentTimeMillis()
                        store.saveFxRate(fresh, fetchedAt)
                        // The series the chart draws. Recorded here rather than only
                        // on a schedule, so a phone that is opened daily builds a
                        // month of history whether or not background work ran.
                        history = appendRate(history, fresh.sell, LocalDate.now().toEpochDay())
                        store.saveRateHistory(history)
                    } else {
                        rateError = true
                    }
                }
                .onFailure { rateError = true }
            loading = false
        }
    }

    // Only reach for the network when the cached rate is actually stale. The
    // refresh button always asks, which is what it is for.
    LaunchedEffect(Unit) {
        val age = System.currentTimeMillis() - fetchedAt
        if (rate.sell <= 0 || age > 30 * 60 * 1000L) refresh()
    }

    val source = amount.replace(',', '.').toDoubleOrNull() ?: 0.0
    val exchangeRate = if (hryvniaToDollar) rate.sell else rate.buy
    val converted = when {
        exchangeRate <= 0 -> 0.0
        hryvniaToDollar -> source / exchangeRate
        else -> source * exchangeRate
    }
    val a = first.replace(',', '.').toDoubleOrNull() ?: 0.0
    val b = second.replace(',', '.').toDoubleOrNull() ?: 0.0
    val total = when (operation) {
        "−" -> a - b
        "×" -> a * b
        "÷" -> if (b == 0.0) 0.0 else a / b
        else -> a + b
    }

    val listState = rememberLazyListState()
    Box {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = navClearance() + Space.huge)
        ) {
            // Item zero is the header alone: that is the block the compact bar watches.
            item { ScreenHeader("MONOBANK", "Курс і суми", "Конвертація валют та швидкі розрахунки") }
            item {
                Column(Modifier.padding(horizontal = Space.screen)) {
                    Card(shape = Radius.lg, colors = CardDefaults.cardColors(containerColor = SurfaceRaised)) {
                        Column(Modifier.padding(Space.lg)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("USD / UAH", color = TextSecondary, fontSize = Type.captionSize)
                                    Text(rateHeadline(rate), fontWeight = FontWeight.Bold)
                                    // Never the figure without its source: the official
                                    // rate and a bank's rate differ by most of a hryvnia,
                                    // and an unlabelled number invites reading one as the
                                    // other.
                                    rateSourceLabel(rate).takeIf { it.isNotBlank() }?.let {
                                        Text(it, color = TextSecondary, fontSize = Type.captionSize)
                                    }
                                    if (fetchedAt > 0) {
                                        Text(
                                            "станом на ${timeLabel(fetchedAt)}" +
                                                if (rateError) " · оновити не вдалося" else "",
                                            color = if (rateError) Negative else TextSecondary,
                                            fontSize = Type.captionSize
                                        )
                                    } else if (rateError) {
                                        Text(
                                            "Ні Monobank, ні НБУ не відповіли, спробуйте пізніше",
                                            color = Negative,
                                            fontSize = Type.captionSize
                                        )
                                    }
                                }
                                IconButton({ refresh() }) {
                                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                                    else Icon(Icons.Default.Refresh, "Оновити", tint = TextSecondary)
                                }
                            }
                            NumberField(if (hryvniaToDollar) "Сума у гривнях" else "Сума у доларах", amount) { amount = it }
                            // Secondary action, so an outline rather than a second filled
                            // shape. The lime is spent on the one figure below.
                            OutlinedButton(
                                { hryvniaToDollar = !hryvniaToDollar },
                                Modifier.fillMaxWidth().padding(top = Space.md),
                                shape = Radius.sm,
                                border = BorderStroke(1.dp, HairLine),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                            ) {
                                Icon(Icons.Default.SwapVert, null)
                                Text(if (hryvniaToDollar) " UAH → USD" else " USD → UAH")
                            }
                            Spacer(Modifier.height(Space.lg))
                            HeroPanel(
                                label = if (hryvniaToDollar) "У доларах" else "У гривнях",
                                value = if (hryvniaToDollar) "${"%.2f".format(converted)} USD" else money(converted),
                                muted = converted == 0.0,
                                // A short number left the right half of the panel empty.
                                // The rate it was converted at belongs there: it is the
                                // one thing you would otherwise scroll up to check.
                                trailing = if (exchangeRate > 0) {
                                    {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                "за курсом",
                                                color = AccentInk.copy(alpha = 0.65f),
                                                fontSize = Type.captionSize
                                            )
                                            Text(
                                                rateFigure(exchangeRate),
                                                color = AccentInk,
                                                fontSize = Type.sectionSize,
                                                lineHeight = Type.sectionLine,
                                                fontWeight = Type.strong
                                            )
                                        }
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                    }

                    Text(
                        "Курс за місяць",
                        Modifier.padding(top = Space.xxl, bottom = Space.md),
                        fontSize = Type.sectionSize,
                        lineHeight = Type.sectionLine,
                        fontWeight = Type.medium
                    )
                    Card(shape = Radius.lg, colors = CardDefaults.cardColors(containerColor = SurfaceBase)) {
                        Column(Modifier.padding(Space.lg)) {
                            if (history.isNotEmpty()) {
                                PriceBars(history, Modifier.fillMaxWidth().height(120.dp))
                                Spacer(Modifier.height(Space.sm))
                            }
                            // Nothing recorded the rate before this version, so the chart
                            // says how many days it has actually seen rather than letting
                            // four bars pass for a month.
                            Text(
                                rateHistoryNote(history, LocalDate.now().toEpochDay()),
                                color = TextSecondary,
                                fontSize = Type.captionSize,
                                lineHeight = Type.captionLine
                            )
                            rateRangeNote(history)?.let {
                                Spacer(Modifier.height(Space.sm))
                                LeaderRow("Від і до", it)
                            }
                        }
                    }

                    // A section heading sits closer to its own content than to what came
                    // before it, so the gap above is larger than the gap below.
                    Text(
                        "Калькулятор сум",
                        Modifier.padding(top = Space.xxl, bottom = Space.md),
                        fontSize = Type.sectionSize,
                        lineHeight = Type.sectionLine,
                        fontWeight = Type.medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        Box(Modifier.weight(1f)) { NumberField("Перша сума", first) { first = it } }
                        Box(Modifier.weight(1f)) { NumberField("Друга сума", second) { second = it } }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Space.md),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm)
                    ) {
                        listOf("+", "−", "×", "÷").forEach { symbol ->
                            FilterChip(
                                operation == symbol,
                                { operation = symbol },
                                { Text(symbol, fontSize = Type.sectionSize) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    SummaryCard(
                        "Результат",
                        NumberFormat.getNumberInstance(Locale("uk", "UA")).format(total),
                        total == 0.0,
                        hero = false
                    )
                }
            }
        }
        CollapsingTitle("Курс і суми", listState)
    }
}

@Composable
fun PaymentsScreen(
    items: List<Pay>,
    save: (List<Pay>) -> Unit,
    store: Store,
    adding: Boolean,
    setAdding: (Boolean) -> Unit
) {
    // The rate the exchange screen already fetched and cached. Dollar entries are
    // converted at the sell rate, since that is what buying dollars costs.
    val rate = remember { store.fxRate().first }
    val monthly = monthlyTotal(items, rate.sell)
    val yearly = yearlyTotal(items, rate.sell)
    var income by remember { mutableDoubleStateOf(store.income()) }
    var editingIncome by remember { mutableStateOf(false) }
    val month = budget(income, monthly)
    var editing by remember { mutableStateOf<Int?>(null) }
    // Read once, so the timeline and the strip cannot disagree about which day it is.
    val today = remember { LocalDate.now() }
    val listState = rememberLazyListState()
    Box {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = navClearance() + Space.fabClearance)
        ) {
            // Item zero is the header alone: that is the block the compact bar watches.
            item { ScreenHeader("ЩОМІСЯЦЯ", "Постійні витрати", "Оренда, комуналка, зв'язок і підписки") }
            item {
                Column(Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xl)) {
                    // The loudest figure should be one you can act on. A monthly total is
                    // read and forgotten; the next payment is prepared for, so it takes
                    // the panel and the total moves down into the summary rows.
                    val next = nextPayment(items, today, rate.sell)
                    HeroPanel(
                        label = if (next != null) {
                            "Найближчий платіж · ${dueLabel(next.daysAway)}"
                        } else {
                            "Разом на місяць"
                        },
                        value = money(next?.total?.total ?: monthly.total),
                        caption = when {
                            next == null -> null
                            next.total.rateMissing ->
                                "${dayMonth(next.date)} · плюс ${dollars(next.total.usd)}, курс ще не завантажено"
                            else -> "${dayMonth(next.date)} · ${dueSummary(next.items)}"
                        },
                        muted = next == null
                    )
                    if (items.isNotEmpty()) {
                        Spacer(Modifier.height(Space.md))
                        DaysStrip(days = 30, marked = paymentOffsets(items, today))
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            "Списання у найближчі 30 днів",
                            color = TextSecondary,
                            fontSize = Type.captionSize
                        )
                    }
                    Spacer(Modifier.height(Space.md))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceBase),
                        shape = Radius.md
                    ) {
                        Column(Modifier.padding(Space.lg)) {
                            Row(
                                Modifier.fillMaxWidth().clickable { editingIncome = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (month.unknown) "Вкажіть дохід" else "Вільно на місяць",
                                        color = TextSecondary,
                                        fontSize = Type.captionSize
                                    )
                                    Spacer(Modifier.height(Space.xs))
                                    Text(
                                        when {
                                            month.unknown -> "щоб бачити, скільки лишається"
                                            else -> money(month.free)
                                        },
                                        fontSize = if (month.unknown) Type.bodySize else Type.sectionSize,
                                        lineHeight = Type.sectionLine,
                                        fontWeight = if (month.unknown) Type.regular else Type.strong,
                                        color = when {
                                            month.unknown -> TextDisabled
                                            month.overspent -> Negative
                                            else -> Accent
                                        }
                                    )
                                    if (!month.unknown) {
                                        Text(
                                            if (month.overspent) "Витрати перевищують дохід ${money(month.income)}"
                                            else "З доходу ${money(month.income)}",
                                            color = TextSecondary,
                                            fontSize = Type.captionSize
                                        )
                                    }
                                }
                                Icon(Icons.Default.Edit, "Змінити дохід", tint = TextSecondary)
                            }
                            if (items.isNotEmpty()) {
                                Spacer(Modifier.height(Space.sm))
                                LeaderRow("Разом на місяць", money(monthly.total))
                                // A year of the same costs, because that is the scale at
                                // which a subscription is worth arguing with.
                                LeaderRow("Разом на рік", money(yearly.total))
                                if (monthly.rateMissing) {
                                    // Both totals are short by this much, so it is said as
                                    // a gap rather than folded in as a smaller number.
                                    LeaderRow(
                                        "Плюс ${dollars(yearly.usd)} на рік",
                                        "курс ще не завантажено"
                                    )
                                } else if (monthly.hasUsd) {
                                    LeaderRow(
                                        "З них ${dollars(yearly.usd)} на рік",
                                        "≈ ${approxMoney(yearly.usdInUah)}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                item {
                    PlaceholderRows(
                        listOf(
                            "оренда, комуналка" to "сума і день оплати",
                            "інтернет, підписки" to "сума і день оплати"
                        ),
                        Modifier.padding(horizontal = Space.screen)
                    )
                }
            }
            // A timeline rather than a list: the date is said once for everything
            // falling on it, instead of "1 числа щомісяця" repeated under every row.
            paymentGroups(items, today).forEach { group ->
                item(key = group.date.toString()) {
                    val isToday = group.date == today
                    Card(
                        Modifier.padding(horizontal = Space.screen, vertical = Space.xs).fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isToday) AccentSoft else SurfaceBase
                        ),
                        shape = Radius.md
                    ) {
                        Column(Modifier.padding(Space.lg)) {
                            Text(
                                if (isToday) "сьогодні · ${dayMonth(group.date)}" else dayMonth(group.date),
                                color = Accent,
                                fontSize = Type.captionSize,
                                fontWeight = Type.medium
                            )
                            group.positions.forEach { position ->
                                val pay = items[position]
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { editing = position }
                                        .padding(vertical = Space.sm),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconChip(payIcon(pay.name))
                                    Spacer(Modifier.width(Space.md))
                                    // No dotted leader here. Two weighted children split
                                    // the row in half, which cut "Оренда квартири" down to
                                    // "Оренда к…" — and a name earns that space before a
                                    // decoration does.
                                    Column(Modifier.weight(1f).padding(end = Space.md)) {
                                        Text(
                                            pay.name,
                                            fontSize = Type.cardTitleSize,
                                            fontWeight = Type.medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        // The annual figure is the one that changes minds
                                        // about a subscription, so it rides with the name
                                        // rather than waiting on another screen.
                                        Text(
                                            annualLabel(pay),
                                            color = TextSecondary,
                                            fontSize = Type.captionSize,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            amountLabel(pay.amount, pay.currency),
                                            fontWeight = Type.strong
                                        )
                                        if (pay.currency == USD && rate.sell > 0) {
                                            Text(
                                                "≈ ${approxMoney(pay.amount * rate.sell)}",
                                                color = TextSecondary,
                                                fontSize = Type.captionSize
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        CollapsingTitle("Постійні витрати", listState)
    }
    if (adding) AddPaymentSheet({ setAdding(false) }) {
        save(items + it)
        setAdding(false)
    }
    if (editingIncome) {
        IncomeDialog(income, { editingIncome = false }) { value ->
            income = value
            store.saveIncome(value)
            editingIncome = false
        }
    }
    editing?.let { index ->
        items.getOrNull(index)?.let { pay ->
            EditPaymentSheet(
                pay,
                close = { editing = null },
                delete = {
                    save(items.filterIndexed { i, _ -> i != index })
                    editing = null
                }
            ) { changed ->
                save(items.mapIndexed { i, item -> if (i == index) changed else item })
                editing = null
            }
        }
    }
}

/** A recurring expense is recognised by its name, since that is all it carries. */
fun payIcon(name: String) = when {
    name.contains("Оренда", true) -> Icons.Default.Home
    name.contains("Комун", true) -> Icons.Default.Bolt
    name.contains("Інтернет", true) -> Icons.Default.Wifi
    name.contains("Мобіл", true) -> Icons.Default.Smartphone
    else -> Icons.Default.Autorenew
}

@Composable
fun AddPaymentSheet(close: () -> Unit, add: (Pay) -> Unit) {
    // The chips fill the name in, they are not the name. Two subscriptions are
    // rarely the same subscription, so the field is always present and always
    // editable: tapping a chip simply types the word for you.
    val presets = listOf("Оренда квартири", "Комуналка", "Інтернет", "Мобільний", "Підписка")
    var name by remember { mutableStateOf(presets.first()) }
    var amount by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("1") }
    var currency by remember { mutableStateOf(UAH) }
    var warnDays by remember { mutableIntStateOf(DEFAULT_WARN_DAYS) }
    FormSheet(
        title = "Нова постійна витрата",
        confirmLabel = "Додати",
        confirmEnabled = parseAmount(amount) > 0 && name.isNotBlank(),
        onConfirm = {
            parseAmount(amount).takeIf { it > 0 }?.let { value ->
                add(
                    Pay(
                        name.trim().ifBlank { "Інше" },
                        value,
                        day.toIntOrNull()?.coerceIn(1, 31) ?: 1,
                        currency,
                        warnDays
                    )
                )
            }
        },
        onDismiss = close
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            items(presets) { preset ->
                FilterChip(name == preset, { name = preset }, { Text(preset) })
            }
        }
        OutlinedTextField(
            name,
            { name = it },
            Modifier.fillMaxWidth(),
            label = { Text("Назва") },
            singleLine = true
        )
        CurrencySegments(currency) { currency = it }
        NumberField(if (currency == USD) "Сума, $" else "Сума, ₴", amount) { amount = it }
        NumberField("День оплати", day) { day = it }
        WarnDaysChips(warnDays) { warnDays = it }
    }
}

@Composable
fun OrdersScreen(
    items: List<Order>,
    save: (List<Order>) -> Unit,
    context: Context,
    adding: Boolean,
    setAdding: (Boolean) -> Unit
) {
    var tracking by remember { mutableStateOf<Order?>(null) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val trackable = items.count { detectCarrier(it.tracking) == CARRIER_NOVA_POSHTA }

    fun checkAll() {
        scope.launch {
            checking = true
            message = null
            var moved = 0
            val now = System.currentTimeMillis()
            val fresh = items.map { order ->
                if (detectCarrier(order.tracking) != CARRIER_NOVA_POSHTA) return@map order
                val status = runCatching { parcelStatus(order.tracking) }.getOrNull()
                    ?: return@map order
                if (status.stage.isNotBlank() && status.stage != order.status) moved++
                applyStatus(order, status, now)
            }
            save(fresh)
            checking = false
            message = when {
                trackable == 0 -> "Немає номерів Нової Пошти для перевірки"
                moved > 0 -> "Оновлено, змінилось статусів: $moved"
                else -> "Перевірено, змін немає"
            }
        }
    }

    // The same control in the large header and in the compact bar, so checking
    // statuses does not become unreachable once you are down the list.
    val checkAction: @Composable () -> Unit = {
        IconButton(onClick = { checkAll() }, enabled = !checking && trackable > 0) {
            if (checking) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
            } else {
                Icon(Icons.Default.Sync, "Перевірити статуси", tint = TextSecondary)
            }
        }
    }
    val listState = rememberLazyListState()
    Box {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = navClearance() + Space.fabClearance)
        ) {
            // Item zero is the header alone: that is the block the compact bar watches.
            item {
                ScreenHeader(
                    "ДОСТАВКА", "Мої покупки", "Вставте посилання — решту FlowPay заповнить сам",
                    trailing = checkAction
                )
            }
            message?.let { text ->
                item {
                    Text(
                        text,
                        Modifier.padding(horizontal = Space.screen).padding(bottom = Space.lg),
                        color = TextSecondary,
                        fontSize = Type.captionSize
                    )
                }
            }
            if (items.isEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = Space.screen)) {
                        EmptyInvite(
                            "Посилок немає",
                            "Додайте покупку з трек-номером Нової Пошти. Статус і залишок " +
                                "безкоштовного зберігання підтягнуться самі."
                        )
                    }
                }
            }
            items(items, key = { it.id }) { order ->
                Card(
                    Modifier.padding(horizontal = Space.screen, vertical = Space.xs).fillMaxWidth(),
                    shape = Radius.md
                ) {
                    Row(Modifier.padding(Space.lg)) {
                        AsyncImage(
                            order.image, order.name,
                            Modifier.size(72.dp).background(SurfaceRaised, Radius.sm),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                order.status.uppercase(),
                                color = Accent,
                                fontSize = Type.overlineSize,
                                fontWeight = Type.strong,
                                letterSpacing = Type.overlineTracking
                            )
                            Spacer(Modifier.height(Space.xs))
                            Text(
                                order.name,
                                fontSize = Type.cardTitleSize,
                                lineHeight = Type.cardTitleLine,
                                fontWeight = Type.medium,
                                maxLines = 2
                            )
                            if (order.price > 0) {
                                Text(money(order.price), fontWeight = Type.strong, fontSize = Type.bodySize)
                            }
                            if (order.tracking.isNotBlank()) {
                                Text(
                                    "Трек: ${order.tracking}",
                                    color = TextSecondary,
                                    fontSize = Type.captionSize
                                )
                            }
                            if (order.statusDetail.isNotBlank()) {
                                Text(
                                    order.statusDetail,
                                    color = TextPrimary,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine,
                                    modifier = Modifier.padding(top = Space.xs)
                                )
                            }
                            if (order.problem) {
                                Text(
                                    "Потрібна увага: перевірте номер або статус у перевізника",
                                    color = Negative,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine,
                                    modifier = Modifier.padding(top = Space.xs)
                                )
                            }
                            if (order.paidStorageFrom > 0) {
                                val left = freeStorageDaysLeft(
                                    LocalDate.ofEpochDay(order.paidStorageFrom),
                                    LocalDate.now()
                                ) ?: 0
                                val pressing = left <= 2
                                Row(
                                    Modifier.fillMaxWidth().padding(top = Space.sm),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Безкоштовне зберігання",
                                        color = TextSecondary,
                                        fontSize = Type.captionSize
                                    )
                                    Box(Modifier.weight(1f).padding(horizontal = Space.sm)) {
                                        DottedLeader(Modifier.fillMaxWidth())
                                    }
                                    Text(
                                        if (left > 0) daysLabel(left) else "закінчилось",
                                        color = if (pressing) Negative else Accent,
                                        fontSize = Type.captionSize,
                                        fontWeight = Type.strong
                                    )
                                }
                                Text(
                                    if (left > 0) {
                                        "платне з ${formatDate(LocalDate.ofEpochDay(order.paidStorageFrom))}"
                                    } else {
                                        "платне з ${formatDate(LocalDate.ofEpochDay(order.paidStorageFrom))}, вже йде"
                                    },
                                    color = TextDisabled,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine
                                )
                            }
                            if (order.scheduledDelivery > 0 && order.status != RECEIVED) {
                                Text(
                                    "Очікується ${formatDate(LocalDate.ofEpochDay(order.scheduledDelivery))}",
                                    color = TextSecondary,
                                    fontSize = Type.captionSize
                                )
                            }
                            if (order.amountToPay > 0) {
                                Text(
                                    "До сплати при отриманні ${money(order.amountToPay)}",
                                    color = TextPrimary,
                                    fontSize = Type.captionSize
                                )
                            }
                            if (order.checkedAt > 0) {
                                Text(
                                    "перевірено ${timeLabel(order.checkedAt)}",
                                    color = TextDisabled,
                                    fontSize = Type.captionSize
                                )
                            } else if (order.tracking.isNotBlank() &&
                                detectCarrier(order.tracking) != CARRIER_NOVA_POSHTA
                            ) {
                                Text(
                                    "Автоперевірка працює для номерів Нової Пошти",
                                    color = TextDisabled,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine
                                )
                            }
                        }
                    }
                    StageRail(
                        stages = PARCEL_STAGES,
                        current = order.status,
                        modifier = Modifier.padding(horizontal = Space.lg)
                    ) { status ->
                        save(items.map { if (it.id == order.id) it.copy(status = status) else it })
                    }
                    // Left aligned for the same reason as the wish card: the floating
                    // action button sits over the bottom right corner.
                    Row(Modifier.padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, order.url.toUri())) }) {
                            Text("До магазину ↗")
                        }
                        IconButton({ tracking = order }) { Icon(Icons.Default.Edit, "Трек-номер") }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val status = runCatching { parcelStatus(order.tracking) }.getOrNull()
                                    message = if (status == null) {
                                        "Не вдалося отримати статус"
                                    } else {
                                        save(
                                            items.map {
                                                if (it.id == order.id) {
                                                    applyStatus(it, status, System.currentTimeMillis())
                                                } else {
                                                    it
                                                }
                                            }
                                        )
                                        status.text
                                    }
                                }
                            },
                            enabled = detectCarrier(order.tracking) == CARRIER_NOVA_POSHTA
                        ) { Icon(Icons.Default.Sync, "Перевірити статус") }
                        IconButton({ save(items - order) }) { Icon(Icons.Default.DeleteOutline, "Видалити") }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        CollapsingTitle("Мої покупки", listState, trailing = checkAction)
    }
    if (adding) AddOrderSheet({ setAdding(false) }) {
        save(items + it)
        setAdding(false)
    }
    tracking?.let { selected ->
        TrackingDialog(selected, { tracking = null }) { number ->
            save(items.map { if (it.id == selected.id) it.copy(tracking = number) else it })
            tracking = null
        }
    }
}

@Composable
fun AddOrderSheet(close: () -> Unit, add: (Order) -> Unit) {
    var link by remember { mutableStateOf("") }
    var trackingNumber by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FormSheet(
        title = "Додати покупку",
        confirmLabel = if (loading) "Зчитую…" else "Додати",
        confirmEnabled = isSupportedWebUrl(link) && !loading,
        onConfirm = {
            scope.launch {
                loading = true
                error = null
                runCatching { product(link) }
                    .onSuccess { item ->
                        add(
                            Order(
                                item.id, item.name, item.url, "Замовлено",
                                tracking = trackingNumber.trim(),
                                image = item.image,
                                price = item.price
                            )
                        )
                    }
                    .onFailure { error = it.message ?: "Не вдалося прочитати посилання" }
                loading = false
            }
        },
        onDismiss = close
    ) {
        Text("Вставте посилання на сторінку придбаного товару.")
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth().padding(top = Space.md), label = { Text("Посилання") })
        OutlinedTextField(
            trackingNumber,
            { trackingNumber = it },
            Modifier.fillMaxWidth().padding(top = Space.md),
            label = { Text("Трек-номер, якщо вже є") },
            singleLine = true
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Space.sm)) }
    }
}

@Composable
fun SettingsScreen(summary: Overview, store: Store, onImported: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<UpdateInfo?>(null) }
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(store.exportJson()) }
        }.onSuccess { message = "Резервну копію збережено" }.onFailure { message = "Не вдалося зберегти файл" }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Порожній файл")
            store.importJson(text)
        }.onSuccess { onImported(); message = "Дані відновлено" }.onFailure { message = "Файл FlowPay пошкоджений" }
    }
    val listState = rememberLazyListState()
    Box {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = navClearance())) {
            // Item zero is the header alone: that is the block the compact bar watches.
            item { ScreenHeader("FLOWPAY", "Огляд", "Скільки відкладено, що в дорозі, що лишається") }
            item {
                Column(Modifier.padding(horizontal = Space.screen)) {
                    // The one loud block on this screen, with the ring reading the same
                    // number a second way.
                    HeroPanel(
                        label = "Відкладено на бажання",
                        value = money(summary.savedTotal),
                        caption = buildList {
                            if (summary.wishTotal > 0) {
                                add("з ${money(summary.wishTotal)} на ${summary.wishCount} позицій")
                            }
                            if (summary.readyCount > 0) add("готових ${summary.readyCount}")
                            summary.monthsToFundAll?.takeIf { it > 0 }?.let {
                                add("все разом ${monthsLabel(it)}")
                            }
                        }.joinToString(" · ").ifBlank { null },
                        muted = summary.savedTotal <= 0,
                        trailing = if (summary.wishTotal > 0) {
                            {
                                ProgressRing(summary.savedProgress, diameter = 84.dp, stroke = 9.dp) {
                                    Text(
                                        "${(summary.savedProgress * 100).toInt()}%",
                                        color = AccentInk,
                                        fontSize = Type.captionSize,
                                        fontWeight = Type.strong
                                    )
                                }
                            }
                        } else {
                            null
                        }
                    )

                    if (summary.plansConflict) {
                        Spacer(Modifier.height(Space.md))
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
                            shape = Radius.md
                        ) {
                            Column(Modifier.padding(Space.lg)) {
                                Text(
                                    "Плани не сходяться",
                                    color = Negative,
                                    fontSize = Type.cardTitleSize,
                                    fontWeight = Type.medium
                                )
                                Text(
                                    "Плани по бажаннях просять ${money(summary.plannedMonthly)} на місяць, " +
                                        "а вільно ${money(summary.freeCash)}. " +
                                        "Не вистачає ${money(summary.plansOverBudget)}.",
                                    color = TextSecondary,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine,
                                    modifier = Modifier.padding(top = Space.xs)
                                )
                            }
                        }
                    }

                    val moved = summary.movement
                    if (moved.tracked > 0) {
                        Spacer(Modifier.height(Space.md))
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SurfaceBase),
                            shape = Radius.md
                        ) {
                            Column(Modifier.padding(Space.lg)) {
                                Text(
                                    when {
                                        moved.change < 0 -> "Список подешевшав"
                                        moved.change > 0 -> "Список подорожчав"
                                        else -> "Ціни стоять на місці"
                                    },
                                    color = TextSecondary,
                                    fontSize = Type.captionSize
                                )
                                Spacer(Modifier.height(Space.xs))
                                Text(
                                    if (moved.change == 0.0) {
                                        money(0.0)
                                    } else {
                                        "${money(kotlin.math.abs(moved.change))} · " +
                                            "%+.1f%%".format(moved.changePercent)
                                    },
                                    fontSize = Type.sectionSize,
                                    lineHeight = Type.sectionLine,
                                    fontWeight = Type.strong,
                                    color = when {
                                        moved.change < 0 -> Accent
                                        moved.change > 0 -> Negative
                                        else -> TextPrimary
                                    }
                                )
                                Text(
                                    "від ${money(moved.firstTotal)} за весь час спостереження · " +
                                        positionsLabel(moved.tracked),
                                    color = TextDisabled,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine
                                )
                                Spacer(Modifier.height(Space.sm))
                                LeaderRow("Подешевшало", moved.cheaper.toString())
                                LeaderRow(
                                    "Подорожчало",
                                    moved.dearer.toString(),
                                    alarm = moved.dearer > 0
                                )
                                LeaderRow("Без змін", moved.steady.toString())

                                // Named separately rather than as leader rows: a product
                                // name is long enough to squeeze the figure off the line.
                                moved.biggestDropName?.let { name ->
                                    Spacer(Modifier.height(Space.sm))
                                    Text(
                                        "Найбільше подешевшало",
                                        color = TextDisabled,
                                        fontSize = Type.captionSize
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            name,
                                            fontSize = Type.captionSize,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f).padding(end = Space.sm)
                                        )
                                        Text(
                                            "%+.1f%%".format(moved.biggestDropPercent),
                                            color = Accent,
                                            fontSize = Type.captionSize,
                                            fontWeight = Type.strong
                                        )
                                    }
                                }
                                moved.biggestRiseName?.let { name ->
                                    Spacer(Modifier.height(Space.sm))
                                    Text(
                                        "Найбільше подорожчало",
                                        color = TextDisabled,
                                        fontSize = Type.captionSize
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            name,
                                            fontSize = Type.captionSize,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f).padding(end = Space.sm)
                                        )
                                        Text(
                                            "%+.1f%%".format(moved.biggestRisePercent),
                                            color = Negative,
                                            fontSize = Type.captionSize,
                                            fontWeight = Type.strong
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(Space.md))
                    // Three figures and their targets in the height one card used to take,
                    // with the rings restating them. This density is what the reference
                    // gets right and a column of single-figure cards does not.
                    StatStrip(
                        columns = listOf(
                            StatColumn(
                                Icons.Default.ReceiptLong,
                                "Витрати за місяць",
                                money(summary.monthlyExpenses),
                                summary.income.takeIf { it > 0 }?.let { money(it) }
                            ),
                            StatColumn(
                                Icons.Default.Savings,
                                if (summary.overspent) "Не сходиться" else "Вільно",
                                if (summary.budgetUnknown) "—" else money(summary.freeCash)
                            ),
                            StatColumn(
                                Icons.Default.Flag,
                                "Плани на місяць",
                                money(summary.plannedMonthly),
                                summary.freeCash.takeIf { !summary.budgetUnknown && it > 0 }
                                    ?.let { money(it) }
                            )
                        ),
                        rings = listOf(
                            summary.savedProgress,
                            if (summary.income > 0) {
                                (summary.monthlyExpenses / summary.income).toFloat().coerceIn(0f, 1f)
                            } else {
                                0f
                            },
                            if (summary.freeCash > 0) {
                                (summary.plannedMonthly / summary.freeCash).toFloat().coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        )
                    )

                    Spacer(Modifier.height(Space.lg))
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceBase),
                        shape = Radius.lg
                    ) {
                        Column(Modifier.padding(Space.lg)) {
                            LeaderRow("Бажань у списку", summary.wishCount.toString())
                            LeaderRow("Повністю накопичено", summary.readyCount.toString())
                            LeaderRow("Посилок у дорозі", summary.parcelsMoving.toString())
                            LeaderRow(
                                "Чекають на відділенні",
                                summary.parcelsAtBranch.toString(),
                                alarm = summary.parcelsAtBranch > 0
                            )
                            LeaderRow("Покупок закрито", summary.parcelsDone.toString())
                        }
                    }
                }

                SectionTitle("Налаштування")
                // Filled list items painted a large lighter block across the screen and
                // left a hard seam under the header. They sit on the page instead.
                SettingsRow(Icons.Default.Sync, "Фонове оновлення", "Кожні 12 годин перевіряються ціни та статуси посилок")
                SettingsRow(
                    Icons.Default.NotificationsNone,
                    "Сповіщення",
                    "Про падіння ціни, досягнення цілі та рух посилки. Ціни й доставка мають окремі канали."
                )
                SettingsRow(
                    Icons.Default.Security,
                    "Приватність",
                    "Вішлісти й фінанси зберігаються лише на телефоні. API-ключі не вшиті в APK."
                )
                HorizontalDivider(color = HairLine, modifier = Modifier.padding(vertical = Space.lg))
                ListItem(
                    modifier = Modifier.padding(top = Space.sm),
                    leadingContent = { Icon(Icons.Default.SystemUpdate, null, tint = Accent) },
                    headlineContent = { Text("Оновлення FlowPay", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("Встановлено: ${BuildConfig.VERSION_NAME}") },
                    trailingContent = {
                        OutlinedButton(
                            shape = Radius.sm,
                            border = BorderStroke(1.dp, HairLine),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            onClick = {
                                scope.launch {
                                    checking = true
                                    message = null
                                    val latest = runCatching { latestUpdate() }.getOrNull()
                                    if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) {
                                        available = latest
                                    } else {
                                        message = if (latest == null) "Release ще не опублікований" else "У вас остання версія"
                                    }
                                    checking = false
                                }
                            },
                            enabled = !checking
                        ) {
                            if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Перевірити")
                        }
                    }
                )
                ListItem(
                    leadingContent = { Icon(Icons.Default.UploadFile, null) },
                    headlineContent = { Text("Створити резервну копію") },
                    supportingContent = { Text("Вішліст, платежі та замовлення у JSON") },
                    trailingContent = { IconButton({ export.launch("flowpay-backup.json") }) { Icon(Icons.Default.ChevronRight, null) } }
                )
                ListItem(
                    leadingContent = { Icon(Icons.Default.Download, null) },
                    headlineContent = { Text("Відновити з файлу") },
                    supportingContent = { Text("Замінить дані на телефоні даними з копії") },
                    trailingContent = { IconButton({ import.launch(arrayOf("application/json", "text/plain")) }) { Icon(Icons.Default.ChevronRight, null) } }
                )
                message?.let { Text(it, Modifier.padding(Space.screen), color = Accent) }
            }
        }
        CollapsingTitle("Огляд", listState)
    }
    available?.let { update ->
        AlertDialog(
            onDismissRequest = { available = null },
            title = { Text("Доступне оновлення") },
            text = { Text("Версія ${update.versionName}. FlowPay завантажить APK і відкриє системне встановлення Android.") },
            confirmButton = {
                Button({
                    installUpdate(context, update.downloadUrl) { message = it }
                    available = null
                }) { Text("Оновити") }
            },
            dismissButton = { TextButton({ available = null }) { Text("Пізніше") } }
        )
    }
}

/**
 * One line of the settings page. Painted on the page rather than on its own filled
 * surface, so the screen stays one colour instead of showing a lighter slab.
 */
@Composable
fun SettingsRow(icon: ImageVector, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.md),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, null, tint = TextSecondary)
        Spacer(Modifier.width(Space.lg))
        Column {
            Text(title, fontSize = Type.cardTitleSize, fontWeight = Type.medium)
            Text(
                detail,
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
    }
}

/**
 * Corrects an existing recurring expense, so a typo no longer means delete and retype.
 *
 * The name is editable here too. It used to be the dialog's title and nothing
 * more, which left a misspelled subscription misspelled for good.
 */
@Composable
fun EditPaymentSheet(
    pay: Pay,
    close: () -> Unit,
    delete: () -> Unit,
    save: (Pay) -> Unit
) {
    // Two different answers: a tick for saving, a heavier one for erasing.
    val touch = LocalHapticFeedback.current
    var name by remember { mutableStateOf(pay.name) }
    var amount by remember { mutableStateOf(amountText(pay.amount)) }
    var day by remember { mutableStateOf(pay.day.toString()) }
    var currency by remember { mutableStateOf(pay.currency) }
    var warnDays by remember { mutableIntStateOf(pay.warnDays) }
    FormSheet(
        title = "Змінити витрату",
        confirmLabel = "Зберегти",
        confirmEnabled = parseAmount(amount) > 0 && name.isNotBlank(),
        onConfirm = {
            parseAmount(amount).takeIf { it > 0 }?.let { value ->
                touch.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                save(
                    pay.copy(
                        name = name.trim().ifBlank { pay.name },
                        amount = value,
                        day = day.toIntOrNull()?.coerceIn(1, 31) ?: pay.day,
                        currency = currency,
                        warnDays = warnDays
                    )
                )
            }
        },
        onDismiss = close
    ) {
        OutlinedTextField(
            name,
            { name = it },
            Modifier.fillMaxWidth(),
            label = { Text("Назва") },
            singleLine = true
        )
        CurrencySegments(currency) { currency = it }
        NumberField(if (currency == USD) "Сума, $" else "Сума, ₴", amount) { amount = it }
        NumberField("День оплати", day) { day = it }
        WarnDaysChips(warnDays) { warnDays = it }
        // Deleting used to sit on the row itself, a thumb's width from the tap
        // that opens this form, and it asked nothing before erasing.
        Spacer(Modifier.height(Space.md))
        TextButton(
            {
                touch.performHapticFeedback(HapticFeedbackType.LongPress)
                delete()
            },
            Modifier.fillMaxWidth()
        ) {
            Text("Видалити витрату", color = Negative)
        }
    }
}

/**
 * Turns a wish into a parcel.
 *
 * The tracking number is optional, because you often order first and learn the
 * number hours later. The item keeps its name, photo, link and price, so the
 * purchases tab shows the same thing you had been saving for, and it can be
 * filled in from that card afterwards.
 */
@Composable
fun BoughtDialog(wish: Wish, close: () -> Unit, confirm: (String) -> Unit) {
    var trackingNumber by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Купив це") },
        text = {
            Column {
                Text(wish.name, fontSize = Type.captionSize, color = TextSecondary)
                Text(
                    "Товар переїде в Покупки зі статусом «Замовлено». Трек-номер можна " +
                        "додати зараз або пізніше.",
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = Space.sm)
                )
                OutlinedTextField(
                    trackingNumber,
                    { trackingNumber = it },
                    Modifier.fillMaxWidth().padding(top = Space.md),
                    label = { Text("Трек-номер, якщо вже є") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button({ confirm(trackingNumber.trim()) }) { Text("Перенести в покупки") }
        },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
}

/**
 * Sets the monthly income.
 *
 * Only ever stored on the phone, and only used to subtract the standing costs from
 * it, so the wishlist can plan against a real figure instead of a guess.
 */
@Composable
fun IncomeDialog(current: Double, close: () -> Unit, save: (Double) -> Unit) {
    var text by remember { mutableStateOf(amountText(current)) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Дохід на місяць") },
        text = {
            Column {
                Text(
                    "Потрібен лише для того, щоб порахувати, скільки лишається після " +
                        "постійних витрат. Нікуди не надсилається.",
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
                NumberField("Сума, ₴", text) { text = it }
            }
        },
        confirmButton = { Button({ save(parseAmount(text)) }) { Text("Зберегти") } },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
}

/**
 * How much notice this expense gets.
 *
 * A row of chips rather than a number field: the useful answers are few, and one
 * of them — a week — is the difference between cancelling a subscription and
 * paying for another year of it.
 */
@Composable
fun WarnDaysChips(warnDays: Int, set: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.md)) {
        Text("Нагадати", color = TextSecondary, fontSize = Type.captionSize)
        LazyRow(
            Modifier.padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            items(WARN_CHOICES) { choice ->
                FilterChip(
                    warnDays == choice,
                    { set(choice) },
                    { Text(warnLabel(choice), fontSize = Type.captionSize) }
                )
            }
        }
    }
}

/**
 * Picks the currency an expense is actually billed in.
 *
 * One control rather than two chips: an expense is billed in one currency, and two
 * independent-looking switches invited the question of what both of them on would
 * mean. The lime indicator here is inside a form, so it never competes with the
 * one lime panel on the screen behind it.
 */
@Composable
fun CurrencySegments(currency: String, set: (String) -> Unit) {
    SegmentedControl(
        options = listOf("Гривня ₴", "Долар $"),
        selected = if (currency == USD) 1 else 0,
        modifier = Modifier.padding(top = Space.md)
    ) { index -> set(if (index == 1) USD else UAH) }
}

/**
 * Sets the tracking number of a parcel.
 *
 * The field existed on the model and was written to backups from the start, but
 * nothing in the app could ever fill it in, so a delivery tracker had no tracking
 * number. You normally learn the number after ordering, which is why it is edited
 * here rather than only at creation.
 */
@Composable
fun TrackingDialog(order: Order, close: () -> Unit, save: (String) -> Unit) {
    var number by remember { mutableStateOf(order.tracking) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Трек-номер") },
        text = {
            Column {
                Text(order.name, color = TextSecondary, fontSize = Type.captionSize)
                OutlinedTextField(
                    number,
                    { number = it },
                    Modifier.fillMaxWidth().padding(top = Space.md),
                    label = { Text("Номер відправлення") },
                    singleLine = true
                )
            }
        },
        confirmButton = { Button({ save(number.trim()) }) { Text("Зберегти") } },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
}

@Composable
fun NumberField(label: String, value: String, set: (String) -> Unit) = OutlinedTextField(
    value, set, Modifier.fillMaxWidth().padding(top = Space.md), label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
)

/**
 * The one hero figure on a screen. When the value is absent it is muted rather than
 * accented: making a zero the brightest thing on the screen shouts that there is
 * nothing here, which is the opposite of what an accent is for.
 */
@Composable
fun SummaryCard(
    label: String,
    value: String,
    isEmpty: Boolean,
    hero: Boolean = true,
    detail: String? = null
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
        shape = Radius.md
    ) {
        Column(Modifier.padding(Space.lg)) {
            Text(label, color = TextSecondary, fontSize = Type.captionSize)
            Spacer(Modifier.height(Space.xs))
            Text(
                value,
                fontSize = if (hero) Type.heroSize else Type.sectionSize,
                lineHeight = if (hero) Type.heroLine else Type.sectionLine,
                letterSpacing = if (hero) Type.heroTracking else 0.sp,
                fontWeight = when {
                    isEmpty -> Type.regular
                    hero -> FontWeight.Black
                    else -> Type.medium
                },
                color = if (isEmpty) TextDisabled else Accent
            )
            detail?.let {
                Text(
                    it,
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine,
                    modifier = Modifier.padding(top = Space.sm)
                )
            }
        }
    }
}


