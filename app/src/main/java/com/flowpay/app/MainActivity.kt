package com.flowpay.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
data class Order(val id: String, val name: String, val url: String, val status: String, val tracking: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
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
        Order(it.optString("id"), it.optString("n"), it.optString("u"), it.optString("s", "Замовлено"), it.optString("t"))
    }
    fun saveOrders(items: List<Order>) = save("orders", items.map {
        JSONObject().put("id", it.id).put("n", it.name).put("u", it.url).put("s", it.status).put("t", it.tracking)
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
        prefs.edit()
            .putString("w", wishes.toString())
            .putString("pay", payments.toString())
            .putString("orders", orders.toString())
            .apply()
    }

    private fun <T> jsonList(key: String, map: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).map { map(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(key: String, values: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }
}

suspend fun product(link: String): Wish = withContext(Dispatchers.IO) {
    val connection = URL(link).openConnection() as HttpURLConnection
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
        url = link,
        image = meta("og:image"),
        price = price,
        history = listOf(price)
    )
}

suspend fun usdRate(): Double = withContext(Dispatchers.IO) {
    val array = JSONArray(URL("https://api.monobank.ua/bank/currency").readText())
    (0 until array.length()).map { array.getJSONObject(it) }
        .first { it.optInt("currencyCodeA") == 840 && it.optInt("currencyCodeB") == 980 }
        .optDouble("rateSell")
}

val Accent = Color(0xffd7ff63)
val AppBackground = Color(0xff090a08)
val CardBackground = Color(0xff1a1b18)
private fun money(value: Double) = NumberFormat.getNumberInstance(Locale("uk", "UA")).format(value) + " ₴"

@Composable
fun FlowPayApp(context: Context) {
    val store = remember { Store(context) }
    var tab by remember { mutableIntStateOf(0) }
    var wishes by remember { mutableStateOf(store.wishes()) }
    var pays by remember { mutableStateOf(store.pays()) }
    var orders by remember { mutableStateOf(store.orders()) }

    LaunchedEffect(Unit) {
        if (wishes.isEmpty()) runCatching {
            product("https://prom.ua/ua/p2522203669-muzhskie-krossovki-asics.html")
        }.onSuccess { wishes = listOf(it); store.saveWishes(wishes) }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = AppBackground, surface = CardBackground)) {
        Scaffold(containerColor = AppBackground, bottomBar = {
            NavigationBar(containerColor = CardBackground) {
                val tabs = listOf(
                    Icons.Default.FavoriteBorder to "Бажання",
                    Icons.Default.Calculate to "План",
                    Icons.Default.ReceiptLong to "Платежі",
                    Icons.Default.LocalShipping to "Замовлення",
                    Icons.Default.MoreHoriz to "Ще"
                )
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(tab == index, { tab = index }, { Icon(item.first, item.second) }, label = { Text(item.second, fontSize = 10.sp) })
                }
            }
        }) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    0 -> WishlistScreen(wishes, { wishes = it; store.saveWishes(it) }, context)
                    1 -> PlannerScreen(wishes, pays)
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
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 14.dp)) {
        Text(kicker, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Black)
        subtitle?.let { Text(it, color = Color.Gray, fontSize = 14.sp) }
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
                                old.copy(name = now.name, image = now.image.ifBlank { old.image }, price = now.price, history = (old.history + now.price).takeLast(90))
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
        }, enabled = link.startsWith("http") && !loading) { Text(if (loading) "Зчитую…" else "Додати") }
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
        AsyncImage(wish.image, wish.name, Modifier.fillMaxWidth().height(220.dp).background(Color(0xff262724)))
        Column(Modifier.padding(18.dp)) {
            Row { AssistChip({}, { Text(wish.category) }); Spacer(Modifier.weight(1f)); Text("%+.1f%%".format(change), color = if (change <= 0) Accent else Color(0xffff6b6b), fontWeight = FontWeight.Bold) }
            Text(wish.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(money(wish.price), fontSize = 29.sp, color = Accent, fontWeight = FontWeight.Black)
            if (wish.targetPrice > 0) Text("Ціль: ${money(wish.targetPrice)}", color = Color.LightGray)
            PriceChart(wish.history, Modifier.fillMaxWidth().height(76.dp).padding(top = 10.dp))
            Text("${wish.history.size} вимірювань · останні 90", color = Color.Gray, fontSize = 11.sp)
            Row {
                TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(wish.url))) }) { Text("До магазину ↗") }
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
fun PlannerScreen(wishes: List<Wish>, pays: List<Pay>) {
    var advance by remember { mutableStateOf("") }
    var salary by remember { mutableStateOf("") }
    var expenses by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Wish?>(null) }
    var rate by remember { mutableDoubleStateOf(0.0) }
    val scope = rememberCoroutineScope()
    val income = (advance.toDoubleOrNull() ?: 0.0) + (salary.toDoubleOrNull() ?: 0.0)
    val reserved = (expenses.toDoubleOrNull() ?: 0.0) + pays.sumOf { it.amount }
    val available = income - reserved
    val missing = ((chosen?.price ?: 0.0) - available).coerceAtLeast(0.0)
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader("ПЛАНУВАННЯ", "Калькулятор покупки", "Порахуйте, коли бажання стане доступним")
            Column(Modifier.padding(horizontal = 20.dp)) {
                NumberField("Аванс", advance) { advance = it }
                NumberField("Основна зарплата", salary) { salary = it }
                NumberField("Інші витрати", expenses) { expenses = it }
                Text("Оберіть товар", color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(wishes) { wish -> FilterChip(chosen?.id == wish.id, { chosen = wish }, { Text(wish.name, maxLines = 1) }) }
                }
                SummaryCard("Вільно після платежів", money(available), if (available >= 0) Accent else Color(0xffff6b6b))
                chosen?.let { Text(if (missing == 0.0) "На ${it.name} уже вистачає" else "До покупки бракує ${money(missing)}", Modifier.padding(top = 10.dp), fontWeight = FontWeight.Bold) }
                FilledTonalButton({ scope.launch { rate = runCatching { usdRate() }.getOrDefault(0.0) } }, Modifier.fillMaxWidth().padding(top = 14.dp)) { Text("Оновити курс Monobank") }
                if (rate > 0) Text("1 USD = ${"%.2f".format(rate)} ₴ · доступно ${"%.2f".format(available / rate)} USD", Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
fun PaymentsScreen(items: List<Pay>, save: (List<Pay>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("1") }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader("ЩОМІСЯЦЯ", "Регулярні платежі", "Підписки, комунальні та обов'язкові витрати")
            Column(Modifier.padding(horizontal = 20.dp)) {
                SummaryCard("Разом на місяць", money(items.sumOf { it.amount }), Accent)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 14.dp), label = { Text("Назва платежу") })
                NumberField("Сума", amount) { amount = it }
                NumberField("День місяця", day) { day = it }
                Button({ amount.toDoubleOrNull()?.let { save(items + Pay(name.ifBlank { "Платіж" }, it, day.toIntOrNull()?.coerceIn(1, 31) ?: 1)); name = ""; amount = "" } }, Modifier.fillMaxWidth()) { Text("Додати платіж") }
            }
        }
        items(items) { pay -> ListItem(headlineContent = { Text(pay.name) }, supportingContent = { Text("${money(pay.amount)} · ${pay.day} числа") }, trailingContent = { IconButton({ save(items - pay) }) { Icon(Icons.Default.DeleteOutline, "Видалити") } }) }
    }
}

@Composable
fun OrdersScreen(items: List<Order>, save: (List<Order>) -> Unit, context: Context) {
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var tracking by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader("ДОСТАВКА", "Мої замовлення", "Зберігайте магазин, трек-номер і статус")
            Column(Modifier.padding(horizontal = 20.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Назва товару") })
                OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Посилання на замовлення") })
                OutlinedTextField(tracking, { tracking = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Трек-номер") })
                Button({ save(items + Order(System.currentTimeMillis().toString(), name.ifBlank { "Замовлення" }, link, "Замовлено", tracking)); name = ""; link = ""; tracking = "" }, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = name.isNotBlank()) { Text("Додати замовлення") }
            }
        }
        if (items.isEmpty()) item { EmptyCard("Тут з'являться ваші активні замовлення") }
        items(items, key = { it.id }) { order ->
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(order.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(order.status, color = Accent)
                    if (order.tracking.isNotBlank()) Text("Трек: ${order.tracking}", color = Color.Gray)
                    Row {
                        listOf("Замовлено", "В дорозі", "Отримано").forEach { status -> TextButton({ save(items.map { if (it.id == order.id) it.copy(status = status) else it }) }) { Text(status, fontSize = 11.sp) } }
                    }
                    Row {
                        if (order.url.startsWith("http")) TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(order.url))) }) { Text("Відкрити ↗") }
                        Spacer(Modifier.weight(1f))
                        IconButton({ save(items - order) }) { Icon(Icons.Default.DeleteOutline, "Видалити") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(store: Store, onImported: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
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
