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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
    fun meta(key: String): String {
        val patterns = listOf(
            Regex("""<meta[^>]+(?:property|name)=["']${Regex.escape(key)}["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']${Regex.escape(key)}["']""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }.orEmpty()
    }
    val rawPrice = meta("product:price:amount").ifBlank {
        Regex("""\"price\"\s*:\s*[\"']?([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1).orEmpty()
    }
    val price = rawPrice.replace(',', '.').toDoubleOrNull() ?: 0.0
    require(price > 0) { "Не вдалося знайти ціну на сторінці" }
    Wish(
        id = System.currentTimeMillis().toString(),
        name = meta("og:title").replace("&quot;", "\"").ifBlank { "Новий товар" },
        url = normalizedLink,
        image = meta("og:image"),
        price = price,
        history = listOf(price)
    )
}

data class FxRate(val buy: Double = 0.0, val sell: Double = 0.0)
data class UpdateInfo(val versionCode: Int, val versionName: String, val downloadUrl: String)

suspend fun usdRate(): FxRate = withContext(Dispatchers.IO) {
    val array = JSONArray(URL("https://api.monobank.ua/bank/currency").readText())
    val item = (0 until array.length()).map { array.getJSONObject(it) }
        .first { it.optInt("currencyCodeA") == 840 && it.optInt("currencyCodeB") == 980 }
    FxRate(item.optDouble("rateBuy"), item.optDouble("rateSell"))
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

val Accent = Color(0xffd7ff63)
val AppBackground = Color(0xff090a08)
val CardBackground = Color(0xff1a1b18)
val TextPrimary = Color(0xfff1f3ec)
private fun money(value: Double) = NumberFormat.getNumberInstance(Locale("uk", "UA")).format(value) + " ₴"

@Composable
fun FlowPayApp(context: Context) {
    val store = remember { Store(context) }
    var tab by remember { mutableIntStateOf(0) }
    var wishes by remember { mutableStateOf(store.wishes()) }
    var pays by remember { mutableStateOf(store.pays()) }
    var orders by remember { mutableStateOf(store.orders()) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = AppBackground,
            surface = CardBackground,
            onBackground = TextPrimary,
            onSurface = TextPrimary
        )
    ) {
        Scaffold(containerColor = Color.Transparent, contentColor = TextPrimary, bottomBar = {
            NavigationBar(containerColor = Color(0xff141512), tonalElevation = 0.dp) {
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
                        label = { Text(item.second, fontSize = 9.sp, maxLines = 1) },
                        alwaysShowLabel = false
                    )
                }
            }
        }) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xff0d100b), AppBackground, Color.Black))
                    )
                    .padding(padding)
            ) {
                when (tab) {
                    0 -> WishlistScreen(wishes, { wishes = it; store.saveWishes(it) }, context)
                    1 -> CalculatorScreen()
                    2 -> PaymentsScreen(pays) { pays = it; store.savePays(it) }
                    3 -> OrdersScreen(orders, { orders = it; store.saveOrders(it) }, context)
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
fun ScreenHeader(kicker: String, title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Accent, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(30.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("F", color = Color(0xff10120d), fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.width(9.dp))
            Text(kicker, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 31.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp)
        subtitle?.let { Text(it, color = Color(0xff9b9d96), fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp)) }
    }
}

@Composable
fun WishlistScreen(items: List<Wish>, save: (List<Wish>) -> Unit, context: Context) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Wish?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader("FLOWPAY", "Мої бажання", "Ціна, ціль та історія в одному місці")
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                Button({ adding = true }, Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Add, null); Text(" Додати")
                }
                Spacer(Modifier.width(10.dp))
                FilledTonalButton(onClick = {
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
                }, enabled = !refreshing && items.isNotEmpty(), shape = RoundedCornerShape(16.dp)) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, null)
                    Text(" Оновити")
                }
            }
            message?.let { Text(it, Modifier.padding(horizontal = 22.dp, vertical = 8.dp), color = Color.Gray) }
        }
        if (items.isEmpty()) item { EmptyCard("Додайте посилання на товар — фото й ціна підтягнуться автоматично") }
        items(items, key = { it.id }) { wish ->
            WishCard(wish, context, onEdit = { editing = wish }, onDelete = { save(items - wish) })
        }
    }
    if (adding) AddWishDialog({ adding = false }, { wish -> save(items + wish); adding = false })
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
            OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Категорія") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
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
                OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Категорія") })
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
    Card(Modifier.padding(horizontal = 12.dp, vertical = 7.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        AsyncImage(
            wish.image, wish.name,
            Modifier.fillMaxWidth().height(190.dp).background(Color(0xff262724)),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.padding(18.dp)) {
            Row { AssistChip({}, { Text(wish.category) }); Spacer(Modifier.weight(1f)); Text("%+.1f%%".format(change), color = if (change <= 0) Accent else Color(0xffff6b6b), fontWeight = FontWeight.Bold) }
            Text(wish.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(money(wish.price), fontSize = 29.sp, color = Accent, fontWeight = FontWeight.Black)
            if (wish.targetPrice > 0) Text("Ціль: ${money(wish.targetPrice)}", color = Color.LightGray)
            PriceChart(wish.history, Modifier.fillMaxWidth().height(76.dp).padding(top = 10.dp))
            Text("${wish.history.size} вимірювань · останні 90", color = Color.Gray, fontSize = 11.sp)
            Row {
                TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, wish.url.toUri())) }) { Text("До магазину ↗") }
                Spacer(Modifier.weight(1f))
                IconButton(onEdit) { Icon(Icons.Default.Edit, "Редагувати") }
                IconButton(onDelete) { Icon(Icons.Default.DeleteOutline, "Видалити") }
            }
        }
    }
}

@Composable
fun PriceChart(values: List<Double>, modifier: Modifier = Modifier) {
    val points = values.filter { it > 0 }
    Canvas(modifier) {
        if (points.size < 2) {
            drawLine(Color.DarkGray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f, StrokeCap.Round)
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
        drawPath(path, if (points.last() <= points.first()) Accent else Color(0xffff6b6b), style = Stroke(5f, cap = StrokeCap.Round))
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

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader("MONOBANK", "Курс і суми", "Конвертація валют та швидкі розрахунки")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xff20221d))) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("USD / UAH", color = Color.Gray, fontSize = 12.sp)
                                Text(
                                    if (rate.sell > 0) "Купівля ${"%.2f".format(rate.buy)} · продаж ${"%.2f".format(rate.sell)}"
                                    else "Немає даних",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton({ refresh() }) {
                                if (loading) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, "Оновити")
                            }
                        }
                        NumberField(if (hryvniaToDollar) "Сума у гривнях" else "Сума у доларах", amount) { amount = it }
                        FilledTonalButton({ hryvniaToDollar = !hryvniaToDollar }, Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Icon(Icons.Default.SwapVert, null)
                            Text(if (hryvniaToDollar) " UAH → USD" else " USD → UAH")
                        }
                        Text(
                            if (hryvniaToDollar) "${"%.2f".format(converted)} USD" else money(converted),
                            Modifier.padding(top = 16.dp), color = Accent, fontSize = 32.sp, fontWeight = FontWeight.Black
                        )
                    }
                }

                Text("Калькулятор сум", Modifier.padding(top = 24.dp, bottom = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { NumberField("Перша сума", first) { first = it } }
                    Box(Modifier.weight(1f)) { NumberField("Друга сума", second) { second = it } }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("+", "−", "×", "÷").forEach { symbol ->
                        FilterChip(operation == symbol, { operation = symbol }, { Text(symbol, fontSize = 18.sp) }, modifier = Modifier.weight(1f))
                    }
                }
                SummaryCard("Результат", NumberFormat.getNumberInstance(Locale("uk", "UA")).format(total), Accent)
            }
        }
    }
}

@Composable
fun PaymentsScreen(items: List<Pay>, save: (List<Pay>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader("ЩОМІСЯЦЯ", "Постійні витрати", "Оренда, комуналка, зв'язок і підписки")
            Column(Modifier.padding(horizontal = 20.dp)) {
                SummaryCard("Разом на місяць", money(items.sumOf { it.amount }), Accent)
                Button({ adding = true }, Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Add, null); Text(" Додати витрату")
                }
            }
        }
        if (items.isEmpty()) item { EmptyCard("Додайте оренду квартири, комуналку, інтернет або підписку") }
        items(items) { pay ->
            Card(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Surface(color = Color(0xff30332a), shape = RoundedCornerShape(14.dp), modifier = Modifier.size(44.dp)) {
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
                    headlineContent = { Text(pay.name, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("${pay.day} числа щомісяця") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(money(pay.amount), fontWeight = FontWeight.Bold)
                            IconButton({ save(items - pay) }) { Icon(Icons.Default.Close, "Видалити") }
                        }
                    }
                )
            }
        }
    }
    if (adding) AddPaymentDialog({ adding = false }) {
        save(items + it)
        adding = false
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
fun OrdersScreen(items: List<Order>, save: (List<Order>) -> Unit, context: Context) {
    var adding by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader("ДОСТАВКА", "Мої покупки", "Вставте посилання — решту FlowPay заповнить сам")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Button({ adding = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.AddLink, null); Text(" Додати посилання")
                }
            }
        }
        if (items.isEmpty()) item { EmptyCard("Скопіюйте посилання на придбаний товар — назва, фото й ціна підтягнуться автоматично") }
        items(items, key = { it.id }) { order ->
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.padding(12.dp)) {
                    AsyncImage(
                        order.image, order.name,
                        Modifier.size(92.dp).background(Color(0xff292a27), RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(order.status.uppercase(), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(order.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                        if (order.price > 0) Text(money(order.price), fontWeight = FontWeight.Black, fontSize = 19.sp)
                    }
                }
                LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("Замовлено", "В дорозі", "Отримано")) { status ->
                        FilterChip(order.status == status, { save(items.map { if (it.id == order.id) it.copy(status = status) else it }) }, { Text(status, fontSize = 11.sp) })
                    }
                }
                Row(Modifier.padding(horizontal = 10.dp)) {
                    TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, order.url.toUri())) }) { Text("До магазину ↗") }
                    Spacer(Modifier.weight(1f))
                    IconButton({ save(items - order) }) { Icon(Icons.Default.DeleteOutline, "Видалити") }
                }
            }
        }
    }
    if (adding) AddOrderDialog({ adding = false }) {
        save(items + it)
        adding = false
    }
}

@Composable
fun AddOrderDialog(close: () -> Unit, add: (Order) -> Unit) {
    var link by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Додати покупку") },
        text = {
            Column {
                Text("Вставте посилання на сторінку придбаного товару.")
                OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Посилання") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Button({
                scope.launch {
                    loading = true
                    error = null
                    runCatching { product(link) }
                        .onSuccess { item ->
                            add(Order(item.id, item.name, item.url, "Замовлено", image = item.image, price = item.price))
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
            ListItem(leadingContent = { Icon(Icons.Default.Sync, null) }, headlineContent = { Text("Фонове оновлення") }, supportingContent = { Text("Кожні 12 годин, коли є інтернет") })
            ListItem(leadingContent = { Icon(Icons.Default.NotificationsNone, null) }, headlineContent = { Text("Сповіщення") }, supportingContent = { Text("Про падіння та досягнення цільової ціни") })
            ListItem(leadingContent = { Icon(Icons.Default.Security, null) }, headlineContent = { Text("Приватність") }, supportingContent = { Text("Вішлісти й фінанси зберігаються лише на телефоні. API-ключі не вшиті в APK.") })
            HorizontalDivider()
            ListItem(
                modifier = Modifier.padding(top = 8.dp),
                leadingContent = { Icon(Icons.Default.SystemUpdate, null, tint = Accent) },
                headlineContent = { Text("Оновлення FlowPay", fontWeight = FontWeight.Bold) },
                supportingContent = { Text("Встановлено: ${BuildConfig.VERSION_NAME}") },
                trailingContent = {
                    FilledTonalButton(
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
            message?.let { Text(it, Modifier.padding(20.dp), color = Accent) }
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

@Composable
fun NumberField(label: String, value: String, set: (String) -> Unit) = OutlinedTextField(
    value, set, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
)

@Composable
fun SummaryCard(label: String, value: String, color: Color) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xff242520)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) { Text(label, color = Color.Gray); Text(value, fontSize = 28.sp, fontWeight = FontWeight.Black, color = color) }
    }
}

@Composable
fun EmptyCard(text: String) {
    Card(Modifier.padding(20.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp)) { Text(text, Modifier.padding(24.dp), color = Color.Gray) }
}

