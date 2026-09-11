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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
    val notifiedPrice: Double = 0.0
)

data class Pay(
    val name: String,
    val amount: Double,
    val day: Int = 1,
    /** "UAH" or "USD". Rent is commonly quoted and paid in dollars. */
    val currency: String = UAH
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setContent { FlowPayApp(this) }
    }
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
            .put("np", it.notifiedPrice)
    })

    fun pays(): List<Pay> = jsonList("pay") {
        Pay(
            it.optString("n"),
            it.optDouble("a"),
            it.optInt("d", 1),
            // Entries saved before currencies existed were all hryvnia.
            it.optString("cur", UAH).ifBlank { UAH }
        )
    }

    fun savePays(items: List<Pay>) = save("pay", items.map {
        JSONObject().put("n", it.name).put("a", it.amount).put("d", it.day).put("cur", it.currency)
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
        return FxRate(buy, sell) to prefs.getLong("fx_at", 0L)
    }

    fun saveFxRate(rate: FxRate, atMillis: Long) = prefs.edit {
        putFloat("fx_buy", rate.buy.toFloat())
        putFloat("fx_sell", rate.sell.toFloat())
        putLong("fx_at", atMillis)
    }

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

suspend fun product(link: String): Wish = withContext(Dispatchers.IO) {
    val normalizedLink = link.trim()
    require(isSupportedWebUrl(normalizedLink)) { "Вкажіть коректне HTTP або HTTPS посилання" }
    val connection = URL(normalizedLink).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
    val html = connection.inputStream.bufferedReader().use { it.readText() }
    parseProduct(html, normalizedLink, System.currentTimeMillis().toString(), LocalDate.now().toEpochDay())
}

data class FxRate(val buy: Double = 0.0, val sell: Double = 0.0)
data class UpdateInfo(val versionCode: Int, val versionName: String, val downloadUrl: String)

suspend fun usdRate(): FxRate = withContext(Dispatchers.IO) {
    // Monobank rate limits this endpoint, so a hung request must not sit forever.
    val connection = URL("https://api.monobank.ua/bank/currency").openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    val body = connection.inputStream.bufferedReader().use { it.readText() }
    parseUsdRate(body)
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
fun FlowPayApp(context: Context) {
    val store = remember { Store(context) }
    var tab by remember { mutableIntStateOf(0) }
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

    // System back closes the item page before it leaves the app.
    BackHandler(enabled = openedWish != null) { openedWish = null }

    // Recomputed whenever expenses change, so the wishlist plan and the expenses
    // screen never disagree about what is free this month.
    val usdSell = remember { store.fxRate().first }.sell
    val monthBudget = budget(remember(pays) { store.income() }, monthlyTotal(pays, usdSell))

    val addLabel = when {
        // An item page has its own actions, and the button would cover them.
        openedWish != null -> null
        tab == 0 -> "Додати бажання"
        tab == 2 -> "Додати витрату"
        tab == 3 -> "Додати покупку"
        else -> null
    }

    FlowPayTheme {
        Scaffold(
            containerColor = AppBackground,
            contentColor = TextPrimary,
            floatingActionButton = {
                addLabel?.let { label ->
                    ExtendedFloatingActionButton(
                        onClick = { adding = true },
                        containerColor = Accent,
                        contentColor = AccentInk,
                        shape = Radius.pill,
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text(label, fontWeight = Type.medium) }
                    )
                }
            },
            bottomBar = {
                NavigationBar(containerColor = SurfaceLow, tonalElevation = 0.dp) {
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
                            // Labelling only the active tab makes the row change width
                            // as you switch, which reads as a glitch.
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
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> WishlistScreen(
                        items = wishes,
                        save = { wishes = it; store.saveWishes(it) },
                        store = store,
                        context = context,
                        adding = adding,
                        setAdding = { adding = it },
                        opened = openedWish,
                        setOpened = { openedWish = it },
                        // A bought wish becomes a parcel, and the tab follows it so
                        // the move is visible rather than something to go looking for.
                        freeCash = monthBudget.free,
                        onBought = { order ->
                            val next = orders + order
                            orders = next
                            store.saveOrders(next)
                            tab = 3
                        }
                    )
                    1 -> CalculatorScreen(store)
                    2 -> PaymentsScreen(pays, { pays = it; store.savePays(it) }, store, adding) { adding = it }
                    3 -> OrdersScreen(orders, { orders = it; store.saveOrders(it) }, context, adding) { adding = it }
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

@Composable
fun ScreenHeader(
    kicker: String,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    // Overline, title and subtitle form one group, at most 8dp apart, followed by a
    // 32dp break. That break is what gives the screen a readable shape.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.screen, end = Space.screen, top = Space.lg, bottom = Space.xxl)
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
    if (openedWish != null) {
        WishDetailScreen(
            wish = openedWish,
            context = context,
            onBack = { setOpened(null) },
            onChange = { changed -> save(items.map { if (it.id == changed.id) changed else it }) },
            onEdit = { editing = openedWish },
            onDelete = { save(items - openedWish); setOpened(null) },
            freeCash = freeCash,
            onBought = { trackingNumber ->
                onBought(
                    Order(
                        id = openedWish.id,
                        name = openedWish.name,
                        url = openedWish.url,
                        status = "Замовлено",
                        tracking = trackingNumber,
                        image = openedWish.image,
                        price = openedWish.price
                    )
                )
                save(items - openedWish)
                setOpened(null)
            }
        )
        editing?.let { selected ->
            EditWishDialog(selected, { editing = null }) { changed ->
                save(items.map { if (it.id == changed.id) changed else it })
                editing = null
            }
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = Space.fabClearance)) {
        item {
            ScreenHeader(
                "FLOWPAY", "Мої бажання", "Ціна, ціль та історія в одному місці",
                trailing = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                refreshing = true
                                var updated = 0
                                val fresh = items.map { old ->
                                    runCatching { product(old.url) }.getOrNull()?.let { now ->
                                        updated++
                                        refreshedWish(old, now)
                                    } ?: old
                                }
                                save(fresh); refreshing = false; message = "Оновлено: $updated з ${items.size}"
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
            )
            message?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = Space.screen).padding(bottom = Space.lg),
                    color = TextSecondary,
                    fontSize = Type.captionSize
                )
            }
        }
        if (items.size > 1) {
            item {
                LazyRow(
                    Modifier.padding(bottom = Space.md),
                    contentPadding = PaddingValues(horizontal = Space.screen),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    items(WishSort.entries.toList()) { option ->
                        FilterChip(
                            sort == option,
                            { sort = option; store.saveWishSort(option) },
                            { Text(option.label, fontSize = Type.captionSize) }
                        )
                    }
                }
            }
        }
        if (items.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = Space.screen)) {
                    EmptyInvite(
                        "Ще нічого не хочеться",
                        "Вставте посилання на товар. FlowPay візьме назву, фото й ціну, " +
                            "далі стежить за ціною сам і скаже, коли вигідно купувати."
                    )
                }
            }
        }
        items(sortWishes(items, sort), key = { it.id }) { wish ->
            WishCard(wish) { setOpened(wish.id) }
        }
    }
    if (adding) AddWishDialog({ setAdding(false) }, { wish -> save(items + wish); setAdding(false) })
    editing?.let { selected ->
        EditWishDialog(selected, { editing = null }) { changed ->
            save(items.map { if (it.id == changed.id) changed else it })
            editing = null
        }
    }
}

@Composable
fun AddWishDialog(close: () -> Unit, add: (Wish) -> Unit) {
    var link by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Інше") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = close, confirmButton = {
        Button(onClick = {
            scope.launch {
                loading = true; error = null
                runCatching { product(link).copy(targetPrice = target.replace(',', '.').toDoubleOrNull() ?: 0.0, category = category) }
                    .onSuccess(add).onFailure { error = it.message ?: "Не вдалося прочитати сторінку" }
                loading = false
            }
        }, enabled = isSupportedWebUrl(link) && !loading) { Text(if (loading) "Зчитую…" else "Додати") }
    }, dismissButton = { TextButton(close) { Text("Скасувати") } }, title = { Text("Новий товар") }, text = {
        Column {
            OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Посилання на товар") })
            NumberField("Цільова ціна, ₴ (необов'язково)", target) { target = it }
            OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth().padding(top = Space.md), label = { Text("Категорія") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Space.sm)) }
        }
    })
}

@Composable
fun EditWishDialog(wish: Wish, close: () -> Unit, save: (Wish) -> Unit) {
    var name by remember { mutableStateOf(wish.name) }
    var target by remember { mutableStateOf(wish.targetPrice.takeIf { it > 0 }?.toString().orEmpty()) }
    var category by remember { mutableStateOf(wish.category) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Редагувати товар") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Назва") })
                NumberField("Цільова ціна, ₴", target) { target = it }
                OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth().padding(top = Space.md), label = { Text("Категорія") })
            }
        },
        confirmButton = {
            Button({ save(wish.copy(name = name.ifBlank { wish.name }, targetPrice = target.replace(',', '.').toDoubleOrNull() ?: 0.0, category = category.ifBlank { "Інше" })) }) {
                Text("Зберегти")
            }
        },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
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
fun WishDetailScreen(
    wish: Wish,
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

    LazyColumn(contentPadding = PaddingValues(bottom = Space.huge)) {
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
                    overlayNumber = "%+.0f%%".format(change).takeIf { change <= -1.0 },
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
                        imageModifier.clip(Radius.lg).background(SurfaceRaised),
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
                                runCatching { product(wish.url) }
                                    .onSuccess {
                                        onChange(refreshedWish(wish, it))
                                        message = "Ціну оновлено"
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

@Composable
fun WishCard(wish: Wish, onOpen: () -> Unit) {
    val change = priceChangePercent(wish)
    val goal = wishGoal(wish)
    val plan = savingsPlan(goal, wish.saved, wish.monthlyPlan)
    // The whole card opens the item page. Edit and delete moved there, which also
    // took them out from under the floating action button.
    Card(
        onClick = onOpen,
        modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.sm).fillMaxWidth(),
        shape = Radius.lg
    ) {
        // A photo earns its 190dp, and carries the news on top of itself: the price
        // move as a large numeral, the verdict in a capsule. Without a photo there is
        // no block rather than a dead grey rectangle.
        if (wish.image.isNotBlank()) {
            val insightForPhoto = priceInsight(wish.history, wish.price, wish.checkedDay)
            PhotoHeader(
                imageUrl = wish.image,
                description = wish.name,
                overlayNumber = "%+.0f%%".format(change).takeIf { change <= -1.0 },
                chip = verdictLabel(insightForPhoto.verdict)
                    .takeIf { insightForPhoto.verdict != BuyVerdict.UNKNOWN },
                chipIcon = Icons.Default.Bolt,
                chipColor = when (insightForPhoto.verdict) {
                    BuyVerdict.GOOD -> Accent
                    BuyVerdict.POOR -> Negative
                    else -> TextPrimary
                }
            ) { imageModifier ->
                AsyncImage(
                    wish.image, wish.name,
                    imageModifier.background(SurfaceRaised),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip({}, { Text(wish.category, fontSize = Type.captionSize) })
                Spacer(Modifier.weight(1f))
                Text(
                    "%+.1f%%".format(change),
                    color = if (change <= 0) Accent else Negative,
                    fontSize = Type.captionSize,
                    fontWeight = Type.strong
                )
            }
            Spacer(Modifier.height(Space.sm))
            Text(
                wish.name,
                fontSize = Type.cardTitleSize,
                lineHeight = Type.cardTitleLine,
                fontWeight = Type.medium,
                maxLines = 2
            )
            FigureWithTarget(
                value = money(wish.price),
                target = wish.targetPrice.takeIf { it > 0 }?.let { "ціль ${money(it)}" }
            )
            if (wish.saved > 0 || wish.monthlyPlan > 0) {
                Spacer(Modifier.height(Space.md))
                PillProgress(plan.progress)
                Text(
                    if (plan.reached) "Накопичено повністю"
                    else "Відкладено ${money(plan.saved)} з ${money(plan.goal)}",
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    modifier = Modifier.padding(top = Space.sm)
                )
            } else {
                PriceBars(wish.history, Modifier.fillMaxWidth().height(60.dp).padding(top = Space.md))
                val insight = priceInsight(wish.history, wish.price, wish.checkedDay)
                // A quietly broken parser showing a week-old price as current is worse
                // than no price at all, so staleness is stated rather than hidden.
                stalenessDays(wish.checkedDay, LocalDate.now().toEpochDay())?.takeIf { it >= 2 }?.let {
                    Text(
                        "Ціна не оновлювалась ${daysLabel(it)}",
                        color = Negative,
                        fontSize = Type.captionSize
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        verdictLabel(insight.verdict),
                        color = when (insight.verdict) {
                            BuyVerdict.GOOD -> Accent
                            BuyVerdict.POOR -> Negative
                            else -> TextSecondary
                        },
                        fontSize = Type.captionSize,
                        fontWeight = Type.strong
                    )
                    if (insight.daysTracked > 0) {
                        Text(
                            " · ${daysLabel(insight.daysTracked)} спостережень",
                            color = TextSecondary,
                            fontSize = Type.captionSize
                        )
                    }
                }
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

    LazyColumn(contentPadding = PaddingValues(bottom = Space.huge)) {
        item {
            ScreenHeader("MONOBANK", "Курс і суми", "Конвертація валют та швидкі розрахунки")
            Column(Modifier.padding(horizontal = Space.screen)) {
                Card(shape = Radius.lg, colors = CardDefaults.cardColors(containerColor = SurfaceRaised)) {
                    Column(Modifier.padding(Space.lg)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("USD / UAH", color = TextSecondary, fontSize = Type.captionSize)
                                Text(
                                    if (rate.sell > 0) "Купівля ${"%.2f".format(rate.buy)} · продаж ${"%.2f".format(rate.sell)}"
                                    else "Курс ще не завантажено",
                                    fontWeight = FontWeight.Bold
                                )
                                if (fetchedAt > 0) {
                                    Text(
                                        "станом на ${timeLabel(fetchedAt)}" +
                                            if (rateError) " · оновити не вдалося" else "",
                                        color = if (rateError) Negative else TextSecondary,
                                        fontSize = Type.captionSize
                                    )
                                } else if (rateError) {
                                    Text(
                                        "Monobank обмежує запити, спробуйте за хвилину",
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
                            muted = converted == 0.0
                        )
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
    var income by remember { mutableDoubleStateOf(store.income()) }
    var editingIncome by remember { mutableStateOf(false) }
    val month = budget(income, monthly)
    var editing by remember { mutableStateOf<Int?>(null) }
    // Read once, so the timeline and the strip cannot disagree about which day it is.
    val today = remember { LocalDate.now() }
    LazyColumn(contentPadding = PaddingValues(bottom = Space.fabClearance)) {
        item {
            ScreenHeader("ЩОМІСЯЦЯ", "Постійні витрати", "Оренда, комуналка, зв'язок і підписки")
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
                        else ->
                            "${dayMonth(next.date)} · ${next.items.joinToString(", ") { it.name }}"
                    },
                    muted = next == null
                )
                if (items.isNotEmpty()) {
                    Spacer(Modifier.height(Space.md))
                    MonthStrip(
                        monthLength = today.lengthOfMonth(),
                        today = today.dayOfMonth,
                        marked = paymentDays(items, today.lengthOfMonth())
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "Дні списань цього місяця",
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
                            if (monthly.rateMissing) {
                                LeaderRow("Плюс ${dollars(monthly.usd)}", "курс ще не завантажено")
                            } else if (monthly.hasUsd) {
                                LeaderRow(
                                    "З них ${dollars(monthly.usd)}",
                                    "≈ ${approxMoney(monthly.usdInUah)}"
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
                Card(
                    Modifier.padding(horizontal = Space.screen, vertical = Space.xs).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceBase),
                    shape = Radius.md
                ) {
                    Column(Modifier.padding(Space.lg)) {
                        Text(
                            dayMonth(group.date),
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
                                Text(
                                    pay.name,
                                    fontSize = Type.cardTitleSize,
                                    fontWeight = Type.medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Box(Modifier.weight(1f).padding(horizontal = Space.sm)) {
                                    DottedLeader(Modifier.fillMaxWidth())
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
    if (adding) AddPaymentDialog({ setAdding(false) }) {
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
            EditPaymentDialog(
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
fun AddPaymentDialog(close: () -> Unit, add: (Pay) -> Unit) {
    // The chips fill the name in, they are not the name. Two subscriptions are
    // rarely the same subscription, so the field is always present and always
    // editable: tapping a chip simply types the word for you.
    val presets = listOf("Оренда квартири", "Комуналка", "Інтернет", "Мобільний", "Підписка")
    var name by remember { mutableStateOf(presets.first()) }
    var amount by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("1") }
    var currency by remember { mutableStateOf(UAH) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Нова постійна витрата") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                CurrencyChips(currency) { currency = it }
                NumberField(if (currency == USD) "Сума, $" else "Сума, ₴", amount) { amount = it }
                NumberField("День оплати", day) { day = it }
            }
        },
        confirmButton = {
            Button(
                {
                    parseAmount(amount).takeIf { it > 0 }?.let { value ->
                        add(
                            Pay(
                                name.trim().ifBlank { "Інше" },
                                value,
                                day.toIntOrNull()?.coerceIn(1, 31) ?: 1,
                                currency
                            )
                        )
                    }
                },
                enabled = parseAmount(amount) > 0 && name.isNotBlank()
            ) { Text("Додати") }
        },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
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

    LazyColumn(contentPadding = PaddingValues(bottom = Space.fabClearance)) {
        item {
            ScreenHeader(
                "ДОСТАВКА", "Мої покупки", "Вставте посилання — решту FlowPay заповнить сам",
                trailing = {
                    IconButton(onClick = { checkAll() }, enabled = !checking && trackable > 0) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                        } else {
                            Icon(Icons.Default.Sync, "Перевірити статуси", tint = TextSecondary)
                        }
                    }
                }
            )
            message?.let {
                Text(
                    it,
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
                            Text(
                                if (left > 0) {
                                    "Безкоштовне зберігання ще ${daysLabel(left)}, платне з " +
                                        formatDate(LocalDate.ofEpochDay(order.paidStorageFrom))
                                } else {
                                    "Безкоштовне зберігання закінчилось"
                                },
                                color = if (left in 1..2 || left == 0) Negative else Accent,
                                fontSize = Type.captionSize,
                                lineHeight = Type.captionLine,
                                modifier = Modifier.padding(top = Space.xs)
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
                LazyRow(
                    Modifier.padding(horizontal = Space.lg),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    items(PARCEL_STAGES) { status ->
                        FilterChip(
                            order.status == status,
                            { save(items.map { if (it.id == order.id) it.copy(status = status) else it }) },
                            { Text(status, fontSize = Type.captionSize) }
                        )
                    }
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
    if (adding) AddOrderDialog({ setAdding(false) }) {
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
fun AddOrderDialog(close: () -> Unit, add: (Order) -> Unit) {
    var link by remember { mutableStateOf("") }
    var trackingNumber by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Додати покупку") },
        text = {
            Column {
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
        },
        confirmButton = {
            Button({
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
            }, enabled = isSupportedWebUrl(link) && !loading) { Text(if (loading) "Зчитую…" else "Додати") }
        },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
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
    LazyColumn {
        item {
            ScreenHeader("FLOWPAY", "Огляд", "Скільки відкладено, що в дорозі, що лишається")

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
fun EditPaymentDialog(
    pay: Pay,
    close: () -> Unit,
    delete: () -> Unit,
    save: (Pay) -> Unit
) {
    var name by remember { mutableStateOf(pay.name) }
    var amount by remember { mutableStateOf(amountText(pay.amount)) }
    var day by remember { mutableStateOf(pay.day.toString()) }
    var currency by remember { mutableStateOf(pay.currency) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Змінити витрату") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Назва") },
                    singleLine = true
                )
                CurrencyChips(currency) { currency = it }
                NumberField(if (currency == USD) "Сума, $" else "Сума, ₴", amount) { amount = it }
                NumberField("День оплати", day) { day = it }
                // Deleting used to sit on the row itself, a thumb's width from the
                // tap that opens this dialog, and it asked nothing before erasing.
                Spacer(Modifier.height(Space.md))
                TextButton(delete, Modifier.fillMaxWidth()) {
                    Text("Видалити витрату", color = Negative)
                }
            }
        },
        confirmButton = {
            Button(
                {
                    parseAmount(amount).takeIf { it > 0 }?.let { value ->
                        save(
                            pay.copy(
                                name = name.trim().ifBlank { pay.name },
                                amount = value,
                                day = day.toIntOrNull()?.coerceIn(1, 31) ?: pay.day,
                                currency = currency
                            )
                        )
                    }
                },
                enabled = parseAmount(amount) > 0 && name.isNotBlank()
            ) { Text("Зберегти") }
        },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
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

/** Picks the currency an expense is actually billed in. */
@Composable
fun CurrencyChips(currency: String, set: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        FilterChip(
            currency != USD,
            { set(UAH) },
            { Text("Гривня ₴", fontSize = Type.captionSize) },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            currency == USD,
            { set(USD) },
            { Text("Долар $", fontSize = Type.captionSize) },
            modifier = Modifier.weight(1f)
        )
    }
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


