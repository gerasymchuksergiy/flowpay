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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import java.util.Locale

data class Wish(
    val id: String,
    val name: String,
    val url: String,
    val image: String,
    val price: Double,
    val targetPrice: Double = 0.0,
    val category: String = "Інше",
    val history: List<Double>
)

data class Pay(val name: String, val amount: Double, val day: Int = 1)
data class Order(
    val id: String,
    val name: String,
    val url: String,
    val status: String,
    val tracking: String = "",
    val image: String = "",
    val price: Double = 0.0
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
        setContent { FlowPayApp(this) }
    }
}

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("flowpay", Context.MODE_PRIVATE)

    fun wishes(): List<Wish> = jsonList("w") { o ->
        val history = o.optJSONArray("h") ?: JSONArray()
        Wish(
            id = o.optString("id", System.currentTimeMillis().toString()),
            name = o.optString("n", "Товар"),
            url = o.optString("u"),
            image = o.optString("i"),
            price = o.optDouble("p", 0.0),
            targetPrice = o.optDouble("t", 0.0),
            category = o.optString("c", "Інше"),
            history = (0 until history.length()).map { history.optDouble(it) }.filter { it > 0 }
        )
    }

    fun saveWishes(items: List<Wish>) = save("w", items.map {
        JSONObject().put("id", it.id).put("n", it.name).put("u", it.url).put("i", it.image)
            .put("p", it.price).put("t", it.targetPrice).put("c", it.category).put("h", JSONArray(it.history))
    })

    fun pays(): List<Pay> = jsonList("pay") { Pay(it.optString("n"), it.optDouble("a"), it.optInt("d", 1)) }
    fun savePays(items: List<Pay>) = save("pay", items.map { JSONObject().put("n", it.name).put("a", it.amount).put("d", it.day) })

    fun orders(): List<Order> = jsonList("orders") {
        Order(
            it.optString("id"), it.optString("n"), it.optString("u"),
            it.optString("s", "Замовлено"), it.optString("t"),
            it.optString("i"), it.optDouble("p", 0.0)
        )
    }
    fun saveOrders(items: List<Order>) = save("orders", items.map {
        JSONObject().put("id", it.id).put("n", it.name).put("u", it.url)
            .put("s", it.status).put("t", it.tracking).put("i", it.image).put("p", it.price)
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

fun refreshedWish(previous: Wish, current: Wish): Wish = previous.copy(
    image = current.image.ifBlank { previous.image },
    price = current.price,
    history = (previous.history + current.price).filter { it > 0 }.takeLast(90)
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
    parseProduct(html, normalizedLink, System.currentTimeMillis().toString())
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
    val code = Regex("""versionCode=(\d+)""").find(release.optString("body"))
        ?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
    val assets = release.optJSONArray("assets") ?: return@withContext null
    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
        .firstOrNull { it.optString("name").endsWith(".apk", true) } ?: return@withContext null
    val downloadUrl = apk.optString("browser_download_url")
    val downloadUri = downloadUrl.toUri()
    if (downloadUri.scheme != "https" || downloadUri.host != "github.com") return@withContext null
    UpdateInfo(code, release.optString("tag_name", "нова версія"), downloadUrl)
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

private fun money(value: Double) = NumberFormat.getNumberInstance(Locale("uk", "UA")).format(value) + " ₴"

@Composable
fun FlowPayApp(context: Context) {
    val store = remember { Store(context) }
    var tab by remember { mutableIntStateOf(0) }
    var wishes by remember { mutableStateOf(store.wishes()) }
    var pays by remember { mutableStateOf(store.pays()) }
    var orders by remember { mutableStateOf(store.orders()) }
    var adding by remember { mutableStateOf(false) }

    // The add dialog belongs to whichever tab is showing, so leaving a tab closes it.
    LaunchedEffect(tab) { adding = false }

    val addLabel = when (tab) {
        0 -> "Додати бажання"
        2 -> "Додати витрату"
        3 -> "Додати покупку"
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
                        Icons.Default.MoreHoriz to "Ще"
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
                    0 -> WishlistScreen(wishes, { wishes = it; store.saveWishes(it) }, context, adding) { adding = it }
                    1 -> CalculatorScreen()
                    2 -> PaymentsScreen(pays, { pays = it; store.savePays(it) }, adding) { adding = it }
                    3 -> OrdersScreen(orders, { orders = it; store.saveOrders(it) }, context, adding) { adding = it }
                    else -> SettingsScreen(store) {
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
    context: Context,
    adding: Boolean,
    setAdding: (Boolean) -> Unit
) {
    var editing by remember { mutableStateOf<Wish?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
        if (items.isEmpty()) {
            item {
                GhostSlots(
                    listOf(
                        "назва товару" to "ціна і ціль",
                        "назва товару" to "ціна і ціль"
                    )
                )
            }
        }
        items(items, key = { it.id }) { wish ->
            WishCard(wish, context, onEdit = { editing = wish }, onDelete = { save(items - wish) })
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

@Composable
fun WishCard(wish: Wish, context: Context, onEdit: () -> Unit, onDelete: () -> Unit) {
    val first = wish.history.firstOrNull() ?: wish.price
    val change = if (first > 0) (wish.price - first) / first * 100 else 0.0
    Card(
        Modifier.padding(horizontal = Space.screen, vertical = Space.sm).fillMaxWidth(),
        shape = Radius.lg
    ) {
        // A photo earns its 190dp. Without one the block was a dead grey rectangle,
        // so the name carries the card instead.
        if (wish.image.isNotBlank()) {
            AsyncImage(
                wish.image, wish.name,
                Modifier.fillMaxWidth().height(190.dp).background(SurfaceRaised),
                contentScale = ContentScale.Crop
            )
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
            Text(money(wish.price), fontSize = Type.sectionSize, color = Accent, fontWeight = Type.strong)
            if (wish.targetPrice > 0) {
                Text(
                    "Ціль: ${money(wish.targetPrice)}",
                    color = TextSecondary,
                    fontSize = Type.captionSize
                )
            }
            PriceChart(wish.history, Modifier.fillMaxWidth().height(76.dp).padding(top = Space.md))
            Text(
                "${measurementsLabel(wish.history.size)} · історія до 90",
                color = TextSecondary,
                fontSize = Type.captionSize
            )
            // Everything sits on the left. The floating action button owns the
            // bottom right of the screen, and it was covering these controls.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, wish.url.toUri())) }) {
                    Text("До магазину ↗")
                }
                IconButton(onEdit) { Icon(Icons.Default.Edit, "Редагувати") }
                IconButton(onDelete) { Icon(Icons.Default.DeleteOutline, "Видалити") }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun PriceChart(values: List<Double>, modifier: Modifier = Modifier) {
    val points = values.filter { it > 0 }
    Canvas(modifier) {
        if (points.size < 2) {
            drawLine(TextDisabled, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f, StrokeCap.Round)
            return@Canvas
        }
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = size.width * index / (points.size - 1)
            val y = size.height - ((value - min) / range * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (points.last() <= points.first()) Accent else Negative, style = Stroke(5f, cap = StrokeCap.Round))
    }
}

@Composable
fun CalculatorScreen() {
    var amount by remember { mutableStateOf("") }
    var hryvniaToDollar by remember { mutableStateOf(true) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf("+") }
    var rate by remember { mutableStateOf(FxRate()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { usdRate() }.onSuccess { rate = it }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

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
                                    else "Немає даних",
                                    fontWeight = FontWeight.Bold
                                )
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
                        Text(
                            if (hryvniaToDollar) "${"%.2f".format(converted)} USD" else money(converted),
                            Modifier.padding(top = Space.lg),
                            color = if (converted == 0.0) TextDisabled else Accent,
                            fontSize = Type.heroSize,
                            lineHeight = Type.heroLine,
                            letterSpacing = Type.heroTracking,
                            fontWeight = if (converted == 0.0) Type.regular else FontWeight.Black
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
    adding: Boolean,
    setAdding: (Boolean) -> Unit
) {
    val monthly = items.sumOf { it.amount }
    var editing by remember { mutableStateOf<Int?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = Space.fabClearance)) {
        item {
            ScreenHeader("ЩОМІСЯЦЯ", "Постійні витрати", "Оренда, комуналка, зв'язок і підписки")
            Column(Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xl)) {
                SummaryCard("Разом на місяць", money(monthly), monthly <= 0.0)
            }
        }
        if (items.isEmpty()) {
            item {
                GhostSlots(
                    listOf(
                        "оренда, комуналка" to "сума і день оплати",
                        "інтернет, підписки" to "сума і день оплати"
                    )
                )
            }
        }
        itemsIndexed(items) { index, pay ->
            Card(
                Modifier.padding(horizontal = Space.screen, vertical = Space.xs).fillMaxWidth(),
                shape = Radius.md
            ) {
                ListItem(
                    modifier = Modifier.clickable { editing = index },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Surface(color = SurfaceRaised, shape = Radius.sm, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    when {
                                        pay.name.contains("Оренда", true) -> Icons.Default.Home
                                        pay.name.contains("Комун", true) -> Icons.Default.Bolt
                                        pay.name.contains("Інтернет", true) -> Icons.Default.Wifi
                                        else -> Icons.Default.Autorenew
                                    }, null, tint = Accent
                                )
                            }
                        }
                    },
                    headlineContent = {
                        Text(pay.name, fontSize = Type.cardTitleSize, fontWeight = Type.medium)
                    },
                    supportingContent = {
                        Text("${pay.day} числа щомісяця", fontSize = Type.captionSize)
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(money(pay.amount), fontWeight = Type.strong)
                            IconButton({ save(items.filterIndexed { i, _ -> i != index }) }) {
                                Icon(Icons.Default.Close, "Видалити")
                            }
                        }
                    }
                )
            }
        }
    }
    if (adding) AddPaymentDialog({ setAdding(false) }) {
        save(items + it)
        setAdding(false)
    }
    editing?.let { index ->
        items.getOrNull(index)?.let { pay ->
            EditPaymentDialog(pay, { editing = null }) { changed ->
                save(items.mapIndexed { i, item -> if (i == index) changed else item })
                editing = null
            }
        }
    }
}

@Composable
fun AddPaymentDialog(close: () -> Unit, add: (Pay) -> Unit) {
    val types = listOf("Оренда квартири", "Комуналка", "Інтернет", "Мобільний", "Підписка", "Інше")
    var selected by remember { mutableStateOf(types.first()) }
    var custom by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Нова постійна витрата") },
        text = {
            Column {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    items(types) { type -> FilterChip(selected == type, { selected = type }, { Text(type) }) }
                }
                if (selected == "Інше") OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), label = { Text("Назва") })
                NumberField("Сума, ₴", amount) { amount = it }
                NumberField("День оплати", day) { day = it }
            }
        },
        confirmButton = {
            Button({
                amount.replace(',', '.').toDoubleOrNull()?.let { value ->
                    add(Pay(if (selected == "Інше") custom.ifBlank { "Інше" } else selected, value, day.toIntOrNull()?.coerceIn(1, 31) ?: 1))
                }
            }, enabled = amount.replace(',', '.').toDoubleOrNull() != null) { Text("Додати") }
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
    LazyColumn(contentPadding = PaddingValues(bottom = Space.fabClearance)) {
        item {
            ScreenHeader("ДОСТАВКА", "Мої покупки", "Вставте посилання — решту FlowPay заповнить сам")
        }
        if (items.isEmpty()) {
            item {
                GhostSlots(
                    listOf(
                        "замовлено" to "оформлено, ще не відправлено",
                        "в дорозі" to "їде, є трек-номер",
                        "отримано" to "покупка закрита"
                    )
                )
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
                    }
                }
                LazyRow(
                    Modifier.padding(horizontal = Space.lg),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    items(listOf("Замовлено", "В дорозі", "Отримано")) { status ->
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
fun SettingsScreen(store: Store, onImported: () -> Unit) {
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
            ScreenHeader("FLOWPAY", "Налаштування")
            // Filled list items painted a large lighter block across the screen and
            // left a hard seam under the header. They sit on the page instead.
            SettingsRow(Icons.Default.Sync, "Фонове оновлення", "Кожні 12 годин, коли є інтернет")
            SettingsRow(Icons.Default.NotificationsNone, "Сповіщення", "Про падіння та досягнення цільової ціни")
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

/** Corrects an existing recurring expense, so a typo no longer means delete and retype. */
@Composable
fun EditPaymentDialog(pay: Pay, close: () -> Unit, save: (Pay) -> Unit) {
    var amount by remember { mutableStateOf(pay.amount.toString()) }
    var day by remember { mutableStateOf(pay.day.toString()) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(pay.name) },
        text = {
            Column {
                NumberField("Сума, ₴", amount) { amount = it }
                NumberField("День оплати", day) { day = it }
            }
        },
        confirmButton = {
            Button(
                {
                    amount.replace(',', '.').toDoubleOrNull()?.let { value ->
                        save(pay.copy(amount = value, day = day.toIntOrNull()?.coerceIn(1, 31) ?: pay.day))
                    }
                },
                enabled = amount.replace(',', '.').toDoubleOrNull() != null
            ) { Text("Зберегти") }
        },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
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
fun SummaryCard(label: String, value: String, isEmpty: Boolean, hero: Boolean = true) {
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
        }
    }
}

/**
 * What an empty screen shows instead of a grey card saying there is no data.
 *
 * Each slot is an outlined placeholder the height of a real row, labelled with the
 * fields it will hold. The emptiness takes on the shape of the future content, so
 * it reads as a system waiting rather than a screen that failed to load. Left
 * aligned on the same edge as the title, and never stretched to fill the screen.
 */
@Composable
fun GhostSlots(fields: List<Pair<String, String>>) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        fields.forEach { (primary, secondary) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .border(1.dp, HairLine, Radius.md)
                    .padding(Space.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(56.dp).background(SurfaceRaised, Radius.sm))
                Spacer(Modifier.width(Space.md))
                Column {
                    Text(primary, color = TextDisabled, fontSize = Type.bodySize)
                    Spacer(Modifier.height(Space.xs))
                    Text(secondary, color = TextDisabled, fontSize = Type.captionSize)
                }
            }
        }
    }
}

