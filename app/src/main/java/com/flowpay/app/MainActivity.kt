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
import androidx.lifecycle.Lifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * One shop a wish is watched at.
 *
 * The same headphones sit in Rozetka, Comfy and Allo, and as three separate
 * wishes they were three separate histories that only a person comparing them by
 * eye could put together. A source is a place to read, with everything that is
 * true of that place and not of the others: its own followed variant, its own
 * freshness, its own money.
 *
 * [price] is always hryvnia, converted where the shop priced it in something
 * else, so the wish can pick a cheapest without comparing dollars against
 * hryvnia. [amount] and [currency] keep what the shop actually printed, because
 * once converted a figure cannot say whether it moved or the rate did.
 */
data class WishSource(
    val url: String,
    /** Hryvnia, converted where needed. Zero when this shop has no readable price. */
    val price: Double = 0.0,
    /** Which of this page's prices is followed here. Pages differ, so this is per shop. */
    val variant: String = "",
    /**
     * How much this one shop's figure is worth believing.
     *
     * Per shop rather than per wish, which is the point of the whole change: one
     * shop dropping the page must not put a warning on a wish that two others are
     * still answering for.
     */
    val freshness: Freshness = Freshness.OK,
    /** Epoch day this shop was last read. Zero means never. */
    val checkedDay: Long = 0L,
    /** The figure the shop printed, in its own money. */
    val amount: Double = 0.0,
    /** ISO code of [amount]. Hryvnia unless the page said otherwise. */
    val currency: String = UAH,
    /** Hryvnia per unit of [currency] when [price] was worked out. One for hryvnia. */
    val rate: Double = 0.0,
    /**
     * What this shop's page last declared about being able to buy the thing.
     *
     * Kept beside [freshness] rather than folded into it because the two answer
     * different questions and the row needs both: [freshness] says whether the
     * figure can be believed, which is all the arithmetic cares about, while this
     * says why — and it is the only thing that can tell "немає в наявності" from
     * "знято з продажу", which are the same amount of not-buyable and opposite
     * advice about whether to keep waiting.
     *
     * [Availability.UNKNOWN] on everything saved before this existed, and on the
     * majority of shops, which declare nothing. Nothing is derived from it that a
     * shop's silence could get wrong.
     */
    val availability: Availability = Availability.UNKNOWN,
    /**
     * The crossed-out "was" price the page declares beside [price], in hryvnia.
     * Zero when it declares none. See Discounts.kt.
     */
    val listPrice: Double = 0.0,
    /**
     * What this shop asks a member of its loyalty programme — Rozetka's card — in
     * hryvnia. Zero when the page states none. A field of the shop, never an
     * edition to pick and never the price: see [memberOfferIn] and PricesMore.kt.
     */
    val memberPrice: Double = 0.0,
    /** The programme [memberPrice] is for, as the page names it. Empty with no price. */
    val memberTier: String = ""
)

data class Wish(
    val id: String,
    val name: String,
    /**
     * The shop the price standing now came from.
     *
     * Kept alongside [sources] rather than replaced by it: this is what "До
     * магазину" opens and what a shared link is matched against, and on a wish
     * watched in three shops the answer is the one that is currently cheapest.
     */
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
    val variant: String = "",
    /**
     * What the shop says about the thing: description, brand, rating, specs.
     *
     * Empty for most wishes, and emphatically not a failure — only what a page
     * declares about itself in a standard place is read, and plenty of shops
     * declare nothing. Refreshed with the price, because a shop that starts
     * filling these in should not need the wish to be added again.
     */
    val about: ProductAbout = ProductAbout(),
    /**
     * How much the price above is currently worth believing.
     *
     * Defaults to [Freshness.OK] so that every wish saved before this existed reads
     * back the way it was last seen: those were all read from a page successfully,
     * and starting them as doubtful would put a warning on a whole healthy list.
     */
    val freshness: Freshness = Freshness.OK,
    /**
     * Epoch day this wish was added. Zero on wishes saved before it was recorded;
     * [addedDay] recovers those from the first price ever taken for them.
     */
    val addedDay: Long = 0L,
    /**
     * Epoch day a deliberate hold runs out. Zero means no hold.
     *
     * A wishlist's job is to put distance between the urge and the decision, and
     * the list alone does not do that — this is the app using time as the tool.
     */
    val holdUntil: Long = 0L,
    /**
     * Why it is wanted, in the owner's own words. Optional and short.
     *
     * Listing reasons is one of the two things the CHI 2019 study on impulse
     * buying found to work (the other is a day's delay, which is the hold). It is
     * read back at the two moments it can change something: when a pause ends,
     * and when "Я купив це" is pressed.
     */
    val why: String = "",
    /**
     * Every shop this thing is watched in, cheapest wins.
     *
     * Empty on a wish saved before a wish could have more than one shop, and on
     * one built in code from a single address. [wishSources] is what everything
     * reads, and it makes that single address look like the one source it always
     * was, so nothing downstream has to know which kind it is holding.
     *
     * The price history stays one series whatever this holds: it records the best
     * price on each day, because [priceInsight], the thirty-day reference window
     * and the chart all assume a single line, and three lines would leave the
     * verdict measuring nothing in particular.
     */
    val sources: List<WishSource> = emptyList(),
    /**
     * What to look this thing up as elsewhere, when the person has corrected it.
     *
     * Empty on every wish nobody has corrected, and emphatically not "not set up
     * yet": empty means [builtSearchTerms] decides, which is what should happen
     * for all but the handful of things the app gets wrong. Storing the built
     * query here as well would freeze each wish against the builder it was added
     * under, so that fixing the builder fixed nothing that already existed.
     *
     * Real data rather than a view preference — it is a sentence the person wrote
     * about their own thing and losing it in a restore would be losing their work
     * — so unlike the folded sections it belongs in [Store.exportJson], which it
     * reaches by being part of [wishJson].
     */
    val searchQuery: String = "",
    /**
     * What a model last wrote about what kind of thing this is, and for which price.
     *
     * Null on every wish nobody has asked about, which is most of them and is not
     * a failure — see [appraisalGate], which keeps the whole section off the
     * screen for a wish the app cannot honestly ask about at all.
     *
     * Cached rather than regenerated on every open for two reasons that pull the
     * same way: each generation is a paid call, and a verdict whose wording
     * changes every time the screen is opened reads as noise rather than as an
     * opinion. It carries the price it was written against so that
     * [appraisalStale] can say the price has moved instead of the screen quietly
     * showing an old judgement about a different number.
     */
    val appraisal: Appraisal? = null,
    /** Duels this wish won and took part in — see Ideas.kt. Nought before any. */
    val duelWins: Int = 0,
    val duelsPlayed: Int = 0,
    /**
     * The monobank jar this wish is saved in. Empty when none. While set, [saved]
     * follows the jar's balance on every monobank pass — see MonoSync.kt.
     */
    val jar: String = "",
    /**
     * History points the owner set aside as a shop's glitch («Це був збій»), each
     * with the point that followed it. Moved out of [history], so nothing that reads
     * the history sees them; «Повернути» moves one back. See PricesMore.kt.
     */
    val excluded: List<SetAside> = emptyList(),
    /** Points the owner said were real prices, so the glitch hint stops asking. */
    val realPoints: List<PricePoint> = emptyList(),
    /** When the thing was sold out, so the chart's line stops there. PricesMore.kt. */
    val stockGaps: List<StockGap> = emptyList(),
    /**
     * Hotline's product page for the same thing, bound by the owner: the market's
     * lowest price and how many shops offer it. A yardstick — never the price,
     * never a push. Null on every wish nobody has bound one to. PricesMore.kt.
     */
    val market: Market? = null,
    /**
     * «Пропустити цього місяця»: the month, "2026-10", whose contribution the plan
     * does not ask for. Empty when none. Unlike [holdUntil] the wish stays in view
     * and its prices keep being watched; from the 1st the plan asks again by
     * itself. See MoneyPlan.kt.
     */
    val skipMonth: String = ""
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
    val warnDays: Int = DEFAULT_WARN_DAYS,
    /**
     * What this has cost before now, oldest first, ending at [amount].
     *
     * A subscription that raises its price quietly is how a subscription earns,
     * and an expense remembering only today's figure cannot see it happen: 269
     * was simply overwritten by 309 and nothing was left to compare against. The
     * same [PricePoint] a wish records its prices with, because the question is
     * the same one and a second shape for it would need a second chart, a second
     * mapping and a second set of bugs.
     *
     * Empty on every expense saved before this existed, and on one that has never
     * been edited — nothing fetches these, so a point is written only when a
     * person types a different number.
     */
    val amounts: List<PricePoint> = emptyList(),
    /**
     * Epoch day a free trial runs out. Zero means there is no trial.
     *
     * A subscription that starts free is the one whose first real charge nobody
     * remembers, because the moment worth remembering is a month after the moment
     * you signed up. The expense is real from the day it is added — it belongs on
     * the list and on the calendar — but it takes nothing until this day comes.
     */
    val trialEnd: Long = 0L,
    /**
     * Month of the year an annual charge lands in, 1..12. Zero means every month.
     *
     * One field rather than a period enum with an anchor beside it, because those
     * two can disagree — "раз на рік" with no month is a state nothing could
     * render — and this app has exactly two rhythms to tell apart. Zero is what
     * every expense already on the phone reads as, and that is the truth about
     * all of them: nothing here could express an annual charge before now.
     *
     * The blind spot it closes: an annual subscription charges once in twelve
     * months, so every "наступні 30 днів" view hid it eleven months out of twelve
     * while [yearlyCost] quietly multiplied it by twelve on top.
     */
    val billingMonth: Int = 0,
    /**
     * The emoji picked by hand. Empty means "guess from the name" — see Emoji.kt.
     *
     * Stored only when a person chose one, so a rename still re-guesses for every
     * payment nobody has touched, and an improved guess reaches them too.
     */
    val emoji: String = "",
    /**
     * «Частинами»: how many monthly payments the plan has. Nought means it runs
     * until it is deleted, which is every expense that is not a plan.
     */
    val instalments: Int = 0,
    /** The epoch day of the plan's first payment. Nought when it is not a plan. */
    val instalmentStart: Long = 0L,
    /**
     * The monobank merchant the owner confirmed as this payment — [merchantKey] of
     * the description he said «так» to. Empty until then. With it, the next charge
     * from that merchant ticks this payment by itself; see Mono.kt.
     */
    val monoMerchant: String = "",
    /**
     * The last day this payment takes money, as an epoch day; nought while it runs
     * on. «Скасував ✓» sets it to the end of the paid period («діє до»),
     * «Погасив достроково» to this month's payment, «Повернув» to yesterday. Every
     * count asks [runsOn], so nothing after this day is counted anywhere.
     */
    val stopsAfter: Long = 0L,
    /** Why [stopsAfter] is set: [STOP_CANCELLED], [STOP_PAID_OFF] or [STOP_RETURNED]. */
    val stopReason: String = "",
    /** The epoch day a pause began. Nought when the payment is not paused. */
    val pausedFrom: Long = 0L,
    /** Pauses already over, so the months they covered stay unasked. See PaymentsLife.kt. */
    val pauses: List<PauseSpan> = emptyList(),
    /**
     * What a charge takes before [trialEnd]. Nought is a free trial, which is what
     * every trial saved before this was: «150 ₴ до 1 лютого, далі 300 ₴» is a promo
     * of 150 with 300 as [amount]. Read through [priceOn].
     */
    val promoPrice: Double = 0.0,
    /** The owner's own «Як скасувати» address. Empty means the built-in one or a search. */
    val cancelUrl: String = "",
    /** The purchase a plan «частинами» pays for, by [Order.id]. Empty when none. */
    val order: String = ""
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
    val amountToPay: Double = 0.0,
    /**
     * What was actually handed over, which is not always [price].
     *
     * The shop's listed price is what the tracker watched; a promo code, a sale
     * that never reached the page, or a different shop entirely is what you paid.
     * Judging the wait against the listed price instead of this one would grade
     * the tracker on its own homework. Zero means it has not been recorded.
     */
    val paid: Double = 0.0,
    /**
     * The lowest price ever recorded for this thing while it was still a wish.
     *
     * Copied across at the moment of purchase, because the wish is deleted then
     * and its history goes with it. Zero means there was no history to judge.
     */
    val lowestSeen: Double = 0.0,
    /** How many times the thing has actually been used. Zero means uncounted. */
    val uses: Int = 0,
    /** Epoch day the purchase was closed and filed. Zero means it is still open. */
    val archivedDay: Long = 0L,
    /**
     * The rest of what the carrier last said about this parcel.
     *
     * Stored rather than re-fetched, so opening a parcel shows something at once
     * and shows it on a train with no signal. Empty until the first check.
     */
    val details: ParcelDetails = ParcelDetails(),
    /**
     * Every status change this app has seen, with the time it saw it.
     *
     * The one record here that cannot be fetched back from anywhere: Nova Poshta's
     * public method answers with the current status and nothing before it. Losing
     * this list would lose the only movement history the app is in a position to
     * have, which is why it travels through the bin and the backup like the rest.
     */
    val sightings: List<Sighting> = emptyList(),
    /**
     * The carrier's own status code as of the last check. Zero means never asked.
     *
     * [problem] says that something is wrong; this is what lets the screen say
     * which thing, through [problemNote]. Stored rather than derived, because the
     * response it came from is not kept and a parcel opened on a train with no
     * signal still has to be able to explain itself.
     */
    val statusCode: Int = 0,
    /**
     * Nothing travels: a game, a key, a subscription. See Purchases.kt.
     *
     * Such a purchase has no carrier and no stages, so it is drawn without the rail
     * and can be closed from the moment it is added.
     */
    val digital: Boolean = false,
    /**
     * The last day it can be sent back, as an epoch day. Zero means not tracked.
     *
     * Set when the purchase is filed — see Returns in Purchases.kt — and cleared by
     * «Залишаю». Finder found 6–8% of people missed a return because they forgot
     * or the window ran out; the morning message is where that is caught.
     */
    val returnBy: Long = 0L,
    /** The emoji picked by hand. Empty means "guess from the name" — see Emoji.kt. */
    val emoji: String = "",
    // ---- Parcels and purchases, second round (ParcelsMore.kt). JSON keys start "pk".
    /**
     * «Номер одержувача» — a parcel for somebody else is asked about with their
     * number, which is the only way Nova Poshta answers it in full. Empty means the
     * owner's own number from the settings, or none.
     */
    val recipientPhone: String = "",
    /** «Повертаю»: a return under way or done. Null when none was started. See [Refund]. */
    val refund: Refund? = null,
    /** The last day of the warranty, as an epoch day. Zero means none recorded. */
    val warrantyUntil: Long = 0L,
    /** «Як тобі …?»: 4 😍, 3 🙂, 2 😐, 1 😞; nought unanswered. See [Delight]. */
    val delight: Int = 0,
    /** «Купити таке ще раз?»: 1 yes, -1 no, nought unanswered. */
    val again: Int = 0,
    /** Epoch day [delight] was answered. Zero when it was not. */
    val delightDay: Long = 0L,
    /** The wish's «Чому хочу», carried over at «Я купив це» so the answer has its reason beside it. */
    val why: String = "",
    /** The wish's category, carried over with it. Empty for a purchase that was never a wish. */
    val category: String = "",
    /**
     * The epoch day this purchase went back and the plan «частинами» paying for it
     * was closed («Повернув» on the plan — see PaymentsLife.kt). Nought otherwise.
     */
    val planReturned: Long = 0L
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
        // Only on a real launch. A recreation — the phone turned, the theme changed —
        // hands back the same intent, and the share or refresh it carried would run
        // a second time.
        // Nor when reopened from the recents screen, which hands back the share that
        // first launched the task as though it had just been sent.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) command = commandOf(intent)
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
        // The dollar's corridor: an hourly check only while something is watched.
        RateWorker.schedule(this)
        // Only for an owner who connected monobank; nothing is asked of anyone else.
        if (MonoStore(this).connected()) MonoSync.schedule(this)
        // Enqueued whether or not a folder has been chosen: the worker checks, and
        // scheduling only once a folder exists would mean a folder chosen while the
        // app was already running never got a job at all.
        BackupWorker.schedule(this)
        // The eye on Огляд, as it was left — set before the first frame, so a
        // hidden sum is never drawn even once. See Privacy.kt.
        SumsMask.set(TouchPrefs(this).hideInside())
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
        // Through refreshWidget: a bare updateAll is ignored by a widget session
        // that is still running (Widget.kt).
        lifecycleScope.launch { refreshWidget(this@MainActivity) }
    }

    // Read as a CharSequence: a text/html share arrives as a styled Spanned, and
    // getStringExtra answers null for one rather than the text inside it.
    private fun commandOf(intent: Intent?): AppCommand? =
        appCommand(
            intent?.action,
            intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            intent?.getIntExtra(EXTRA_TAB, -1) ?: -1
        )
}

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("flowpay", Context.MODE_PRIVATE)

    // Read without repeated ids, whatever is stored: see [withoutRepeatedIds].
    fun wishes(): List<Wish> = withoutRepeatedIds(jsonList("w", ::wishOf)) { it.id }

    fun saveWishes(items: List<Wish>) = save("w", items.map(::wishJson))

    fun pays(): List<Pay> = jsonList("pay", ::payOf)

    fun savePays(items: List<Pay>) = save("pay", items.map(::payJson))

    fun orders(): List<Order> = withoutRepeatedIds(jsonList("orders", ::orderOf)) { it.id }

    fun saveOrders(items: List<Order>) = save("orders", items.map(::orderJson))

    /**
     * What has actually been paid, month by month.
     *
     * Pruned on the way out rather than on a schedule: nothing else runs often
     * enough to be trusted with it, and reading is the only moment the list is
     * certain to be looked at.
     */
    fun paidMarks(today: LocalDate = LocalDate.now()): List<PaidMark> =
        prunePaidMarks(
            jsonList("paid") {
                PaidMark(
                    it.optString("n"),
                    it.optString("m"),
                    it.optDouble("a", 0.0),
                    it.optString("cur", UAH).ifBlank { UAH }
                )
            },
            today
        )

    fun savePaidMarks(items: List<PaidMark>, today: LocalDate = LocalDate.now()) =
        save("paid", prunePaidMarks(items, today).map {
            JSONObject().put("n", it.name).put("m", it.month)
                .put("a", it.amount).put("cur", it.currency)
        })

    /** Deleted items, with anything past its thirty days already dropped. */
    fun bin(today: Long = LocalDate.now().toEpochDay()): List<BinEntry> = pruneBin(
        jsonList("bin") {
            BinEntry(
                id = it.optString("id"),
                kind = it.optString("k"),
                title = it.optString("t"),
                detail = it.optString("d"),
                payload = it.optString("p"),
                day = it.optLong("day", 0L)
            )
        },
        today
    )

    fun saveBin(items: List<BinEntry>, today: Long = LocalDate.now().toEpochDay()) =
        save("bin", pruneBin(items, today).map {
            JSONObject().put("id", it.id).put("k", it.kind).put("t", it.title)
                .put("d", it.detail).put("p", it.payload).put("day", it.day)
        })

    /**
     * Puts a deleted item in the bin and returns what the bin now holds.
     *
     * The payload is the item in the very shape [saveWishes] and its siblings
     * write, so restoring is a move rather than a reconstruction.
     */
    fun recycle(entry: BinEntry): List<BinEntry> {
        val next = pruneBin(bin() + entry, entry.day)
        saveBin(next, entry.day)
        return next
    }

    /**
     * Puts one entry back where it came from.
     *
     * Appends rather than restoring a position, because the position a wish had in
     * a list sorted by price is not a property of the wish. The item itself comes
     * back whole, which is the part that cannot be typed again.
     */
    fun restoreFromBin(id: String) {
        val entry = bin().firstOrNull { it.id == id } ?: return
        runCatching {
            val json = JSONObject(entry.payload)
            when (entry.kind) {
                // An item already back — the undo bar beat the bin to it — is not
                // added a second time; the entry is simply cleared.
                BIN_WISH -> wishOf(json).let { back ->
                    if (wishes().none { it.id == back.id }) saveWishes(wishes() + back)
                }
                BIN_PAY -> savePays(pays() + payOf(json))
                BIN_ORDER -> orderOf(json).let { back ->
                    if (orders().none { it.id == back.id }) saveOrders(orders() + back)
                }
                // A fund (MoneyPlan.kt), kept beside the other lists under its own key.
                BIN_FUND -> fundOf(json).let { back ->
                    val now = fundsOf(prefs.getString(PlanStore.FUNDS_KEY, "[]"))
                    if (now.none { it.id == back.id }) {
                        prefs.edit { putString(PlanStore.FUNDS_KEY, fundsJson(now + back)) }
                    }
                }
                // An entry of a kind this version does not know is left in the bin
                // rather than dropped: a newer build may be able to restore it.
                else -> return
            }
        }.onSuccess { dropFromBin(id) }
    }

    fun dropFromBin(id: String) = saveBin(bin().filterNot { it.id == id })

    fun emptyBin() = saveBin(emptyList())

    /** The folder the weekly copy is written into, as a tree uri. Blank means none. */
    fun backupFolder(): String = prefs.getString("bk_dir", "").orEmpty()

    fun saveBackupFolder(uri: String) = prefs.edit { putString("bk_dir", uri) }

    /** When the last automatic or on-demand copy was written. Zero means never. */
    fun lastBackupAt(): Long = prefs.getLong("bk_at", 0L)

    fun saveLastBackupAt(millis: Long) = prefs.edit { putLong("bk_at", millis) }
    /**
     * The interrupting alerts already sent, newest last, as keys like
     * "hold-<id>-<day>". See [ALERT_MEMORY]. A view of the past rather than data,
     * so it stays out of the backup.
     */
    fun alerted(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("alerted", "[]"))
        (0 until array.length()).map { array.optString(it) }
    }.getOrDefault(emptyList())

    fun saveAlerted(keys: List<String>) =
        prefs.edit { putString("alerted", JSONArray(keys.takeLast(ALERT_MEMORY)).toString()) }

    /** Each wish's price as the last morning message saw it. Not app data: no backup. */
    fun digestPrices(): Map<String, Double> = runCatching {
        val o = JSONObject(prefs.getString("digest_p", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optDouble(it, 0.0) }
    }.getOrDefault(emptyMap())

    fun saveDigestPrices(prices: Map<String, Double>) = prefs.edit {
        putString("digest_p", JSONObject().apply { prices.forEach { (id, price) -> put(id, price) } }.toString())
    }

    /** Epoch day the payment reminder last ran, so a day is never repeated. */
    fun lastReminderDay(): Long = prefs.getLong("reminded", 0L)

    fun saveLastReminderDay(day: Long) = prefs.edit { putLong("reminded", day) }

    /**
     * When a background pass last got all the way through, as epoch millis.
     *
     * Zero on every phone that updated into this version, which reads as "ще не
     * виконувалась" until the first pass lands. That is the honest answer: nothing
     * before this recorded a run, so claiming one would be inventing it.
     */
    fun lastRunAt(key: String): Long = prefs.getLong("run_$key", 0L)

    fun saveLastRunAt(key: String, atMillis: Long) = prefs.edit { putLong("run_$key", atMillis) }

    /**
     * Every pass and its last success, for the health panel.
     *
     * The weekly backup is deliberately not one of them. It does nothing at all
     * until a folder has been chosen, so on most phones it would sit in the panel
     * reading "ще не виконувалась" for ever — an alarm about a feature that was
     * never switched on. Its own freshness is on the backup row, where the folder
     * that explains it also is.
     */
    fun workRuns(): List<WorkRun> =
        listOf(WORK_PRICES, WORK_DIGEST).map { WorkRun(it, lastRunAt(it)) }

    /** The hour the daily digest is sent at. */
    fun digestHour(): Int =
        prefs.getInt("digest_h", DEFAULT_DIGEST_HOUR).coerceIn(0, 23)

    fun saveDigestHour(hour: Int) = prefs.edit { putInt("digest_h", hour.coerceIn(0, 23)) }

    /**
     * Ukraine's public holidays for one year, as epoch days.
     *
     * Empty whenever the cache is for another year or was never filled, and empty
     * is a working answer: the weekend rule stands on its own and the holidays
     * only sharpen it.
     */
    fun holidays(year: Int): Set<Long> = runCatching {
        // One key per year, so December can hold January's calendar as well. The
        // single "hol" key the app used before is still read for its own year.
        val raw = prefs.getString("hol_$year", null) ?: prefs.getString("hol", "{}") ?: "{}"
        val cached = JSONObject(raw)
        if (cached.optInt("y") != year) return emptySet()
        val days = cached.optJSONArray("d") ?: return emptySet()
        (0 until days.length()).map { days.getLong(it) }.toSet()
    }.getOrDefault(emptySet())

    /**
     * The holidays a charge in the coming weeks can fall on.
     *
     * In December that includes next year's: a charge due on Sunday the 3rd of
     * January moves back to Friday the 1st, which is New Year's Day, unless the
     * new year's calendar is already known.
     */
    fun holidaysAround(today: LocalDate): Set<Long> =
        holidays(today.year) + if (today.monthValue == 12) holidays(today.year + 1) else emptySet()

    fun saveHolidays(year: Int, days: Set<Long>) = prefs.edit {
        putString(
            "hol_$year",
            JSONObject()
                .put("y", year)
                .put("d", JSONArray().apply { days.sorted().forEach { put(it) } })
                .toString()
        )
    }

    /**
     * The last month whose recap was actually opened. Blank means none ever was.
     *
     * A preference rather than part of [exportJson], for the same reason the rate
     * target is: it says what this phone has already shown its owner, and
     * restoring a year-old file should not make five months of recaps queue up
     * again. Only one is ever waiting anyway — the month that has just ended.
     */
    fun recapSeen(): String = prefs.getString("recap", "").orEmpty()

    fun saveRecapSeen(month: String) = prefs.edit { putString("recap", month) }

    /**
     * The status pill the owner last waved away, or null when none stands.
     *
     * Two keys rather than one packed string, so neither half can be read back
     * without the other. Out of [exportJson] alongside the recap mark and the rate
     * target, and for the same reason: it says what this phone has already shown
     * its owner today, and a restore must not silence today's pill on the strength
     * of a swipe made a year ago.
     *
     * The rule this feeds, and why the day is stored at all, is written out over
     * [NoteDismissal].
     */
    fun dismissedNote(): NoteDismissal? {
        val key = prefs.getString("pill", "").orEmpty()
        if (key.isBlank()) return null
        return NoteDismissal(key, prefs.getLong("pill_day", 0L))
    }

    fun saveDismissedNote(dismissal: NoteDismissal?) = prefs.edit {
        if (dismissal == null) {
            // Cleared rather than blanked, for the reason given over the rate
            // target: two spellings of "nothing was dismissed" is one too many.
            remove("pill")
            remove("pill_day")
        } else {
            putString("pill", dismissal.key)
            putLong("pill_day", dismissal.day)
        }
    }

    /**
     * Whether a foldable section on the wish page is left open.
     *
     * One answer per section for the whole app rather than one per wish: the
     * choice being made is "do I want to see the price history when I open a
     * thing", and someone who has answered that once should not be asked again on
     * the next wish. It is also why this is not a field on [Wish] — nothing about
     * a pair of headphones says whether their owner likes charts.
     *
     * Out of [exportJson] for the same reason the rate target and the recap mark
     * are: it describes how this phone is being looked at rather than what is on
     * it, and restoring a year-old file should not reach across and refold the
     * screen.
     *
     * [key] is one of the ASCII constants beside [CollapsibleSection], so the
     * stored name never passes through a locale-sensitive case change.
     */
    fun sectionOpen(key: String): Boolean = prefs.getBoolean("sec_$key", false)

    fun saveSectionOpen(key: String, open: Boolean) =
        prefs.edit { putBoolean("sec_$key", open) }

    /** Which figure the Quick Settings tile is currently showing. */
    fun tileFace(): String = prefs.getString("tile", TILE_RATE) ?: TILE_RATE

    fun saveTileFace(face: String) = prefs.edit { putString("tile", face) }

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
     *
     * Monobank's sell rate only — [appendRate] turns the NBU's figure away. A
     * history written before that rule may still hold a day the NBU stood in, and
     * nothing on a point says which; the cap pushes those out within a month.
     */
    fun rateHistory(): List<PricePoint> =
        jsonList("fxh") { PricePoint(it.optDouble("p", 0.0), it.optLong("d", 0L)) }
            .filter { it.price > 0 }

    fun saveRateHistory(points: List<PricePoint>) = save("fxh", points.map {
        JSONObject().put("p", it.price).put("d", it.day)
    })

    /**
     * The rate the user asked to be told about, or null when none is set.
     *
     * A preference rather than part of [exportJson], alongside the income and the
     * digest hour. It is a standing instruction to this phone about what the rate
     * is doing now, and a year-old file restoring a threshold that was met last
     * March would announce a crossing that is no longer happening.
     */
    fun rateTarget(): RateTarget? {
        val value = prefs.getFloat("fxt", 0f).toDouble()
        if (value <= 0.0) return null
        return RateTarget(value, prefs.getBoolean("fxt_up", true), prefs.getLong("fxt_hit", 0L))
    }

    fun saveRateTarget(target: RateTarget?) = prefs.edit {
        if (target == null) {
            // Cleared rather than zeroed: a stored nought and an absent key would
            // both have to read as "no target", and two spellings of one state is
            // how the direction ends up remembered for a target nobody set.
            remove("fxt")
            remove("fxt_up")
            remove("fxt_hit")
        } else {
            putFloat("fxt", target.rate.toFloat())
            putBoolean("fxt_up", target.above)
            putLong("fxt_hit", target.hitDay)
        }
    }

    /**
     * Everything worth losing, as one file.
     *
     * Version 2 adds the paid record and the bin. The number is not read back on
     * import — an older file simply lacks those keys and an older build simply
     * ignores them — so it is here to say what a file is rather than to gate it.
     */
    fun exportJson(): String = JSONObject()
        .put("version", 2)
        .put("wishes", JSONArray(prefs.getString("w", "[]")))
        .put("payments", JSONArray(prefs.getString("pay", "[]")))
        .put("orders", JSONArray(prefs.getString("orders", "[]")))
        .put("paid", JSONArray(prefs.getString("paid", "[]")))
        .put("bin", JSONArray(prefs.getString("bin", "[]")))
        // The funds — money the owner says he put aside — are his data like the
        // wishes. «На життя» and the payday are preferences, like the income.
        .put("mpFunds", JSONArray(prefs.getString(PlanStore.FUNDS_KEY, "[]")))
        .toString(2)

    fun importJson(text: String) {
        val root = JSONObject(text)
        val wishes = root.getJSONArray("wishes")
        val payments = root.optJSONArray("payments") ?: JSONArray()
        val orders = root.optJSONArray("orders") ?: JSONArray()
        // A backup written before these existed carries no opinion about them, so
        // it leaves what is on the phone alone. Reading a missing key as an empty
        // list would let restoring a year-old file silently destroy a year of
        // payment records that the file never claimed to replace.
        val paid = root.optJSONArray("paid")
        val bin = root.optJSONArray("bin")
        val funds = root.optJSONArray("mpFunds")
        prefs.edit {
            putString("w", wishes.toString())
            putString("pay", payments.toString())
            putString("orders", orders.toString())
            paid?.let { putString("paid", it.toString()) }
            bin?.let { putString("bin", it.toString()) }
            funds?.let { putString(PlanStore.FUNDS_KEY, it.toString()) }
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

/**
 * The stored shape of each kind of item, written once.
 *
 * These were inline in [Store] until the bin needed them too. A second copy of the
 * mapping is how a restored wish comes back without its history: the two spellings
 * of "h" drift apart and nothing fails loudly enough to notice.
 */

fun wishJson(wish: Wish): JSONObject = JSONObject()
    .put("id", wish.id).put("n", wish.name).put("u", wish.url).put("i", wish.image)
    .put("p", wish.price).put("t", wish.targetPrice).put("c", wish.category)
    .put(
        "h",
        JSONArray().apply {
            wish.history.forEach { point ->
                put(
                    JSONObject().put("p", point.price).put("d", point.day)
                        .put("r", point.rate).put("rs", point.rateSource)
                )
            }
        }
    )
    .put("cd", wish.checkedDay)
    .put("s", wish.saved).put("m", wish.monthlyPlan).put("dl", wish.deadline)
    .put("np", wish.notifiedPrice).put("v", wish.variant)
    .put("fr", wish.freshness.name).put("ad", wish.addedDay).put("hu", wish.holdUntil).put("why", wish.why)
    // The duel record is the owner's own answers, so it travels with the wish.
    .put("dw", wish.duelWins).put("dp", wish.duelsPlayed)
    .put("jr", wish.jar)
    // «Пропустити»: the owner's own decision about a month, so it travels too.
    .put("mpsk", wish.skipMonth)
    .put("ab", aboutJson(wish.about))
    // Written exactly as held, empty included, so that what comes back out of the
    // bin is what went in. A wish that predates the list is not filled in here:
    // [wishSources] is what turns its single address into the one source it always
    // was, and doing it there means one rule rather than two that can drift.
    .put(
        "src",
        JSONArray().apply { wish.sources.forEach { put(sourceJson(it)) } }
    )
    // Written even when empty, like the array above, so that what comes back out
    // of the bin is exactly what went in.
    .put("sq", wish.searchQuery)
    // Unlike the two above this one is genuinely absent rather than empty on a
    // wish nobody has asked about, and the difference is load-bearing: absent
    // means the section offers to write one, and an empty object would mean a
    // model was asked and said nothing. So the key is written only when there is
    // an answer, and [wishOf] reads its absence back as null.
    .let { if (wish.appraisal != null) it.put("ap", appraisalJson(wish.appraisal)) else it }
    // Set-aside glitches, points said to be real, sold-out spans (PricesMore.kt).
    // The owner's own answers and part of the history, so they travel with it.
    .put("wpx", setAsideJson(wish.excluded))
    .put("wpr", pointsJson(wish.realPoints))
    .put("wpg", gapsJson(wish.stockGaps))
    // The bound Hotline market, only when there is one, like "ap" above.
    .let { if (wish.market != null) it.put("wpmk", marketJson(wish.market)) else it }

/**
 * Which shape a stored review is written in.
 *
 * One, the first version, held `k`/`w`/`q` — a line, a list of things to weigh, and
 * a scrap of the shop's own blurb — written by a model that had not searched for
 * anything and was forbidden to state a figure. Two is a review with sources.
 *
 * The marker exists so that [appraisalOf] can read a version-one object and return
 * nothing rather than salvage it. Salvage would be worse than loss: its sentences
 * were written under rules that no longer apply, the section would have to present
 * them as ungrounded, and the honest state for a wish whose only review predates
 * the whole feature is "nobody has asked yet" — which offers to ask.
 */
const val APPRAISAL_FORMAT = 2

/**
 * A model's review, stored beside the wish.
 *
 * A nested object for the same reason [aboutJson] is one: the bin's restore and the
 * backup file carry it whole or not at all, and a half-restored review — the prose
 * without the sources it was grounded on, or without the price it was written for —
 * would be a judgement nobody could check against anything, which is the exact
 * failure this feature is built around avoiding.
 */
fun appraisalJson(appraisal: Appraisal): JSONObject = JSONObject()
    .put("v", APPRAISAL_FORMAT)
    .put("k", appraisal.kind)
    .put("g", JSONArray().apply { appraisal.good.forEach { put(it) } })
    .put("b", JSONArray().apply { appraisal.weak.forEach { put(it) } })
    .put("s", appraisal.suits)
    .put("x", appraisal.skip)
    .put("c", JSONArray().apply { appraisal.check.forEach { put(it) } })
    .put(
        "src",
        JSONArray().apply {
            appraisal.sources.forEach { put(JSONObject().put("t", it.title).put("u", it.url)) }
        }
    )
    .put("q", JSONArray().apply { appraisal.queries.forEach { put(it) } })
    .put("f", appraisal.found)
    .put("p", appraisal.price)
    .put("d", appraisal.day)
    .put("m", appraisal.model)

/** Every non-blank string in a stored array, in order. */
private fun textsOf(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
}

fun appraisalOf(o: JSONObject?): Appraisal? {
    if (o == null) return null
    // A review written under the old rules is not converted, it is dropped. See
    // [APPRAISAL_FORMAT].
    if (o.optInt("v") != APPRAISAL_FORMAT) return null
    val stored = o.optJSONArray("src") ?: JSONArray()
    val appraisal = Appraisal(
        kind = o.optString("k"),
        good = textsOf(o.optJSONArray("g")),
        weak = textsOf(o.optJSONArray("b")),
        suits = o.optString("s"),
        skip = o.optString("x"),
        check = textsOf(o.optJSONArray("c")),
        sources = (0 until stored.length())
            .mapNotNull { stored.optJSONObject(it) }
            .map { AppraisalSource(it.optString("t"), it.optString("u")) }
            .filter { it.url.isNotBlank() },
        queries = textsOf(o.optJSONArray("q")),
        // Absent reads as true, which is the shape of every review that actually
        // found the thing — the flag is only ever written false deliberately.
        found = o.optBoolean("f", true),
        price = o.optDouble("p", 0.0),
        day = o.optLong("d", 0L),
        model = o.optString("m")
    )
    // A stored object with nothing in it is not a review, and reading it back as
    // one would put an empty paragraph under a heading that promises words.
    return appraisal.takeIf { !it.isEmpty }
}

/**
 * One shop, stored.
 *
 * A nested object rather than parallel arrays of urls and prices: a wish whose
 * shops and prices got out of step by one would compare Rozetka's price under
 * Comfy's name, and nothing on screen would look wrong.
 */
fun sourceJson(source: WishSource): JSONObject = JSONObject()
    .put("u", source.url).put("p", source.price).put("v", source.variant)
    .put("fr", source.freshness.name).put("cd", source.checkedDay)
    .put("a", source.amount).put("cur", source.currency).put("r", source.rate)
    // By name, like the freshness beside it, so that the stored file stays readable
    // and adding a value later cannot silently renumber the ones already written.
    .put("av", source.availability.name)
    .put("lp", source.listPrice)
    // The card member's price and its programme (PricesMore.kt).
    .put("wpm", source.memberPrice).put("wpt", source.memberTier)

fun sourceOf(o: JSONObject): WishSource = WishSource(
    url = o.optString("u"),
    price = o.optDouble("p", 0.0),
    variant = o.optString("v"),
    freshness = freshnessFrom(o.optString("fr")),
    checkedDay = o.optLong("cd", 0L),
    amount = o.optDouble("a", 0.0),
    // A price stored before currencies existed is hryvnia, which is what every
    // shop the app could read at the time was pricing in.
    currency = o.optString("cur", UAH).ifBlank { UAH },
    rate = o.optDouble("r", 0.0),
    // A source stored before availability was read declared nothing as far as this
    // app is concerned, which is exactly what [Availability.UNKNOWN] means, so old
    // data reads back behaving precisely as it did.
    availability = availabilityStored(o.optString("av")),
    // Absent on everything read before the crossed-out price was: nothing declared.
    listPrice = o.optDouble("lp", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
    // Absent on everything read before member prices were: none stated.
    memberPrice = o.optDouble("wpm", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
    memberTier = o.optString("wpt")
)

/**
 * The page's own words, stored beside the wish.
 *
 * A nested object rather than five more top-level keys, so the bin's restore and
 * the backup file carry it whole or not at all — a half-restored description is
 * harder to notice than a missing one.
 */
fun aboutJson(about: ProductAbout): JSONObject = JSONObject()
    .put("d", about.description).put("b", about.brand)
    .put("r", about.rating).put("rc", about.ratingCount)
    .put(
        "s",
        JSONArray().apply {
            about.specs.forEach { (name, value) ->
                put(JSONObject().put("n", name).put("v", value))
            }
        }
    )

fun aboutOf(o: JSONObject?): ProductAbout {
    if (o == null) return ProductAbout()
    val specs = o.optJSONArray("s") ?: JSONArray()
    return ProductAbout(
        description = o.optString("d"),
        brand = o.optString("b"),
        rating = o.optDouble("r", 0.0),
        ratingCount = o.optInt("rc", 0),
        specs = (0 until specs.length()).mapNotNull { index ->
            specs.optJSONObject(index)?.let { it.optString("n") to it.optString("v") }
        }.filter { it.first.isNotBlank() && it.second.isNotBlank() }
    )
}

fun wishOf(o: JSONObject): Wish {
    val recorded = o.optJSONArray("h") ?: JSONArray()
    val stored = o.optJSONArray("src") ?: JSONArray()
    return Wish(
        id = o.optString("id", System.currentTimeMillis().toString()),
        name = cleanProductTitle(o.optString("n", "Товар")).ifBlank { "Товар" },
        url = o.optString("u"),
        image = o.optString("i"),
        price = o.optDouble("p", 0.0),
        targetPrice = o.optDouble("t", 0.0),
        category = o.optString("c", "Інше"),
        // Histories written before dates existed are bare numbers. They are read
        // as points with an unknown day rather than being thrown away, and the
        // same goes for points written before the rate travelled with them.
        history = (0 until recorded.length()).mapNotNull { index ->
            recorded.optJSONObject(index)?.let { point ->
                PricePoint(
                    point.optDouble("p", 0.0),
                    point.optLong("d", 0L),
                    point.optDouble("r", 0.0),
                    point.optString("rs")
                )
            } ?: recorded.optDouble(index, 0.0).takeIf { it > 0 }?.let { PricePoint(it, 0L) }
        }.filter { it.price > 0 },
        checkedDay = o.optLong("cd", 0L),
        notifiedPrice = o.optDouble("np", 0.0),
        variant = o.optString("v"),
        saved = o.optDouble("s", 0.0),
        monthlyPlan = o.optDouble("m", 0.0),
        deadline = o.optLong("dl", 0L),
        // Anything saved before freshness existed was last seen being read from a
        // page, so that is the honest default rather than a fresh doubt.
        freshness = freshnessFrom(o.optString("fr")),
        addedDay = o.optLong("ad", 0L),
        holdUntil = o.optLong("hu", 0L),
        // Absent on every wish saved before the reason could be written.
        why = o.optString("why"),
        // Absent on every wish saved before the duel existed: never dueled.
        duelWins = o.optInt("dw", 0).coerceAtLeast(0),
        duelsPlayed = o.optInt("dp", 0).coerceAtLeast(0),
        // Absent on every wish not tied to a monobank jar.
        jar = o.optString("jr"),
        // Absent on every wish whose plan was never skipped.
        skipMonth = o.optString("mpsk"),
        about = aboutOf(o.optJSONObject("ab")),
        // A wish saved with only `u` has no array here at all, and stays empty
        // rather than being filled in on the way past: [wishSources] is the one
        // place that turns that single address into the one source it always was.
        sources = (0 until stored.length()).mapNotNull { index ->
            stored.optJSONObject(index)?.let(::sourceOf)
        }.filter { it.url.isNotBlank() },
        // Absent on every wish saved before the search existed, which reads back as
        // empty — and empty is precisely right for them: nobody has corrected the
        // query for a wish that never had one.
        searchQuery = o.optString("sq"),
        // Absent on every wish saved before this existed and on every wish nobody
        // has asked about, both of which are the same thing: no model has written
        // about it, so the section offers to.
        appraisal = appraisalOf(o.optJSONObject("ap")),
        // Absent on everything saved before 4 October's second pass: nothing set
        // aside, nothing confirmed, no sold-out span recorded.
        excluded = setAsideOf(o.optJSONArray("wpx")),
        realPoints = pointsOf(o.optJSONArray("wpr")),
        stockGaps = gapsOf(o.optJSONArray("wpg")),
        // Absent on every wish no Hotline page was bound to.
        market = marketOf(o.optJSONObject("wpmk"))
    )
}

/**
 * An expense's amount history, written whole.
 *
 * The rate a wish records beside each price has no meaning here: an expense is
 * billed in one currency and stays in it, so only the figure and the day it took
 * effect are stored. Reading tolerates anything that is not an object, because a
 * half-written array must come back as a shorter history rather than as a crash
 * that costs the whole expense.
 */
fun amountsJson(points: List<PricePoint>): JSONArray = JSONArray().apply {
    points.forEach { point -> put(JSONObject().put("a", point.price).put("d", point.day)) }
}

fun amountsOf(array: JSONArray?): List<PricePoint> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { PricePoint(it.optDouble("a", 0.0), it.optLong("d", 0L)) }
    }.filter { it.price > 0.0 }
}

fun payJson(pay: Pay): JSONObject = JSONObject()
    .put("n", pay.name).put("a", pay.amount).put("d", pay.day)
    .put("cur", pay.currency).put("wd", pay.warnDays)
    // All three have to travel, or the bin restores a subscription that has
    // forgotten it was ever cheaper, a backup imports a trial as a live charge,
    // and an annual domain fee comes back as a monthly one twelve times the size.
    .put("am", amountsJson(pay.amounts)).put("te", pay.trialEnd)
    .put("bm", pay.billingMonth)
    // A choice somebody made by hand, so it travels through the bin and the backup.
    .put("em", pay.emoji)
    // A plan forgotten here would come back from the bin as a payment for ever.
    .put("ic", pay.instalments).put("is", pay.instalmentStart)
    // The owner's own «так», so a restore does not ask him again.
    .put("mm", pay.monoMerchant)
    // A payment's life after it starts — PaymentsLife.kt. A stop or a pause lost
    // here would come back from the bin or a backup charging again, and a promo
    // price lost would read the discount as the full price.
    .put("plsa", pay.stopsAfter).put("plsr", pay.stopReason)
    .put("plpf", pay.pausedFrom).put("plps", pausesJson(pay.pauses))
    .put("plpp", pay.promoPrice).put("plcu", pay.cancelUrl).put("plo", pay.order)

fun payOf(o: JSONObject): Pay = Pay(
    o.optString("n"),
    // A default, so an entry missing its amount reads as nought rather than NaN,
    // which would quietly poison every total it was summed into.
    o.optDouble("a", 0.0).takeIf { it.isFinite() } ?: 0.0,
    o.optInt("d", 1),
    // Entries saved before currencies existed were all hryvnia.
    o.optString("cur", UAH).ifBlank { UAH },
    // Entries saved before the warning existed got a day's notice from the worker
    // itself, so that is what they keep.
    o.optInt("wd", DEFAULT_WARN_DAYS),
    // An expense saved before any of this comes back with no history at all, which
    // is the truth: nothing was watching what it used to cost.
    amountsOf(o.optJSONArray("am")),
    // Nought is no trial, which is what every expense on the phone already is.
    o.optLong("te", 0L),
    // Nought is "every month", which is what every expense saved before this
    // genuinely was — there was no other rhythm to save. Anything outside 1..12
    // is read the same way rather than trusted, since a month of 13 would put a
    // charge on a date [LocalDate] refuses to build.
    o.optInt("bm", 0).takeIf { it in 1..MONTHS_IN_YEAR } ?: 0,
    // Absent on everything saved before emoji existed: guessed from the name.
    emoji = o.optString("em"),
    // Absent on everything saved before plans existed: an ordinary expense.
    instalments = o.optInt("ic", 0).coerceAtLeast(0),
    instalmentStart = o.optLong("is", 0L).coerceAtLeast(0L),
    // Absent until a monobank charge was confirmed for it.
    monoMerchant = o.optString("mm"),
    // Absent on everything saved before a payment could stop, pause or run at a
    // promo price: it runs, as everything did.
    stopsAfter = o.optLong("plsa", 0L).coerceAtLeast(0L),
    stopReason = o.optString("plsr"),
    pausedFrom = o.optLong("plpf", 0L).coerceAtLeast(0L),
    pauses = pausesOf(o.optJSONArray("plps")),
    promoPrice = o.optDouble("plpp", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
    cancelUrl = o.optString("plcu"),
    order = o.optString("plo")
)

fun orderJson(order: Order): JSONObject = JSONObject()
    .put("id", order.id).put("n", order.name).put("u", order.url)
    .put("s", order.status).put("t", order.tracking).put("i", order.image)
    .put("p", order.price).put("sd", order.statusDetail).put("ca", order.checkedAt)
    .put("pr", order.problem).put("sc", order.statusCode).put("ps", order.paidStorageFrom)
    .put("sdl", order.scheduledDelivery).put("atp", order.amountToPay)
    // The record of the purchase itself travels with the parcel, which is what
    // lets a binned purchase come back still knowing what it cost and how the
    // wait turned out. Left out here, a restore would return an empty parcel and
    // nothing on screen would say the verdict had been thrown away.
    .put("pd", order.paid).put("lw", order.lowestSeen)
    .put("us", order.uses).put("ar", order.archivedDay)
    // Both halves of each of these are in Tracking.kt, next to each other. The
    // observation log especially: it is the only thing on a parcel that no
    // refetch can rebuild, so a backup that dropped it would quietly throw away
    // the whole movement history of everything in flight.
    .put("dt", detailsJson(order.details))
    .put("sg", sightingsJson(order.sightings))
    .put("dg", order.digital)
    .put("rb", order.returnBy)
    .put("em", order.emoji)
    // Parcels and purchases, second round: the other half is in orderOf, both
    // round-trip tested in ParcelsMoreTest. The recipient's number travels with its
    // parcel; the owner's own number is a preference and is in no backup at all.
    .put("pkPh", order.recipientPhone)
    .apply { order.refund?.let { put("pkRet", refundJson(it)) } }
    .put("pkWu", order.warrantyUntil)
    .put("pkJoy", order.delight).put("pkAgain", order.again).put("pkJoyDay", order.delightDay)
    .put("pkWhy", order.why).put("pkCat", order.category)
    // The plan that paid for it was closed because it went back — PaymentsLife.kt.
    .put("plr", order.planReturned)

/**
 * A parcel read back off the phone.
 *
 * The name goes through [cleanProductTitle] on the way out, exactly as a wish's
 * does in [wishOf], and that is a repair rather than a tidy. Tidying happens at
 * parse time, and a title scraped before the entity scanner was fixed was stored
 * with its raw "&#039;" still in it — so «Модуль пам&#039;яті» would have sat on
 * the screen until something happened to fetch that page again, which for a
 * purchase is never: a parcel's name is written once, when it is added, and the
 * shop page is not read a second time.
 *
 * Safe to apply on every read because the scanner makes one left-to-right pass and
 * never looks at its own output, so a name with no references left in it comes back
 * the identical string. The one case where a second pass is not the same as a first
 * is a page that double-escaped itself — "&amp;#39;" is stored as the literal text
 * "&#39;" on purpose, and reading it again turns it into an apostrophe. That is
 * pinned in EntitiesTest as the boundary it is, and it is the same bargain [wishOf]
 * has been making since it shipped: a title that genuinely wants to show a
 * character reference is hypothetical, and one carrying a stale one is on this
 * phone right now.
 */
fun orderOf(o: JSONObject): Order = Order(
    o.optString("id"), cleanProductTitle(o.optString("n")), o.optString("u"),
    o.optString("s", ORDERED), o.optString("t"),
    o.optString("i"), o.optDouble("p", 0.0),
    o.optString("sd"), o.optLong("ca", 0L),
    o.optBoolean("pr", false), o.optLong("ps", 0L),
    o.optLong("sdl", 0L), o.optDouble("atp", 0.0),
    // Parcels saved before a purchase could be closed have none of this. Zero
    // throughout reads as "not recorded", which is exactly what it is, so an old
    // backup imports into an open parcel rather than one that claims to have been
    // bought for nothing.
    paid = o.optDouble("pd", 0.0),
    lowestSeen = o.optDouble("lw", 0.0),
    uses = o.optInt("us", 0),
    archivedDay = o.optLong("ar", 0L),
    // Absent on every parcel saved before the detail screen existed. An empty
    // record reads as "nothing has been fetched yet", which is exactly true of
    // those, and the screen says so rather than drawing an empty journey.
    details = detailsOf(o.optJSONObject("dt")),
    sightings = sightingsOf(o.optJSONArray("sg")),
    // Zero on every parcel saved before the code was kept. Those still carry the
    // `pr` flag, so a restored parcel in trouble says the old general sentence
    // rather than nothing, and says the specific one again after the next check.
    statusCode = o.optInt("sc", 0),
    // Absent on every purchase saved before the app knew a download from a parcel.
    // Those are judged by their shop, which is what puts the Steam game already on
    // the phone back where it belongs without anyone having to touch it. Once the
    // flag has been written, it is the answer — including a «no» set by hand.
    digital = if (o.has("dg")) o.optBoolean("dg", false) else isDigitalStore(o.optString("u")),
    // Absent on everything filed before return windows were kept: none tracked.
    returnBy = o.optLong("rb", 0L),
    // Absent on everything saved before emoji existed: guessed from the name.
    emoji = o.optString("em"),
    // Absent on everything saved before 4 October 2026: no number of its own, no
    // return, no warranty, no answer — which is exactly what those purchases have.
    recipientPhone = o.optString("pkPh"),
    refund = refundOf(o.optJSONObject("pkRet")),
    warrantyUntil = o.optLong("pkWu", 0L),
    delight = o.optInt("pkJoy", 0).takeIf { it in 0..4 } ?: 0,
    again = o.optInt("pkAgain", 0).coerceIn(-1, 1),
    delightDay = o.optLong("pkJoyDay", 0L),
    why = o.optString("pkWhy"),
    category = o.optString("pkCat"),
    // Absent on every purchase no plan «частинами» was closed for.
    planReturned = o.optLong("plr", 0L).coerceAtLeast(0L)
)

/** A wish on its way to the bin, with enough on the row to recognise it by. */
fun binEntryOf(wish: Wish, today: Long): BinEntry = BinEntry(
    id = binEntryId(BIN_WISH, wish.id),
    kind = BIN_WISH,
    title = wish.name,
    // The history is the part that cannot be fetched back, so the row says how
    // much of it is at stake rather than restating the price.
    detail = listOfNotNull(
        money(wish.price).takeIf { wish.price > 0 },
        changesLabel(wish.history.size).takeIf { wish.history.isNotEmpty() }
    ).joinToString(" · "),
    payload = wishJson(wish).toString(),
    day = today
)

fun binEntryOf(pay: Pay, today: Long): BinEntry = BinEntry(
    // A Pay has no id of its own, so the bin gives it one. Name and day alone
    // would collide with a second copy of the same expense deleted later.
    id = "pay-$today-${System.nanoTime()}",
    kind = BIN_PAY,
    title = pay.name,
    detail = "${amountLabel(pay.amount, pay.currency)} · ${rhythmNote(pay)}",
    payload = payJson(pay).toString(),
    day = today
)

fun binEntryOf(order: Order, today: Long): BinEntry = BinEntry(
    id = binEntryId(BIN_ORDER, order.id),
    kind = BIN_ORDER,
    title = order.name,
    detail = listOfNotNull(
        order.status.takeIf { it.isNotBlank() },
        order.tracking.takeIf { it.isNotBlank() }
    ).joinToString(" · "),
    payload = orderJson(order).toString(),
    day = today
)

fun isSupportedWebUrl(value: String): Boolean = runCatching {
    val url = URL(value.trim())
    url.protocol in setOf("http", "https") && url.host.isNotBlank()
}.getOrDefault(false)

fun refreshedWish(
    previous: Wish,
    current: Wish,
    today: Long = LocalDate.now().toEpochDay(),
    rate: FxRate = FxRate()
): Wish =
    previous.copy(
        image = current.image.ifBlank { previous.image },
        price = current.price,
        // Only a figure the page offered for sale may extend the history.
        // [wishFromOffer] marks a sold-out placeholder, and a price it could not
        // convert, with their own freshness — and this used to append them anyway
        // and call the wish in stock, which is how sharing a sold-out page put a
        // placeholder in as the all-time low.
        history = if (current.price > 0.0 && current.freshness == Freshness.OK) {
            appendPrice(previous.history, current.price, today, rate.sell, rate.source)
        } else {
            previous.history
        },
        checkedDay = today,
        freshness = when {
            current.price > 0.0 && current.freshness != Freshness.OK -> current.freshness
            current.price > 0.0 -> Freshness.OK
            else -> previous.freshness
        },
        // What the page says about the thing is most of what a wish with no price
        // has to show, and this is the one path a shared link takes into the list.
        // Without it the description read from the page was dropped on the floor
        // exactly where it was needed most.
        about = if (current.about.isEmpty) previous.about else current.about,
        // The freshly read page knows what money it was priced in and what that
        // converted to; the placeholder this is filling in knows only the address.
        // Anything the new reading did not bring keeps what was there before.
        sources = wishSources(current).ifEmpty { wishSources(previous) }
    )

/**
 * Re-reads a page and follows the variant this wish was set to.
 *
 * The interesting cases are the two that are not a price. A page that lost the
 * followed variant, and a page that lost its prices altogether, must both leave the
 * stored figure alone rather than swap in a neighbouring edition — but they are no
 * longer silent about it, because a stored figure that nobody can buy at is exactly
 * what used to sit on the card looking current. The wish comes back carrying which
 * of the two happened; only [Reading.Priced] touches the price or the history.
 */
fun readWish(
    previous: Wish,
    html: String,
    today: Long = LocalDate.now().toEpochDay(),
    rate: FxRate = FxRate()
): Reading {
    val sources = wishSources(previous)
    if (sources.isEmpty()) return Reading.Failed
    // Everything but the first shop is left exactly as it was, which is what a
    // page that was never fetched deserves. One page can only answer for itself.
    val readings = sources.mapIndexed { index, source ->
        if (index == 0) readSource(source, html, today, rate) else SourceReading.Failed
    }
    return mergeSources(previous, readings, today, rate)
}

/**
 * The shop answered that the page is not there.
 *
 * Distinct from a failed connection on purpose: a 404 is a fact about the item
 * worth showing on its card, whereas a timeout is a fact about the phone and must
 * leave every card exactly as it was.
 */
class PageGone(val code: Int) : java.io.IOException("Сторінка більше не відповідає ($code)")

/** Fetches a shop page. Separate from parsing it, so both uses share one request. */
suspend fun pageHtml(link: String): String = withContext(Dispatchers.IO) {
    val normalizedLink = link.trim()
    require(isSupportedWebUrl(normalizedLink)) { "Вкажіть коректне HTTP або HTTPS посилання" }
    val connection = URL(normalizedLink).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
    // Checked rather than left to the stream, which throws the same
    // FileNotFoundException for a 404 as for several failures that say nothing
    // about the item. The card is allowed to claim the page is gone only here.
    val code = connection.responseCode
    if (code == 404 || code == 410) throw PageGone(code)
    connection.inputStream.bufferedReader().use { it.readText() }
}

/**
 * How long to wait before asking a silent page a second time.
 *
 * Long enough that the shop is answering a fresh request rather than the same one
 * again, short enough that a person holding the phone still reads it as the app
 * working rather than as the app hanging.
 */
const val RETRY_PAUSE_MS = 1_500L

/**
 * Fetches a page, and fetches it once more when the first answer carried no price.
 *
 * Not a workaround for one shop. The same address, seconds apart, genuinely
 * answers differently: a page comes back half its usual size with its price
 * stripped out, and the next request returns the whole thing. Nothing in the
 * response says which kind arrived, so the only way to tell is to look for a
 * price and ask again when there is not one.
 *
 * Exactly one retry, and only on the path where a person is waiting for an
 * answer. The twice-daily pass deliberately does not use this: there it would
 * double the traffic at every shop on the list to rescue a reading that will be
 * attempted again in twelve hours anyway.
 */
suspend fun pricedPageHtml(link: String): String {
    val first = pageHtml(link)
    if (extractOffers(first).isNotEmpty()) return first
    delay(RETRY_PAUSE_MS)
    // A second fetch that fails outright changes nothing: the first answer is
    // still the best account of the page there is, and it is the one whose title
    // and photograph the add sheet is about to offer.
    val second = runCatching { pageHtml(link) }.getOrNull() ?: return first
    return if (extractOffers(second).isNotEmpty()) second else first
}

/**
 * Re-reads a wish's page and follows the variant it was set to.
 *
 * Never throws. Every outcome the fetch can tell apart is a [Reading], including
 * the two that leave the price alone: silently adopting a neighbouring variant's
 * price would corrupt the history a verdict is computed from, and nothing on screen
 * would show it had happened. A page that answers 404 is the item's own news and
 * reaches the card; anything else that fails is the network's, and changes nothing.
 */
suspend fun refreshed(previous: Wish, today: Long, rate: FxRate): Reading {
    val sources = wishSources(previous)
    // One at a time rather than in parallel: the pass already walks the whole
    // wishlist this way, and three shops answering at once is a burst of requests
    // at one shop's neighbours for no gain a background job can feel.
    val readings = sources.map { source ->
        val html = try {
            pageHtml(source.url)
        } catch (gone: PageGone) {
            // This shop dropped the page. That is news about this shop and nothing
            // at all about the others, which is why it lands on the row.
            return@map SourceReading.Stale(
                source.copy(checkedDay = today, freshness = Freshness.GONE)
            )
        } catch (failure: Exception) {
            return@map SourceReading.Failed
        }
        readSource(source, html, today, rate)
    }
    return mergeSources(previous, readings, today, rate)
}

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
 *
 * Callers go through [refreshUsdRate], which decides what of the answer is kept:
 * the official figure never replaces a bank reading taken minutes before, and never
 * reaches the rate chart or the threshold.
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

/**
 * Asks for the rate and keeps what is worth keeping: the figure on the phone, and
 * the day's point on the chart.
 *
 * One function for the currency screen and the background pass. The two used to
 * do this each on their own, and both saved [usdRate]'s NBU fallback over a
 * Monobank reading taken a minute earlier — a second tap on refresh was enough —
 * so the bank's sell rate gave way to the official one on the screen, in every
 * dollar conversion, on the chart and in the threshold. [rateToKeep] decides what
 * the phone holds and [appendRate] what the chart records; this only asks and saves.
 *
 * What is held is read after the answer arrives rather than before the question,
 * so a reading the other caller saved meanwhile is what the decision compares with.
 *
 * Returns the reading now held, or null when nothing new was kept: neither source
 * answered, or only the NBU did while a recent bank reading was already here.
 */
suspend fun refreshUsdRate(store: Store): FxRate? {
    val fresh = usdRate()
    val (held, heldAt) = store.fxRate()
    val now = System.currentTimeMillis()
    val kept = rateToKeep(held, heldAt, fresh, now) ?: return null
    store.saveFxRate(kept, now)
    store.saveRateHistory(appendRate(store.rateHistory(), kept, LocalDate.now().toEpochDay()))
    return kept
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
 *
 * [phone] is the recipient's or the sender's number, 380XXXXXXXXX, or empty. With
 * it Nova Poshta fills in the cash on delivery, the delivery cost, paid storage and
 * the sender; without it, it warns and leaves them out. It goes into this request
 * body and nowhere else — never into a log, a URL or anything stored with it.
 */
suspend fun parcelStatus(number: String, phone: String = ""): ParcelStatus? = withContext(Dispatchers.IO) {
    val clean = number.filter { !it.isWhitespace() }
    if (detectCarrier(clean) != CARRIER_NOVA_POSHTA) return@withContext null
    val body = trackingRequest(clean, phone).toString()
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
    // Exported, and on the application context. The completion broadcast comes from
    // the system's download provider, which runs as its own app — a receiver marked
    // not-exported on Android 14+ is not guaranteed to hear it, and one tied to the
    // activity is lost if the activity goes while the APK downloads. The receiver
    // only acts on this one download id, so a forged broadcast can at most open
    // the installer on a file this app downloaded itself.
    ContextCompat.registerReceiver(
        context.applicationContext, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
        ContextCompat.RECEIVER_EXPORTED
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
    // Held here rather than inside the purchases screen, for the same reason the
    // wish page is: the action button and the status pill both have to know that
    // an item page is open, and neither of them lives down there.
    var openedOrder by remember { mutableStateOf<String?>(null) }
    // A tracking number that arrived through the share sheet, waiting for the
    // purchases tab to open its add form with it.
    var sharedTracking by remember { mutableStateOf<String?>(null) }
    // A letter about a subscription from the share sheet, waiting for Платежі to
    // open «Новий платіж» filled in, or to offer the new price (SubscriptionText.kt).
    var sharedLetter by remember { mutableStateOf<SharedLetter?>(null) }
    // A Hotline product page that arrived through the share sheet, waiting for the
    // owner to say which wish it is the market for. PricesMore.kt.
    var marketShare by remember { mutableStateOf<String?>(null) }
    // Both read pruned: a month that fell out of the year, or an entry past its
    // thirty days, is dropped on the way out of the store rather than lingering
    // in memory until something happens to write the list back.
    var paid by remember { mutableStateOf(store.paidMarks()) }
    var bin by remember { mutableStateOf(store.bin()) }
    // «На життя», the payday, the funds and the payday ritual — MoneyPlan.kt.
    val planStore = remember { PlanStore(context) }
    var funds by remember { mutableStateOf(planStore.funds()) }
    var planSettings by remember { mutableStateOf(planStore.settings()) }
    var ritualRecord by remember { mutableStateOf(planStore.ritual()) }
    var declinedFunds by remember { mutableStateOf(planStore.declinedFunds()) }
    // Bumped when the income is edited from Огляд, so the figure is read again.
    var incomeEdits by remember { mutableIntStateOf(0) }

    // Both belong to whichever tab is showing, so leaving a tab clears them.
    LaunchedEffect(tab) {
        adding = false
        openedWish = null
        openedOrder = null
    }

    val notices = remember { SnackbarHostState() }
    val noticeScope = rememberCoroutineScope()
    // One line at a time: a share is a sequence of two or three of these, and
    // queued snackbars would still be reporting the fetch after it finished.
    fun say(text: String) = noticeScope.launch {
        notices.currentSnackbarData?.dismiss()
        // «Фонд покрив …», «Записано: відкладено …»: with the eye on Огляд shut, a
        // snackbar hides its sums like the screen under it (Privacy.kt).
        notices.showSnackbar(personalNow(text))
    }

    // Declared after the effect above so that when a command arrives on another
    // tab, the tab switch clears that tab's dialogs first and this runs second.
    LaunchedEffect(command, tab) {
        if (command == null) return@LaunchedEffect
        // A tapped notification belongs to whichever tab it was about, not to the
        // wishlist, so it is the one command that does not go through the switch.
        if (command is AppCommand.OpenTab) {
            onCommandHandled()
            tab = command.tab
            return@LaunchedEffect
        }
        // Every command belongs to the wishlist. Switching costs a pass through
        // this effect, which is why it returns and waits for the new tab.
        if (tab != TAB_WISHES) {
            tab = TAB_WISHES
            return@LaunchedEffect
        }
        // Handled once, here, and the slow part moved out of this effect. The effect
        // is keyed on the tab too, so a tap on Платежі while a shared page was still
        // being read used to cancel the read and start it over — which found its own
        // placeholder already in the list and left it without a name for good.
        onCommandHandled()
        when (command) {
            is AppCommand.AddWish -> adding = true
            is AppCommand.OpenTab -> Unit
            is AppCommand.RefreshPrices -> noticeScope.launch {
                if (wishes.isEmpty()) {
                    say(refreshMessage(0, 0))
                } else {
                    say("Перевіряю ціни…")
                    val day = LocalDate.now().toEpochDay()
                    val rate = store.fxRate().first
                    // Readings are matched to the list they were taken from, then
                    // folded by id into the list as it is when they arrive — see
                    // Merge.kt. Matching by position after the fact brought deleted
                    // wishes back and dropped the last one off the end.
                    val before = wishes
                    val fetched = before.map { refreshed(it, day, rate) }
                    val result = applyFollowed(before, fetched)
                    val merged = mergeById(wishes, before, result.wishes) { it.id }
                    wishes = merged
                    store.saveWishes(merged)
                    say(refreshMessage(result.updated, merged.size))
                    staleMessage(staleCount(merged))?.let { say(it) }
                }
            }
            is AppCommand.AddShared -> noticeScope.launch {
                // A letter about a subscription, asked first: such a letter nearly
                // always carries a link, and an order number in it can look like a
                // waybill. It has to use a subscription's own words — SubscriptionText.kt.
                subscriptionLetter(command.text, pays, LocalDate.now())?.let { letter ->
                    if (letter is SharedLetter.SamePrice) {
                        say("«${letter.pay.name}» уже є в платежах — ціна та сама")
                    } else {
                        sharedLetter = letter
                    }
                    tab = TAB_PAYMENTS
                    return@launch
                }
                // A Nova Poshta waybill anywhere in the words of the message makes it a
                // parcel, link or no link: an SMS from a shop carries the order's link
                // beside the number, and the link used to win. See [sharedParcelNumber];
                // a waybill already on the list opens its parcel.
                sharedParcelNumber(command.text)?.let { number ->
                    sharedTracking = number
                    tab = TAB_ORDERS
                    return@launch
                }
                when (val link = sharedLink(command.text, wishes)) {
                // No link, but a parcel number — an SMS or a Viber message from the
                // carrier. That is a purchase on its way, so it goes to Покупки
                // with the number already typed.
                SharedLink.Missing -> trackingNumberIn(command.text)?.let { number ->
                    sharedTracking = number
                    tab = TAB_ORDERS
                } ?: say("У повідомленні немає ні посилання, ні трек-номера")
                // Already watched: its page and its chart, not a line saying so —
                // see [wishToOpen].
                is SharedLink.Known -> {
                    openedWish = wishToOpen(link)
                    say(knownShareNote(link.wish))
                }
                is SharedLink.New -> {
                    // A Hotline product page is a market for a wish, not a wish:
                    // it asks which one to bind it to — see [hotlineProductUrl].
                    val market = hotlineProductUrl(link.url)
                    if (market != null) {
                        marketShare = market
                        return@launch
                    }
                    // The link is saved before the page is read, so a shop that
                    // blocks the fetch costs a name and a price, never the item.
                    val id = System.currentTimeMillis().toString()
                    val day = LocalDate.now().toEpochDay()
                    val rate = store.fxRate().first
                    val saved = wishes + placeholderWish(link.url, id, day)
                    wishes = saved
                    store.saveWishes(saved)
                    say("Додано до бажань, шукаю ціну…")
                    val read = runCatching {
                        readForAdd(pricedPageHtml(link.url), link.url, id, day, rate)
                    }.getOrNull()

                    /** The shop's own title, photograph and words replace the
                     * placeholder's, but the row keeps its id so nothing else has
                     * to be told it changed. */
                    fun fill(fetched: Wish) {
                        val filled = wishes.map {
                            if (it.id == id) {
                                refreshedWish(it, fetched, day, rate).copy(name = fetched.name)
                            } else {
                                it
                            }
                        }
                        wishes = filled
                        store.saveWishes(filled)
                    }

                    when (read) {
                        // No page at all, or one that named nothing. Either way
                        // the link is safe in the list and the price is typed.
                        null, PageAdd.Blank -> say("Сторінка не читається — впишіть ціну вручну")
                        is PageAdd.Priced -> {
                            fill(read.wish)
                            say("Додано: ${read.wish.name}")
                        }
                        // The case the app used to throw away. The name, the
                        // photograph and the description were all on the page;
                        // only the number has to come from you.
                        is PageAdd.Described -> {
                            fill(read.wish)
                            say("Додано: ${read.wish.name} — ціни на сторінці немає, впишіть її")
                        }
                    }
                }
            } }
        }
    }

    // System back closes the item page before it leaves the app.
    BackHandler(enabled = openedWish != null || openedOrder != null) {
        openedWish = null
        openedOrder = null
    }

    // How many times the app has come back to the front. Everything below that is
    // read off the store once is read again on each of these: the activity lives
    // for days on this phone, and "once" used to mean "since the day it was
    // opened" — a pill saying «сьогодні» about yesterday, a recap that never came.
    var returns by remember { mutableIntStateOf(0) }

    // Read once per return, so the pill cannot change its mind about what is due
    // partway through a visit that happens to cross midnight, and so the budget
    // and the pill cannot disagree about which trials have run out.
    val today = remember(returns) { LocalDate.now() }

    // Re-read on every tab change as well: the Курс tab fetches the rate and the
    // Платежі tab edits the income, and both used to stay stale everywhere else
    // until the app was killed.
    val usdSell = remember(returns, tab) { store.fxRate().first }.sell
    // One «Вільно» for every surface: income less the month's payments, plus what
    // the funds hold of this month's annual charges, less «На життя» when it is on.
    // See MoneyPlan.kt. With nothing set it is income less payments, as it was.
    val moneyInputs = MoneyInputs(
        today = today,
        income = remember(pays, returns, tab, incomeEdits) { store.income() },
        pays = pays,
        marks = paid,
        wishes = wishes,
        funds = funds,
        usdSell = usdSell,
        life = planSettings.life,
        payday = planSettings.payday,
        holidays = remember(returns) { store.holidaysAround(today) },
        ritual = ritualRecord
    )
    val moneyNow = remember(moneyInputs) { moneyPlan(moneyInputs) }
    val monthBudget = moneyNow.month.asBudget()

    val addLabel = when {
        // An item page has its own actions, and the button would cover them.
        openedWish != null || openedOrder != null -> null
        tab == TAB_WISHES -> "Додати бажання"
        tab == TAB_PAYMENTS -> "Додати витрату"
        tab == TAB_ORDERS -> "Додати покупку"
        else -> null
    }

    // What the owner has already waved away today. Held in state as well as in the
    // preferences so that a swipe takes effect on the spot rather than on the next
    // launch — the pill is the one surface where "it will be gone next time" is
    // indistinguishable from the bug this was fixed alongside.
    var dismissedNote by remember { mutableStateOf(store.dismissedNote()) }

    // Suppressed on an item page for the same reason as the action button: that
    // page is one thing at a time, and the pill would be a second one.
    val note = if (openedWish == null && openedOrder == null) {
        statusNote(orders, pays, wishes, today, usdSell, paid, dismissedNote)
    } else {
        null
    }

    /**
     * A deletion, with both ways back out of it.
     *
     * The bar covers the mistake noticed at once and the bin covers the one
     * noticed a week later, and both go through here so that what the bar puts
     * back is exactly what the bin would have: the stored item, whole.
     */
    fun recycle(entry: BinEntry, restore: () -> Unit) {
        bin = store.recycle(entry)
        noticeScope.launch {
            notices.currentSnackbarData?.dismiss()
            val answer = notices.showSnackbar(
                message = binUndoMessage(entry),
                actionLabel = "Повернути",
                duration = SnackbarDuration.Long
            )
            if (answer == SnackbarResult.ActionPerformed) {
                store.dropFromBin(entry.id)
                bin = store.bin()
                restore()
            }
        }
    }

    fun deleteWish(wish: Wish) {
        val next = wishes.filterNot { it.id == wish.id }
        wishes = next
        store.saveWishes(next)
        // Appended on the way back rather than slotted into its old index: the
        // grid's order is the user's sort, not a property of the wish.
        recycle(binEntryOf(wish, today.toEpochDay())) {
            if (wishes.none { it.id == wish.id }) {
                val back = wishes + wish
                wishes = back
                store.saveWishes(back)
            }
        }
    }

    /** By position, because two identical expenses are equal as values. */
    fun deletePay(index: Int) {
        val pay = pays.getOrNull(index) ?: return
        val next = pays.filterIndexed { at, _ -> at != index }
        pays = next
        store.savePays(next)
        // A cancelled payment goes to the bin running — restoring it means it is
        // wanted again — and its monobank merchant stays watched for three months.
        val entry = if (pay.stopReason == STOP_CANCELLED) {
            rememberGone(context, pay)
            endedBinEntry(pay, today.toEpochDay()).copy(id = binEntryOf(pay, today.toEpochDay()).id)
        } else {
            binEntryOf(pay, today.toEpochDay())
        }
        recycle(entry) {
            val back = pays + pay
            pays = back
            store.savePays(back)
        }
    }

    fun deleteOrder(order: Order) {
        val next = orders.filterNot { it.id == order.id }
        orders = next
        store.saveOrders(next)
        recycle(binEntryOf(order, today.toEpochDay())) {
            if (orders.none { it.id == order.id }) {
                val back = orders + order
                orders = back
                store.saveOrders(back)
            }
        }
    }

    /** Everything a restore can touch, re-read at once. */
    fun reload() {
        wishes = store.wishes()
        pays = store.pays()
        orders = store.orders()
        paid = store.paidMarks()
        bin = store.bin()
        funds = planStore.funds()
        planSettings = planStore.settings()
        ritualRecord = planStore.ritual()
        declinedFunds = planStore.declinedFunds()
    }

    // A monobank pass writes ticks and jar balances straight into the store; the
    // lists here are read again when it ends, as after any background pass.
    val monoVersion = MonoStore.version
    LaunchedEffect(monoVersion) { if (monoVersion > 0) reload() }
    // «Сплачено» from the widget or the morning message, made while this screen
    // was open: the marks are read again so it shows, and so the next tick here
    // starts from them (QuickActions.kt).
    val quickVersion = QuickMarks.version
    LaunchedEffect(quickVersion) { if (quickVersion > 0) paid = store.paidMarks() }
    // The sheet over a shop (ShopSheet.kt) writes a new wish straight into the store
    // while this screen may still be alive behind it; the list is read again at
    // once, so nothing here saves its older copy over the new wish.
    val sheetVersion = ShopSheetSignal.version
    LaunchedEffect(sheetVersion) { if (sheetVersion > 0) reload() }
    val monoBalance = remember(monoVersion) {
        MonoStore(context).let { mono -> mono.client()?.takeIf { mono.connected() }?.let { ownUah(it, mono.accountsToRead(it)) } }
    }
    val monoAt = remember(monoVersion) { MonoStore(context).clientAt() }

    // A fund pays its part of an annual charge once the month is marked or over —
    // whichever way the mark came, a monobank tick included. Kept, so the next
    // contribution counts towards next year's charge. See [settledFund].
    LaunchedEffect(moneyNow.funds) {
        if (moneyNow.funds != funds) {
            funds = moneyNow.funds
            planStore.saveFunds(moneyNow.funds)
        }
    }

    /** A tick on Платежі, and what a fund paid for it said out loud. */
    fun markPaid(marks: List<PaidMark>) {
        // Onto the marks as the store has them, not over them: the widget and the
        // morning message may have marked something since this list was read
        // (QuickActions.kt, HANDOFF §15).
        val before = paid
        paid = store.updatePaidMarks { now -> rebaseMarks(now, before, marks) }
        val settled = settledFunds(funds, pays, paid, today, usdSell)
        if (settled != funds) {
            coverageNote(funds, settled, pays, paid, usdSell)?.let { say(it) }
            funds = settled
            planStore.saveFunds(settled)
        }
    }

    val moneyHost = MoneyHost(
        inputs = moneyInputs,
        plan = moneyNow,
        settings = planSettings,
        monoBalance = monoBalance,
        monoAt = monoAt,
        declinedFunds = declinedFunds,
        treat = monthTreat(wishes, moneyNow.treatBudget - codTotal(codDues(orders, today)), today.toEpochDay()),
        saveSettings = { settings ->
            planSettings = settings
            planStore.saveSettings(settings)
        },
        saveIncome = { value ->
            store.saveIncome(value)
            incomeEdits++
        },
        updateWishes = { change ->
            val next = change(wishes)
            wishes = next
            store.saveWishes(next)
        },
        updateFunds = { change ->
            val next = change(funds)
            funds = next
            planStore.saveFunds(next)
        },
        deleteFund = { fund ->
            val next = funds.filterNot { it.id == fund.id }
            funds = next
            planStore.saveFunds(next)
            // Into the bin like everything else, so a slip costs nothing.
            recycle(binEntryOf(fund, today.toEpochDay())) {
                if (funds.none { it.id == fund.id }) {
                    val back = funds + fund
                    funds = back
                    planStore.saveFunds(back)
                }
            }
        },
        addPay = { pay ->
            val next = pays + pay
            pays = next
            store.savePays(next)
        },
        saveRitual = { record ->
            ritualRecord = record
            planStore.saveRitual(record)
        },
        declineFund = { name ->
            planStore.declineFund(name)
            declinedFunds = planStore.declinedFunds()
        },
        say = { say(it) }
    )

    // The background pass writes prices, histories and parcel statuses straight
    // into the store. The lists here were read at launch and never again, so the
    // first edit after a pass saved the launch-time list over everything the pass
    // had learned — and the next pass announced the same target hit a second time.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        returns++
        // A day passing changes the stored list — a promo's end written into its
        // history, a cancellation whose question waited a week moved to the bin
        // (PaymentsLife.kt) — so that is applied before the lists are read.
        sweepPayments(context, store, LocalDate.now())
        reload()
    }

    // Read afresh every time the overview is opened rather than once per session:
    // "last run" is only worth anything as this second's answer, and the reasons
    // the system is holding a job change with the battery and the network.
    var health by remember { mutableStateOf<WorkHealth?>(null) }
    var healthOpen by remember { mutableStateOf(false) }

    /**
     * The recap for the month that has ended, built silently and left waiting.
     *
     * No notification and no dialog on launch. It is assembled here because this
     * is the one place that holds all four lists at once, and it sits on the
     * overview until it is tapped — an artefact, not an announcement.
     */
    var recapSeen by remember { mutableStateOf(store.recapSeen()) }
    var recapOpen by remember { mutableStateOf(false) }
    val recap = remember(recapSeen, wishes, pays, orders, paid) {
        recapDue(recapSeen, today)?.let { month ->
            monthlyRecap(
                wishes = wishes,
                pays = pays,
                orders = orders,
                marks = paid,
                month = month,
                today = today,
                income = monthBudget.income,
                usdSellRate = usdSell
            )
            // A month with too little in it produces no ceremony at all, the same
            // way a morning with no news sends no digest.
        }?.takeUnless { it.empty }
    }
    var digestHour by remember { mutableIntStateOf(store.digestHour()) }
    LaunchedEffect(tab) {
        if (tab != TAB_OVERVIEW) return@LaunchedEffect
        health = WorkHealth(
            runs = store.workRuns(),
            pendingReasons = pendingJobReasons(context),
            apiLevel = android.os.Build.VERSION.SDK_INT,
            nowMillis = System.currentTimeMillis(),
            notificationsOn = androidx.core.app.NotificationManagerCompat.from(context)
                .areNotificationsEnabled()
        )
    }

    // The bar is chrome, and chrome should yield to content. It moves all the
    // way or not at all: following the finger left it resting half off screen,
    // with its labels cut and its icons crowding the system buttons.
    val density = LocalDensity.current
    val barTravel = with(density) { Space.navBar.toPx() } +
        WindowInsets.navigationBars.getBottom(density)
    val barDown = remember { mutableStateOf(false) }
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
        // The deck replaces the app rather than floating over it. As a dialog it
        // would be a second window, outside the box the theme paints the ground and
        // the grain in, and it would have had to fill the whole screen with flat
        // near-black — the one surface in the app that would then band. Composed
        // here instead, the theme's ground is its ground, and the navigation bar
        // and the action button are gone for the duration without being suppressed
        // one condition at a time.
        val deck = recap.takeIf { recapOpen }
        if (deck != null) {
            RecapDeck(deck) {
                store.saveRecapSeen(deck.month)
                recapSeen = deck.month
                recapOpen = false
                // Coming back from a screen that took over the whole phone is
                // arriving somewhere, not continuing a scroll. Without this the
                // chrome returns in whatever state the last flick left it in, and
                // the deck closes onto a screen with no navigation bar and no
                // action button until you happen to scroll upward.
                barDown.value = false
            }
            return@FlowPayTheme
        }
        // The bar is computed below the deck's early return, so while the deck is
        // up this animation is not in the composition at all. Coming back, it is
        // composed afresh and animateFloatAsState starts at its target, so the bar
        // is simply there rather than sliding in behind a screen that just closed.
        // Inside the theme, not above it: LocalReducedMotion is provided by
        // FlowPayTheme, and read one line higher it would quietly be the default
        // rather than the phone's answer — which is the failure this whole audit is
        // about, arrived at by accident.
        //
        // On a phone asked to stop animating, the bar simply stays. Snapping it in
        // and out on every change of scroll direction would be worse than either
        // answer: a navigation bar and a button that blink out of existence when you
        // flick, and back when you flick the other way. The hiding exists only as a
        // movement — chrome getting out of the way of content — so with the movement
        // gone there is nothing left worth keeping, and the honest answer is to
        // leave the chrome where it is.
        val barGone = barDown.value && !LocalReducedMotion.current
        val glass = remember { HazeState() }
        // How many form sheets are up. The local's default is one counter for the
        // whole app, which is what a single-activity app needs; the sheets count
        // themselves into it wherever they are composed. See LocalSheetsOpen.
        val sheetsOpen = LocalSheetsOpen.current
        val barHidden by animateFloatAsState(
            if (barGone) barTravel else 0f,
            Motion.spatial(),
            label = "bottom bar"
        )
        Scaffold(
            modifier = Modifier.nestedScroll(barScroll),
            // Transparent, not AppBackground: the theme has already painted the
            // ground and laid the grain over it, and a second opaque fill here
            // would cover the grain up again.
            containerColor = Color.Transparent,
            contentColor = TextPrimary,
            snackbarHost = { SnackbarHost(notices) },
            // No floating button: each screen's "+" sits in its header now. See
            // [AddButton] for what the floating one used to cover.
            bottomBar = {
                // Frosted glass, which HANDOFF §12 once ruled out because blur does
                // nothing below Android 12. The owner's phone is Android 16, the
                // rows readable through the 0.82 bar were the complaint, and Haze
                // falls back to a plain tinted bar below 12 — no worse than before.
                // Decided with the owner on 3 October 2026.
                FlowPayNavBar(
                    destinations = NAV_DESTINATIONS,
                    selected = tab,
                    modifier = Modifier
                        .offset { IntOffset(0, barHidden.roundToInt()) }
                        .hazeEffect(glass, NavGlass)
                ) { tab = it }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    // What the bar's glass blurs: everything the screens draw.
                    .hazeSource(glass)
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
                StatusPill(
                    // A payment's sum hides with the eye on Огляд; a shop's price does not.
                    note?.let { if (it.kind == StatusKind.PAYMENT) it.copy(detail = personal(it.detail)) else it },
                    onDismiss = { shown ->
                        val mark = NoteDismissal(shown.key, today.toEpochDay())
                        dismissedNote = mark
                        store.saveDismissedNote(mark)
                    }
                ) { target -> tab = target }
                // The app steps back while a form sheet is up: a little smaller and
                // softly blurred, so the sheet reads as in front of it rather than
                // as a grey cloth over it. Blur needs Android 12, which the owner's
                // phone has; older phones get the scale alone.
                val sheetDepth by animateFloatAsState(
                    if (sheetsOpen.intValue > 0) 1f else 0f,
                    Motion.spatial(),
                    label = "sheet depth"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .graphicsLayer {
                            val scale = 1f - 0.04f * sheetDepth
                            scaleX = scale
                            scaleY = scale
                        }
                        .blur((10 * sheetDepth).dp)
                ) {
                    // Each tab keeps where it was scrolled to. Switching drifts the
                    // page a tenth of the way in from the side of the tab you
                    // tapped, and the tab then plays its own arrival — the title,
                    // the tiles, the figures, the emoji. See [Entrance].
                    val tabStates = rememberSaveableStateHolder()
                    val fade = Motion.effects<Float>()
                    val drift = Motion.spatial<IntOffset>()
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val towards = if (targetState > initialState) 1 else -1
                            (fadeIn(fade) + slideInHorizontally(drift) { towards * it / 10 })
                                .togetherWith(fadeOut(fade) + slideOutHorizontally(drift) { -towards * it / 10 })
                        },
                        label = "tab"
                    ) { shown ->
                    tabStates.SaveableStateProvider(shown) {
                    CompositionLocalProvider(LocalEntrance provides rememberEntrance()) {
                    when (shown) {
                        TAB_WISHES -> WishlistScreen(
                            items = wishes,
                            save = { wishes = it; store.saveWishes(it) },
                            update = { change ->
                                val next = change(wishes)
                                wishes = next
                                store.saveWishes(next)
                            },
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
                            moneyHost = moneyHost,
                            onDelete = { deleteWish(it) },
                            onBought = { order, wish ->
                                val next = orders + order
                                orders = next
                                store.saveOrders(next)
                                val left = wishes.filterNot { it.id == wish.id }
                                wishes = left
                                store.saveWishes(left)
                                // Binned, but without the undo bar: buying is a move
                                // rather than a mistake, and "Видалено «…» Повернути"
                                // over a tab that has just switched to Покупки would
                                // be the app contradicting itself. The entry is still
                                // there for thirty days, because a purchase recorded
                                // by accident should not cost months of price history
                                // — the parcel only carries the one figure the
                                // verdict needs, not the whole series.
                                bin = store.recycle(binEntryOf(wish, today.toEpochDay()))
                                tab = TAB_ORDERS
                            }
                        )
                        TAB_RATE -> CalculatorScreen(store)
                        TAB_PAYMENTS -> PaymentsScreen(
                            items = pays,
                            save = { pays = it; store.savePays(it) },
                            store = store,
                            adding = adding,
                            setAdding = { adding = it },
                            paid = paid,
                            setPaid = { marks -> markPaid(marks) },
                            onDelete = { deletePay(it) },
                            orders = orders,
                            // A plan's purchase went back: the purchase says so.
                            onOrderReturned = { id, day ->
                                val next = orders.map { if (it.id == id) it.copy(planReturned = day) else it }
                                orders = next
                                store.saveOrders(next)
                            },
                            shared = sharedLetter,
                            onSharedUsed = { sharedLetter = null },
                            moneyHost = moneyHost
                        )
                        TAB_ORDERS -> OrdersScreen(
                            items = orders,
                            save = { orders = it; store.saveOrders(it) },
                            update = { change ->
                                val next = change(orders)
                                orders = next
                                store.saveOrders(next)
                            },
                            context = context,
                            adding = adding,
                            setAdding = { adding = it },
                            store = store,
                            opened = openedOrder,
                            setOpened = { openedOrder = it },
                            onDelete = { deleteOrder(it) },
                            sharedTracking = sharedTracking,
                            onSharedTrackingUsed = { sharedTracking = null }
                        )
                        // The overview is where "how am I doing" is asked, and every
                        // figure on it is only as true as the last background pass.
                        // So a pass that has stopped says so above the screen; one
                        // that is fine says so on its row under Налаштування.
                        else -> Column(Modifier.fillMaxSize()) {
                            val line = health?.let { healthLine(it) }
                            WorkHealthStrip(stripLine(line)) { healthOpen = true }
                            Box(Modifier.weight(1f)) {
                                val summary = overview(
                                    wishes,
                                    pays,
                                    orders,
                                    monthBudget.income,
                                    usdSell,
                                    today,
                                    plan = moneyNow
                                )
                                // Parcels that will still take money at the counter —
                                // ParcelsMore.kt. They rain on their day, they are a
                                // line under the free money, and the treat leaves
                                // them room; the big figure itself is untouched.
                                val cod = codDues(orders, today)
                                SettingsScreen(
                                    recap = recap,
                                    onOpenRecap = { recapOpen = true },
                                    summary = summary,
                                    weather = weatherWithParcels(
                                        moneyWeather(
                                            pays, paid, today, usdSell, monthBudget.income, summary.freeCash,
                                            covered = weatherCover(moneyNow.funds, pays, today, usdSell)
                                        ),
                                        cod, monthBudget.income, summary.freeCash
                                    ),
                                    balance = monoBalance,
                                    treat = monthTreat(wishes, summary.freeCash - summary.plannedMonthly - codTotal(cod), today.toEpochDay()),
                                    parcelsToPay = codLine(cod),
                                    weatherParcels = codChips(cod, today),
                                    owed = owed(orders, today.toEpochDay()),
                                    onOpenWish = { id ->
                                        tab = TAB_WISHES
                                        openedWish = id
                                    },
                                    store = store,
                                    health = line,
                                    onOpenHealth = { healthOpen = true },
                                    next = nextPayment(stillOwing(pays, paid, today), today, usdSell),
                                    usdRate = usdSell,
                                    onOpenTab = { tab = it },
                                    bin = bin,
                                    onRestore = { id -> store.restoreFromBin(id); reload() },
                                    onDropFromBin = { id ->
                                        store.dropFromBin(id)
                                        bin = store.bin()
                                    },
                                    onEmptyBin = { store.emptyBin(); bin = store.bin() },
                                    onImported = { reload() },
                                    moneyHost = moneyHost
                                )
                            }
                        }
                    }
                    }
                    }
                    }
                }
            }
            // Marked seen on opening rather than on being shown. A deck that
            // vanished because you scrolled past it once would be a month's worth
            // of the app's only ceremony, lost to a flick.
            if (healthOpen) {
                health?.let { current ->
                    WorkHealthSheet(
                        health = current,
                        digestHour = digestHour,
                        onHour = { hour ->
                            digestHour = hour
                            store.saveDigestHour(hour)
                            // The enqueued request carries the old time of day in its
                            // initial delay, so the schedule has to be replaced rather
                            // than left to notice.
                            ReminderWorker.reschedule(context)
                        },
                        onOpenSettings = { openBackgroundSettings(context) },
                        onClose = { healthOpen = false },
                        onOpenNotifications = { openNotificationSettings(context) }
                    )
                }
            }
            // A Hotline page shared into the app: which wish is it the market for?
            // Bound onto the list as it is when the owner answers, then that wish's
            // page opens with the market line under its price. PricesUi.kt.
            marketShare?.let { page ->
                BindMarketSheet(
                    url = page,
                    wishes = wishes,
                    onClose = { marketShare = null },
                    onBind = { id, market ->
                        val next = wishes.map { if (it.id == id) it.copy(market = market) else it }
                        wishes = next
                        store.saveWishes(next)
                        marketShare = null
                        openedWish = id
                    }
                )
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
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    /** Zero where the list around it already supplies the screen margin. */
    inset: Dp = Space.screen
) {
    // The tab's own name and the screen's actions, on one line. The lime overline
    // and the sentence under the title explained the app to its own owner on every
    // visit, and took a seventh of the screen to do it.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = inset, end = inset, top = Space.lg, bottom = Space.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Kinetic type on a tab's arrival: the title rises out of a mask while its
        // letters close up from wide to their set tracking.
        val rise = entranceFraction(1f, delayMs = 0L, stiffness = 260f)
        Box(Modifier.weight(1f).clipToBounds()) {
            Text(
                title,
                Modifier.graphicsLayer { translationY = (1f - rise) * size.height },
                fontFamily = Display,
                fontSize = Type.screenTitleBentoSize,
                lineHeight = Type.screenTitleLine,
                letterSpacing = androidx.compose.ui.unit.lerp(8.sp, Type.screenTitleTracking, rise.coerceIn(0f, 1f)),
                fontWeight = Type.strong
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun WishlistScreen(
    items: List<Wish>,
    save: (List<Wish>) -> Unit,
    /**
     * A change computed from the list as it is when it lands, not as it was when
     * the work began. Every path that waits on the network goes through this —
     * see Merge.kt for what saving the starting list used to do.
     */
    update: ((List<Wish>) -> List<Wish>) -> Unit,
    store: Store,
    context: Context,
    adding: Boolean,
    setAdding: (Boolean) -> Unit,
    // Held by id rather than by value so the page keeps showing the live item
    // after a price refresh or a change to the savings plan.
    opened: String?,
    setOpened: (String?) -> Unit,
    freeCash: Double,
    /** The month and its plans — «Пропустити» and «А якщо куплю зараз?» on a wish page. */
    moneyHost: MoneyHost? = null,
    /** Deleting is the app's one irreversible act, so it is owned above this screen. */
    onDelete: (Wish) -> Unit,
    /**
     * The wish travels with the parcel it became, because removing it from the
     * list is a deletion like any other and belongs above this screen with the bin.
     */
    onBought: (Order, Wish) -> Unit
) {
    var editing by remember { mutableStateOf<Wish?>(null) }
    var sort by remember { mutableStateOf(store.wishSort()) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var dueling by remember { mutableStateOf(false) }
    // Which chip is down. Null is "Усі", and it is also where a category goes when
    // its last wish is renamed or deleted out from under the selection.
    var chosenCategory by remember { mutableStateOf<String?>(null) }
    val touch = rememberTouch()
    val scope = rememberCoroutineScope()
    // Read once, so a session that crosses midnight cannot change its mind about
    // which wishes are still on hold halfway down the list.
    val today = remember { LocalDate.now() }

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
                    onChange = { changed ->
                        update { now -> now.map { if (it.id == changed.id) changed else it } }
                    },
                    onEdit = { editing = shown },
                    onDelete = { onDelete(shown); setOpened(null) },
                    freeCash = freeCash,
                    moneyHost = moneyHost,
                    onBought = { trackingNumber, paid ->
                        onBought(
                            Order(
                                // Its own id, not the wish's. The wish goes to the bin
                                // under its id, and the same thing bought twice — the
                                // wish restored and bought again — would otherwise be
                                // two parcels under one key, which the purchases list
                                // cannot draw.
                                id = "o-${shown.id}-${System.currentTimeMillis()}",
                                name = shown.name,
                                url = shown.url,
                                status = ORDERED,
                                tracking = trackingNumber,
                                image = shown.image,
                                price = shown.price,
                                paid = paid,
                                // The wish leaves the list on the next line, so the
                                // one number the verdict needs has to be carried
                                // across now or it is gone for good.
                                lowestSeen = lowestTracked(shown),
                                digital = isDigitalStore(shown.url),
                                // Gone with the wish otherwise: the reason it was
                                // wanted, for «Як тобі …?» three weeks on, and the
                                // category, for «Гаджети: 3 з 4 — 😍».
                                why = shown.why,
                                category = shown.category
                            ),
                            shown
                        )
                        setOpened(null)
                    }
                )
            } else {
                val categories = remember(items) { knownCategories(items) }
                val totals = remember(items) { categoryTotals(items) }
                // Derived rather than corrected in an effect: a category whose last
                // wish was deleted simply stops being selected on the next frame,
                // instead of leaving the grid filtered on a name nothing carries.
                val category = chosenCategory?.takeIf { chosen ->
                    categories.any { categoryKey(it) == categoryKey(chosen) }
                }
                val shown = remember(items, category, query) { filterWishes(items, category, query) }
                // Over the filtered list, not over everything. A headline saying
                // eighty-four thousand while the screen shows one category of it
                // would be a number arguing with the grid underneath it.
                val sum = remember(shown) { wishlistTotal(shown) }

                // The same controls in the large header and in the compact bar, so
                // refreshing and searching stay reachable once you are down the grid
                // — which is exactly where a list long enough to need searching puts
                // you. A field pinned to the top of the grid would scroll away with
                // the header and have to be scrolled back to.
                val refreshAction: @Composable () -> Unit = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                refreshing = true
                                val day = LocalDate.now().toEpochDay()
                                val rate = store.fxRate().first
                                val before = items.count { targetHit(it, day) }
                                val started = items
                                val fetched = started.map { refreshed(it, day, rate) }
                                val result = applyFollowed(started, fetched)
                                // One tick if this pass brought something to the
                                // price you named — not one per wish. The count is
                                // compared rather than the list, because what is
                                // being reported is that the pass produced news at
                                // all; which item it was is on the grid a moment
                                // later, and the pill above it.
                                if (result.wishes.count { targetHit(it, day) } > before) {
                                    touch.landed()
                                }
                                update { now -> mergeById(now, started, result.wishes) { it.id } }
                                refreshing = false
                                message = listOfNotNull(
                                    refreshMessage(result.updated, started.size),
                                    staleMessage(staleCount(result.wishes))
                                ).joinToString(" · ")
                            }
                        },
                        enabled = !refreshing && items.isNotEmpty()
                    ) {
                        if (refreshing) {
                            BusyMark()
                        } else {
                            Icon(Icons.Default.Refresh, "Оновити ціни", tint = TextSecondary)
                        }
                    }
                }
                val headerActions: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (items.size > 1) {
                            IconButton(
                                onClick = {
                                    searching = !searching
                                    // Closing the box has to clear what was in it, or
                                    // the grid stays filtered by a query with nothing
                                    // on screen to explain why.
                                    if (!searching) query = ""
                                }
                            ) {
                                Icon(
                                    if (searching) Icons.Default.Close else Icons.Default.Search,
                                    if (searching) "Закрити пошук" else "Пошук за назвою",
                                    tint = if (query.isNotBlank()) Accent else TextSecondary
                                )
                            }
                        }
                        refreshAction()
                        AddButton("Додати бажання") { setAdding(true) }
                    }
                }
                // The same actions plus the figure, for the bar the large title
                // hands over to. The panel below the header scrolls away with the
                // header; a total that is only visible at the very top of the list
                // is a total you have to scroll back for, which is most of the way
                // to not having one. Bare here rather than labelled: the panel has
                // already said what it is, and this bar is one row tall.
                val barActions: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (shown.isNotEmpty()) {
                            Text(
                                approxMoney(sum.total),
                                color = TextPrimary,
                                fontSize = Type.captionSize,
                                fontWeight = Type.medium,
                                maxLines = 1,
                                style = Tabular
                            )
                            Spacer(Modifier.width(Space.sm))
                        }
                        headerActions()
                    }
                }
                val gridState = rememberLazyGridState()
                // The box is item one, so opening it from the compact bar would
                // otherwise put the field somewhere thirty cards above the screen.
                LaunchedEffect(searching) { if (searching) gridState.animateScrollToItem(0) }
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
                                title = "Бажання",
                                // The grid already supplies the screen margin.
                                inset = 0.dp,
                                trailing = headerActions
                            )
                        }
                        // Unconditional, which is the whole fix. The figure already
                        // existed — on the «Усі» chip, in a row drawn only where
                        // there are two categories or more — so a list where
                        // everything is «Інше» has never once shown its own total.
                        // Nothing here asks how the list is arranged.
                        if (shown.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                WishlistTotalPanel(sum, wishlistTotalLabel(category, query))
                            }
                            if (duelPool(items, today.toEpochDay()).size >= 3) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    DuelInvite(Modifier.revealOnEnter(1).padding(bottom = Space.lg)) { dueling = true }
                                }
                            }
                        }
                        if (searching) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                WishSearchField(query, { query = it }) {
                                    searching = false
                                    query = ""
                                }
                            }
                        }
                        if (showsCategoryRow(items)) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                CategoryChips(
                                    totals = totals,
                                    everything = allCategoriesTotal(items),
                                    selected = category
                                ) { tapped ->
                                    // Tapping the chip that is already down clears it,
                                    // so getting back to the whole list never needs the
                                    // row to be scrolled back to its first chip.
                                    chosenCategory = if (tapped == category) null else tapped
                                }
                            }
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
                                            color = TextPrimary,
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
                                                text = {
                                                    Text(
                                                        option.label,
                                                        // The trigger line names the
                                                        // current order, but not once
                                                        // the menu covers it.
                                                        color = if (option == sort) Accent else TextPrimary
                                                    )
                                                },
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
                        if (items.isNotEmpty() && shown.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    browseEmptyNote(query, category),
                                    Modifier.padding(top = Space.lg, bottom = Space.lg),
                                    color = TextSecondary,
                                    fontSize = Type.bodySize,
                                    lineHeight = Type.bodyLine
                                )
                            }
                        }
                        // Held wishes drop to their own block at the foot of the list
                        // rather than vanishing: the whole point of a hold is to come
                        // back to the thing, and something you cannot find again was
                        // deleted rather than postponed.
                        val (watched, held) = partitionByHold(shown, today.toEpochDay())
                        itemsIndexed(sortWishes(watched, sort), key = { _, wish -> wish.id }) { index, wish ->
                            // Sorting, searching, holding and deleting move the cards
                            // rather than teleport them; opening the tab drops them in
                            // a row at a time.
                            WishCard(
                                wish,
                                this@AnimatedContent,
                                today.toEpochDay(),
                                Modifier.animateItem().revealOnEnter(index / 2 + 1)
                            ) {
                                setOpened(wish.id)
                            }
                        }
                        if (held.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "Відкладено — ${positionsLabel(held.size)}",
                                    Modifier.padding(top = Space.xl, bottom = Space.sm),
                                    color = TextSecondary,
                                    fontSize = Type.captionSize
                                )
                            }
                            items(sortWishes(held, sort), key = { it.id }) { wish ->
                                WishCard(wish, this@AnimatedContent, today.toEpochDay()) {
                                    setOpened(wish.id)
                                }
                            }
                        }
                    }
                    CollapsingTitle("Бажання", gridState, trailing = barActions)
                }
            }
        }
    }
    if (dueling) {
        DuelSheet(
            items = items,
            today = today.toEpochDay(),
            onPick = { winner, loser -> update { now -> afterDuel(now, winner, loser) } },
            onSetAside = { id ->
                update { now -> now.map { if (it.id == id) it.copy(holdUntil = today.toEpochDay() + DUEL_HOLD_DAYS) else it } }
            },
            onClose = { dueling = false }
        )
    }
    if (adding) {
        // What past purchases in a category were like, for the line under it.
        val bought = remember { store.orders() }
        AddWishSheet(
            { setAdding(false) },
            store.fxRate().first,
            knownCategories(items),
            categoryNote = { typed -> categoryJoy(bought, typed) }
        ) { wish ->
            update { now -> now + wish }
            setAdding(false)
        }
    }
    editing?.let { selected ->
        EditWishSheet(selected, knownCategories(items), { editing = null }) { changed ->
            update { now -> now.map { if (it.id == changed.id) changed else it } }
            editing = null
        }
    }
}

/**
 * The search box, which only exists while it is being used.
 *
 * It takes focus the moment it appears, because it was opened by tapping a
 * magnifier and anything else would ask for a second tap to do the obvious thing.
 */
@Composable
fun WishSearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        query,
        onQuery,
        Modifier
            .fillMaxWidth()
            .padding(bottom = Space.lg)
            .focusRequester(focus),
        label = { Text("Пошук за назвою") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) },
        trailingIcon = {
            IconButton(onClick = { if (query.isBlank()) onClose() else onQuery("") }) {
                Icon(Icons.Default.Close, "Очистити", tint = TextSecondary)
            }
        }
    )
}

/**
 * What the list on screen costs, said once, in words that cannot be misread.
 *
 * Not a [HeroPanel], and the reason is the rule written on [HeroPanel] itself:
 * one lime block per screen. This screen already spends its lime on the action
 * button and on whichever category chip is down, and a lime slab the width of the
 * grid would out-shout the photographs that are the point of the page. So the
 * emphasis comes from size and from being alone up there, not from colour.
 *
 * Three lines, in the order the questions arrive: what this is a total of, the
 * figure, and what the figure does not include. The third line is not an
 * apology — it is the difference between a sum and a sum you can act on, because
 * a wish whose page states no price contributes nothing and would otherwise make
 * the total quietly too small.
 *
 * Rounded to whole hryvnia, like the chips: this is a sum of many prices read on
 * many days, and kopecks on it would claim a precision it does not have.
 */
@Composable
fun WishlistTotalPanel(sum: WishlistTotal, label: String) {
    BentoTile(TileLavender, Modifier.revealOnEnter(0).fillMaxWidth().padding(bottom = Space.lg)) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(end = HeroStickerSize)) {
                Text(
                    label,
                    color = TileInkSoft,
                    fontSize = Type.captionSize,
                    fontWeight = Type.medium
                )
                Spacer(Modifier.height(Space.xs))
                SplitFigure(approxMoney(sum.total), 30.sp)
                Spacer(Modifier.height(Space.xs))
                // Never an alarm colour. Nothing has gone wrong — some pages
                // simply do not state a price, and this line is the sum being
                // honest about itself rather than the app reporting a fault.
                TileCaption(wishlistTotalNote(sum), TileLavender, maxLines = 3)
            }
            EmojiSticker("🛍️", 48.dp, Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * One chip per category, each carrying what it is holding.
 *
 * The money is the point of the row as much as the filter is: "Техніка" says
 * nothing you did not know, while "Техніка · 62 000 ₴" answers the question a
 * wishlist is really being asked. On the chip rather than in the overview because
 * that puts every category's figure beside every other one, which is the only way
 * the number means anything — a total alone is a number, four totals side by side
 * is where the money went.
 */
@Composable
fun CategoryChips(
    totals: List<CategoryTotal>,
    everything: Double,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        Modifier.padding(bottom = Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        item {
            CategoryChip("Усі", approxMoney(everything), selected == null) { onSelect(null) }
        }
        items(totals, key = { it.name }) { total ->
            CategoryChip(
                total.name,
                approxMoney(total.total),
                categoryKey(total.name) == selected?.let(::categoryKey)
            ) { onSelect(total.name) }
        }
    }
}

@Composable
private fun CategoryChip(label: String, amount: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(Radius.pill)
            // Chosen is a lighter neutral with a white label, like the view switch
            // on Платежі: a filter is never the subject of the screen.
            .background(if (active) HairLine else SurfaceBase)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.sm)
    ) {
        Text(
            label,
            color = if (active) TextPrimary else TextSecondary,
            fontSize = Type.captionSize,
            fontWeight = Type.medium,
            maxLines = 1
        )
        Text(
            amount,
            // Two thirds opacity rather than a second colour: on the lime chip any
            // muted grey from the palette turns muddy against it.
            color = if (active) TextSecondary else TextDisabled,
            fontSize = Type.overlineSize,
            maxLines = 1,
            style = Tabular
        )
    }
}

/**
 * The categories already in use, offered rather than imposed.
 *
 * The field stays free text — a fixed list would be wrong about this person's
 * things within a week — but typing "техніка" a second time is how one category
 * quietly becomes two, and tapping is both faster and exact.
 */
@Composable
fun CategorySuggestions(known: List<String>, chosen: String, onPick: (String) -> Unit) {
    if (known.isEmpty()) return
    LazyRow(
        Modifier.padding(top = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        items(known, key = { it }) { name ->
            val active = categoryKey(name) == categoryKey(chosen)
            Text(
                name,
                Modifier
                    .clip(Radius.pill)
                    .background(if (active) AccentSoft else SurfaceHigh)
                    .clickable { onPick(name) }
                    .padding(horizontal = Space.md, vertical = Space.sm),
                color = if (active) Accent else TextSecondary,
                fontSize = Type.captionSize,
                maxLines = 1
            )
        }
    }
}

/**
 * What the page did give, shown before asking for the one thing it did not.
 *
 * The photograph and the title are here to be checked, not admired: a shared link
 * is often not the link the person thought they shared, and typing a price into a
 * sheet that has silently latched onto the wrong item is the one mistake this
 * screen can make that nothing downstream would catch. Seeing the thing answers
 * that before a number is typed.
 */
@Composable
fun NoPricePreview(facts: PageFacts, link: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (facts.image.isNotBlank()) {
            AsyncImage(
                facts.image,
                facts.name,
                Modifier
                    .size(64.dp)
                    .clip(Radius.sm)
                    .background(SurfaceRaised),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(Space.md))
        }
        Column(Modifier.weight(1f)) {
            Text(
                facts.name.ifBlank { placeholderName(link) },
                fontSize = Type.bodySize,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                sourceName(link),
                color = TextSecondary,
                fontSize = Type.captionSize,
                maxLines = 1
            )
        }
    }
    Text(
        noPriceNote(facts),
        color = TextSecondary,
        fontSize = Type.captionSize,
        lineHeight = Type.captionLine,
        modifier = Modifier.padding(top = Space.md)
    )
}

@Composable
fun AddWishSheet(
    close: () -> Unit,
    rate: FxRate = FxRate(),
    /** The spellings already in use, so a new wish joins a category instead of forking it. */
    known: List<String> = emptyList(),
    /** «Гаджети: 3 з 4 — 😍» for the category being typed — see ParcelsMore.kt. */
    categoryNote: (String) -> CategoryJoy? = { null },
    add: (Wish) -> Unit
) {
    var link by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(OTHER_CATEGORY) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The page is held between the two steps, so choosing an edition does not
    // fetch it a second time and cannot land on a different version of it.
    var page by remember { mutableStateOf<String?>(null) }
    var offers by remember { mutableStateOf(emptyList<Offer>()) }
    // Set only on the third path: the page was read, it says what the thing is,
    // and it does not say what it costs. Null keeps the sheet on its usual form.
    var facts by remember { mutableStateOf<PageFacts?>(null) }
    var typedPrice by remember { mutableStateOf("") }
    val touch = rememberTouch()
    val scope = rememberCoroutineScope()

    fun finish(offer: Offer) {
        val html = page ?: return
        // Both ways into the list come through here — the page with one price, and
        // the card tapped when it had several — so the tick belongs here rather
        // than at either call site, where one of them would have been forgotten.
        touch.landed()
        add(
            wishFromOffer(
                html,
                link.trim(),
                System.currentTimeMillis().toString(),
                offer,
                LocalDate.now().toEpochDay(),
                rate
            ).copy(
                targetPrice = parseAmount(target),
                category = canonicalCategory(category, known)
            )
        )
    }

    /** The third way in: everything the page gave, plus the number it withheld. */
    fun finishTyped(read: PageFacts) {
        touch.landed()
        add(
            wishFromFacts(
                read,
                link.trim(),
                System.currentTimeMillis().toString(),
                parseAmount(typedPrice),
                LocalDate.now().toEpochDay()
            ).copy(
                targetPrice = parseAmount(target),
                category = canonicalCategory(category, known)
            )
        )
    }

    val read = facts
    FormSheet(
        title = when {
            offers.size > 1 -> "Яка ціна ваша?"
            read != null -> "Ціну доведеться вписати"
            else -> "Новий товар"
        },
        confirmLabel = if (loading) "Зчитую…" else "Додати",
        confirmEnabled = when {
            loading -> false
            // Nothing to confirm while the picker is the question on screen: the
            // rows themselves are the answer.
            offers.size > 1 -> false
            read != null -> parseAmount(typedPrice) > 0.0
            else -> isSupportedWebUrl(link)
        },
        onConfirm = {
            if (read != null) finishTyped(read) else scope.launch {
                loading = true
                error = null
                runCatching { pricedPageHtml(link) }
                    .onSuccess { html ->
                        val found = extractOffers(html)
                        // A page priced in money the app has no rate for cannot be
                        // tracked at all, and saying so now is kinder than adding a
                        // wish that will never show a price. Judged on the first
                        // offer because a page prices every edition in one currency.
                        val money = found.firstOrNull()
                            ?.let { toHryvnia(it.price, it.currency, rate) }
                        val whatItIs = pageFacts(html)
                        when {
                            // Read fine, priced nothing. Not a failure: the name
                            // and the photograph are the parts that cannot be
                            // typed again, and the number is the part that can.
                            found.isEmpty() && whatItIs.describable -> facts = whatItIs
                            found.isEmpty() -> error = NOTHING_READ_NOTE
                            money != null && money.noRate ->
                                error = "Ціна в ${money.currency} — FlowPay знає курс лише долара"
                            // One price is not a question worth asking.
                            found.size == 1 -> { page = html; finish(found.first()) }
                            else -> { page = html; offers = found }
                        }
                    }
                    .onFailure { error = it.message ?: "Не вдалося прочитати сторінку" }
                // Whichever way it failed — nothing readable on the page, money
                // with no rate, or no page at all. The error text says which; this
                // says that the link you pasted did not become a wish, which is
                // what you were waiting to find out with the phone in your hand.
                if (error != null) touch.refused()
                loading = false
            }
        },
        onDismiss = close
    ) {
        if (read != null) {
            NoPricePreview(read, link)
            NumberField("Ціна, ₴", typedPrice) { typedPrice = it }
            NumberField("Цільова ціна, ₴ (необов'язково)", target) { target = it }
            OutlinedTextField(
                category,
                { category = it },
                Modifier.fillMaxWidth().padding(top = Space.md),
                label = { Text("Категорія") }
            )
            CategorySuggestions(known, category) { category = it }
            categoryNote(category)?.let { CategoryJoyLine(it) }
        } else if (offers.size > 1) {
            Text(
                "На сторінці кілька цін. Оберіть ту, за якою стежити — " +
                    "далі FlowPay щоразу шукатиме саме її.",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
            Spacer(Modifier.height(Space.md))
            offers.forEachIndexed { index, offer ->
                Card(
                    onClick = { finish(offer) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = Space.sm).litEdge(Radius.sm),
                    colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
                    shape = Radius.sm
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(Space.lg),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = Space.md)) {
                            Text(
                                offerLabel(offer, index),
                                fontSize = Type.bodySize,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // A page of editions routinely has one of them sold
                            // out, and the figure beside a sold-out edition is
                            // often the lowest on the page — so without this the
                            // cheapest-looking row in the picker is the one that
                            // cannot be bought. Stated rather than hidden: tracking
                            // a thing until it comes back is the point of the list.
                            offerStockLabel(offer)?.let { stock ->
                                Text(
                                    stock,
                                    color = if (blocksPrice(offer.availability)) {
                                        Negative
                                    } else {
                                        TextSecondary
                                    },
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine
                                )
                            }
                        }
                        // In the shop's own money, because that is what is printed
                        // on the page the person is choosing from.
                        Text(
                            amountLabel(offer.price, offer.currency.ifBlank { UAH }),
                            fontWeight = Type.strong,
                            style = Tabular
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Посилання на товар") })
            NumberField("Цільова ціна, ₴ (необов'язково)", target) { target = it }
            OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth().padding(top = Space.md), label = { Text("Категорія") })
            CategorySuggestions(known, category) { category = it }
            categoryNote(category)?.let { CategoryJoyLine(it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Space.sm)) }
        }
    }
}

@Composable
fun EditWishSheet(
    wish: Wish,
    /** The spellings already in use, so an edit joins a category instead of forking it. */
    known: List<String> = emptyList(),
    close: () -> Unit,
    save: (Wish) -> Unit
) {
    var name by remember { mutableStateOf(wish.name) }
    var target by remember { mutableStateOf(amountText(wish.targetPrice)) }
    var category by remember { mutableStateOf(wish.category) }
    var price by remember { mutableStateOf(amountText(wish.price)) }
    var why by remember { mutableStateOf(wish.why) }
    val touch = rememberTouch()
    val today = remember { LocalDate.now().toEpochDay() }
    FormSheet(
        title = "Редагувати товар",
        confirmLabel = "Зберегти",
        confirmEnabled = true,
        onConfirm = {
            val typed = parseAmount(price)
            // A price the user typed is the deliberate fallback for a page that
            // cannot be read, so it is marked as hand-entered rather than passed off
            // as a reading: the card then stops promising it is being watched.
            val priced = if (typed > 0.0 && typed != wish.price) {
                wish.copy(
                    price = typed,
                    history = appendPrice(wish.history, typed, today),
                    checkedDay = today,
                    freshness = Freshness.MANUAL
                )
            } else {
                wish
            }
            touch.landed()
            save(
                priced.copy(
                    name = name.ifBlank { wish.name },
                    targetPrice = parseAmount(target),
                    category = canonicalCategory(category, known),
                    why = why.trim().take(WHY_LIMIT)
                )
            )
        },
        onDismiss = close
    ) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Назва") })
        NumberField("Ціна, ₴", price) { price = it }
        if (isStale(wish.freshness)) {
            Text(
                "Сторінка зараз не читається. Вписана вручну ціна лишає бажання живим — " +
                    "план накопичення і ціль працюють далі.",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.sm)
            )
        }
        NumberField("Цільова ціна, ₴", target) { target = it }
        // A target picked from what the price has actually done, rather than from
        // the air: the way CamelCamelCamel and Google's typical-price range offer it.
        val suggestions = targetSuggestions(wish.history, wish.price, today)
        if (suggestions.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = Space.sm),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                suggestions.forEach { suggestion ->
                    FilterChip(
                        selected = parseAmount(target) == suggestion.price,
                        onClick = { target = amountText(suggestion.price) },
                        label = { Text("${suggestion.label} · ${money(suggestion.price)}") }
                    )
                }
            }
        }
        OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth().padding(top = Space.md), label = { Text("Категорія") })
        CategorySuggestions(known, category) { category = it }
        OutlinedTextField(
            why,
            { why = it.take(WHY_LIMIT) },
            Modifier.fillMaxWidth().padding(top = Space.md),
            label = { Text("Навіщо мені це — необов'язково") },
            maxLines = 3
        )
    }
}

/**
 * One shop behind a wish: what it is asking, and whether it is still answering.
 *
 * The cheapest is marked rather than sorted to the top, so the rows keep the order
 * they were added in and a shop does not jump about the card every time a sale
 * starts somewhere else.
 */
@Composable
fun SourceRow(
    source: WishSource,
    cheapest: Boolean,
    removable: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    val dead = isStale(source.freshness)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sourceName(source.url),
                        color = if (dead) TextSecondary else TextPrimary,
                        fontSize = Type.bodySize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (cheapest) {
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            "найдешевше",
                            color = Accent,
                            fontSize = Type.overlineSize,
                            fontWeight = Type.strong,
                            letterSpacing = Type.overlineTracking
                        )
                    }
                }
            }
            Text(
                sourcePriceLabel(source),
                color = when {
                    dead -> TextDisabled
                    cheapest -> Accent
                    else -> TextPrimary
                },
                fontSize = Type.bodySize,
                fontWeight = Type.strong
            )
            if (removable) {
                IconButton(onRemove, Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        "Прибрати магазин",
                        tint = TextDisabled,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        // Why this row reads the way it does: a dead page, money the app has no
        // rate for, or a shop that has said outright it has the thing. Silent on the
        // ordinary case, which is most rows most of the time.
        sourceStockNote(source)?.let { note ->
            Text(
                note,
                color = if (dead) Negative else TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
        // The working behind a converted figure, so "2 480 ₴" can be checked
        // against what the shop actually printed.
        if (!dead) {
            convertedPriceLine(
                PriceInUah(
                    uah = source.price,
                    amount = source.amount,
                    currency = source.currency,
                    rate = source.rate,
                    noRate = false
                )
            )?.let { line ->
                Text(
                    line,
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine,
                    modifier = Modifier.padding(top = Space.xs)
                )
            }
        }
    }
}

/**
 * Where to look when the thing cannot be bought where it is being watched.
 *
 * **Why it sits under the price rather than at the bottom of the page.** He
 * pictured scrolling down to it, and said in the same breath that he did not know
 * where it would fit. The constraint that decides it is not layout but timing: the
 * question "who else has it" is formed by reading «Магазин зараз не продає це»,
 * and that sentence is the caption under the price at the top of the screen. A
 * button four sections below it is a button he has to already know about, and the
 * one moment it would have been useful is the moment he closed the page. So it
 * goes where the bad news is delivered, which is also the only place on this
 * screen that is guaranteed to be on his display when the question occurs to him.
 *
 * **Why the query is shown and not merely sent.** A search with the wrong words
 * comes back with junk and gives no hint whose fault that was — he would be left
 * deciding whether hotline has the thing or the app asked it the wrong question.
 * Printing the words turns a black box into something with a visible mistake in
 * it, and making them editable turns a visible mistake into a fixed one. The fix
 * is stored on the wish, so it holds.
 */
@Composable
fun ElsewhereCard(
    wish: Wish,
    terms: String,
    onTerms: (String) -> Unit,
    onSearch: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(top = Space.lg).litEdge(Radius.md),
        colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
        shape = Radius.md
    ) {
        Column(Modifier.padding(Space.lg)) {
            Text(
                compareNote(wish),
                fontSize = Type.bodySize,
                lineHeight = Type.bodyLine
            )
            OutlinedTextField(
                terms,
                onTerms,
                Modifier.fillMaxWidth().padding(top = Space.md),
                label = { Text("Що шукати") },
                singleLine = true
            )
            Text(
                "Запит можна виправити — він збережеться для цього бажання.",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.sm)
            )
            OutlinedButton(
                onSearch,
                Modifier.fillMaxWidth().padding(top = Space.md),
                enabled = terms.isNotBlank(),
                shape = Radius.sm,
                border = BorderStroke(1.dp, HairLine),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) { Text("Шукати на Hotline ↗") }
        }
    }
}

/**
 * Adds a second shop for the same thing.
 *
 * The page is read before the shop is kept, because a source that cannot produce
 * a price is not a source — it is a link, and the wish already has one of those
 * that works. Better to say so here than to add a row that sits empty for ever.
 */
@Composable
fun AddSourceSheet(
    wish: Wish,
    rate: FxRate,
    close: () -> Unit,
    add: (WishSource) -> Unit
) {
    var link by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now().toEpochDay() }

    FormSheet(
        title = "Ще один магазин",
        confirmLabel = if (loading) "Зчитую…" else "Додати",
        confirmEnabled = isSupportedWebUrl(link) && !loading,
        onConfirm = {
            scope.launch {
                loading = true
                error = null
                if (hasSource(wish, link)) {
                    error = "Цей магазин уже в списку"
                } else {
                    runCatching { pricedPageHtml(link) }
                        .onSuccess { html ->
                            val offer = extractOffers(html).firstOrNull()
                            val converted = offer?.let { toHryvnia(it.price, it.currency, rate) }
                            when {
                                // The same two outcomes the add sheet now tells
                                // apart, and they ask for different things here
                                // too: one means check the link, the other means
                                // this shop is no use as a second opinion. Typing
                                // a price is deliberately not offered — a shop
                                // exists on a wish to be read, and this wish
                                // already has one that answers.
                                offer == null || converted == null ->
                                    error = if (pageFacts(html).describable) {
                                        "Сторінку прочитав, але ціни на ній немає. " +
                                            "Другий магазин потрібен саме заради ціни."
                                    } else {
                                        NOTHING_READ_NOTE
                                    }
                                converted.noRate ->
                                    error = "Ціна в ${converted.currency} — курсу до гривні немає"
                                else -> add(
                                    WishSource(
                                        url = link.trim(),
                                        price = converted.uah,
                                        variant = offer.label,
                                        freshness = Freshness.OK,
                                        checkedDay = today,
                                        amount = converted.amount,
                                        currency = converted.currency,
                                        rate = converted.rate
                                    )
                                )
                            }
                        }
                        .onFailure { error = it.message ?: "Не вдалося прочитати сторінку" }
                }
                loading = false
            }
        },
        onDismiss = close
    ) {
        Text(
            "Те саме у другому магазині. FlowPay читатиме обидві сторінки й " +
                "показуватиме ту ціну, що зараз нижча.",
            color = TextSecondary,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine
        )
        OutlinedTextField(
            link,
            { link = it },
            Modifier.fillMaxWidth().padding(top = Space.md),
            label = { Text("Посилання на товар") }
        )
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Space.sm)
            )
        }
    }
}

/**
 * A heading that sits closer to its own content than to whatever came before.
 *
 * [icon] is optional and most headings do without one. Where it is given, it is one
 * mark at the head of a whole section rather than one per row: an eye scanning a
 * long page finds a section by its shape, and that is the entire job being asked of
 * it. The same mark repeated down every row of the section would be nine more
 * things to look at on a page whose complaint is that there is too much to look at
 * — decoration is density too.
 *
 * Drawn in [TextSecondary] rather than in the accent, because the accent on this
 * app means "this is the thing", and a heading is a signpost, not the thing.
 */
@Composable
fun SectionTitle(text: String, icon: ImageVector? = null) {
    Row(
        Modifier.padding(horizontal = Space.screen).padding(top = Space.xxl, bottom = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(Space.lg), tint = TextSecondary)
            Spacer(Modifier.width(Space.md))
        }
        Text(
            text,
            fontSize = Type.sectionSize,
            lineHeight = Type.sectionLine,
            fontWeight = Type.medium
        )
    }
}

/**
 * The names the folded state of each section is stored under.
 *
 * Plain ASCII and written out rather than derived from the heading: the headings
 * are Ukrainian, and a key built by lowercasing one would be a locale-sensitive
 * transformation standing between a person's choice and the preference that
 * remembers it.
 */
const val SECTION_HISTORY = "history"
const val SECTION_SHOPS = "shops"
const val SECTION_PLAN = "plan"

/**
 * The bin's fold on «Огляд», stored like the wish page's folds and for a stronger
 * reason than any of them.
 *
 * «Огляд» exists to answer "how am I doing", and the bin answers nothing of the
 * kind: it is a safety net, and a safety net earns its place on the one day you
 * deleted something by mistake and on no other. Rendered open it put four cards of
 * things the person had already decided they did not want between the month's
 * figures and the settings underneath them.
 *
 * So it is shut by default — which [Store.sectionOpen] already is for a key it has
 * never been asked about — and the day it is needed it stays open until it is shut
 * again.
 */
const val SECTION_BIN = "bin"

/**
 * The two folds inside «Про товар», stored the same way for the same reason.
 *
 * They are not [CollapsibleSection]s — see [CardFold] — but the choice they
 * record is identical in kind: "do I read the shop's blurb when I open a thing",
 * answered once for the app rather than once per pair of headphones.
 */
const val SECTION_ABOUT_TEXT = "abouttext"
const val SECTION_ABOUT_SPECS = "aboutspecs"

/**
 * A heading that can fold its own content away, and says what is inside while shut.
 *
 * The wish page was a wall: everything it knows, expanded, in one column, with the
 * description of the thing itself buried below a block of savings arithmetic. The
 * fix is not only to fold the long parts away, because a row reading "Історія
 * ціни ⌄" and nothing else makes you open all three to find out which one holds
 * what you came for — which is the wall again, with taps in front of it. So the
 * [summary] is not decoration: it is the reason a closed section is allowed to be
 * closed, and it must be a figure the screen has already worked out rather than a
 * new one invented here.
 *
 * [open] is hoisted, because the answer outlives the screen — see [Store.sectionOpen].
 *
 * **No haptic, and that is on purpose.** Haptics.kt sets the budget at five and
 * spends none of it on an ordinary tap: a vibration on every tap stops being
 * feedback within a day and becomes the texture of the app. `switched` is defined
 * for a real two-state change the person made to their own data — a payment
 * marked paid, a wish put on hold — and folding a heading is neither. It changes
 * nothing but the view, and the screen answers the tap completely and instantly by
 * opening. That is also the argument the three-way chart switch on this same page
 * already makes for staying silent, and this control will end up on more screens
 * than that one, so a buzz here would be the fastest route to exactly the texture
 * Haptics.kt is written against.
 */
@Composable
fun CollapsibleSection(
    title: String,
    summary: String,
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    /** One mark at the head of the section, on the same terms as [SectionTitle]. */
    icon: ImageVector? = null,
    content: @Composable () -> Unit
) {
    // The chevron is the same gesture as the opening, so it rides the same spring
    // rather than a second one of its own — and Motion is what makes both of them
    // snap when the phone has been told to stop animating.
    val turn by animateFloatAsState(
        if (open) 180f else 0f,
        Motion.spatial(),
        label = "section chevron"
    )
    Row(
        Modifier
            .fillMaxWidth()
            // Clickable before the padding, so the whole heading block is the
            // target rather than the two lines of text inside it.
            .clickable { onToggle(!open) }
            .padding(horizontal = Space.screen)
            .padding(top = Space.xxl, bottom = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(Space.lg), tint = TextSecondary)
            Spacer(Modifier.width(Space.md))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = Type.sectionSize,
                lineHeight = Type.sectionLine,
                fontWeight = Type.medium
            )
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine,
                    modifier = Modifier.padding(top = Space.xs)
                )
            }
        }
        Icon(
            Icons.Default.ExpandMore,
            if (open) "Згорнути" else "Розгорнути",
            Modifier.padding(start = Space.md).rotate(turn),
            tint = TextSecondary
        )
    }
    AnimatedVisibility(
        visible = open,
        // Height only. A section holding the price chart must not have the chart
        // fade or draw itself into existence behind the reveal — the thing has a
        // press-and-drag scrub on it, and a canvas that is still arriving is a
        // canvas that owes the finger an answer it cannot give yet. Anchored at the
        // top so the block grows downward out of its own heading instead of sliding
        // up from under whatever follows it.
        enter = expandVertically(Motion.spatial(), expandFrom = Alignment.Top),
        exit = shrinkVertically(Motion.spatial(), shrinkTowards = Alignment.Top)
    ) {
        content()
    }
}

/**
 * The same fold one level down: a heading *inside* a card rather than over one.
 *
 * [CollapsibleSection] was tried here first and does not fit, for three reasons
 * that are all about scale rather than taste. It carries [Space.screen] of its own
 * horizontal padding, so nested inside a card that is already inset by the screen
 * margin its heading sits twenty dp adrift of the rows it introduces. It sets its
 * title at [Type.sectionSize] — the same size as the «Про товар» heading standing
 * directly above the card — so the block would have two equal headings, one inside
 * the other, with nothing in the type to say which contains which. And its
 * [Space.xxl] top break is a gap between screen sections; spent inside a card it is
 * most of a phone's worth of empty.
 *
 * So this is a sibling rather than a reuse, and the whole of what it changes is
 * weight: one row instead of two, card-title size instead of section size, the
 * summary run on after a middle dot instead of set on a line of its own. It keeps
 * what actually mattered about the original — the summary on the shut heading, so
 * a closed fold still says what is in it and you are not opening things to find
 * out which one you wanted — and it keeps the motion, so both folds in the app
 * open with the same spring and both go still when the phone asks for no motion.
 *
 * No haptic here either, and for the identical reason: this changes the view and
 * nothing else, and the fold opening under the finger is already the whole answer.
 */
@Composable
fun CardFold(
    title: String,
    summary: String,
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    val turn by animateFloatAsState(
        if (open) 180f else 0f,
        Motion.spatial(),
        label = "card fold chevron"
    )
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!open) }.padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = TextPrimary,
            fontSize = Type.cardTitleSize,
            lineHeight = Type.cardTitleLine,
            fontWeight = Type.medium
        )
        if (summary.isNotBlank()) {
            Text(
                " · $summary",
                color = TextSecondary,
                fontSize = Type.captionSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Tabular
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Default.ExpandMore,
            if (open) "Згорнути" else "Розгорнути",
            Modifier.padding(start = Space.sm).size(20.dp).rotate(turn),
            tint = TextSecondary
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(Motion.spatial(), expandFrom = Alignment.Top),
        exit = shrinkVertically(Motion.spatial(), shrinkTowards = Alignment.Top)
    ) {
        content()
    }
}

/**
 * A run of the shop's own prose, cut to [ABOUT_CLAMP_LINES] with a way past the cut.
 *
 * A clamp rather than a fold, because these two states are not the same offer. A
 * shut heading says "there is a description, open it to find out whether you
 * wanted it"; four lines say what the thing is and let the rest be optional. The
 * block exists to answer "what is this", so the answer has to be on screen.
 *
 * The «більше» appears only where the text is actually cut — Compose reports that
 * from the layout rather than from a character count, which is the only way to be
 * right about it at every text size the phone offers. A short description gets no
 * affordance at all, because there is nothing behind it.
 */
@Composable
fun ClampedText(text: String, expanded: Boolean, onToggle: (Boolean) -> Unit) {
    // Whether the clamp is hiding anything. Measured while shut and then left
    // alone: once open there is no overflow to see, and re-measuring would take
    // the «менше» away at exactly the moment it is the only way back.
    var clipped by remember(text) { mutableStateOf(false) }
    // A description that fits is not a control. Without this the row is tappable,
    // a tap does nothing visible, and «менше» appears over text with nothing
    // behind it.
    val offersMore = clipped || expanded
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (offersMore) Modifier.clickable { onToggle(!expanded) } else Modifier)
    ) {
        Text(
            text,
            color = TextSecondary,
            fontSize = Type.bodySize,
            lineHeight = Type.bodyLine,
            maxLines = if (expanded) Int.MAX_VALUE else ABOUT_CLAMP_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> if (!expanded) clipped = layout.hasVisualOverflow }
        )
        if (offersMore) {
            Text(
                moreLabel(expanded),
                color = Accent,
                fontSize = Type.captionSize,
                fontWeight = Type.medium,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
    }
}

/** One number of the savings plan, muted while there is nothing to show yet. */
@Composable
fun PlanTile(label: String, value: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Card(
        modifier.litEdge(Radius.sm),
        colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
        shape = Radius.sm
    ) {
        Column(Modifier.padding(Space.lg)) {
            // Only ever a savings plan, which is the owner's own money (Privacy.kt).
            Text(personal(label), color = TextSecondary, fontSize = Type.captionSize)
            Spacer(Modifier.height(Space.xs))
            Text(
                personal(value),
                fontSize = Type.sectionSize,
                lineHeight = Type.sectionLine,
                fontWeight = if (muted) Type.regular else Type.strong,
                color = if (muted) TextDisabled else TextPrimary,
                style = Tabular
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
/** The price history as it was paid for: hryvnia, the only view always available. */
const val CHART_HRYVNIA = 0

/** The same history in dollars, dropping the readings that carry no rate. */
const val CHART_DOLLAR = 1

/** Price and rate together, both set to 100 at the first reading that had a rate. */
const val CHART_REBASED = 2

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
    onBought: (tracking: String, paid: Double) -> Unit,
    moneyHost: MoneyHost? = null
) {
    // The wish as it is now, for the work that lands seconds after it began. The
    // parameter seen inside a coroutine is the one from the frame it started in,
    // and building the saved wish from that reverted whatever was typed meanwhile.
    val latest by rememberUpdatedState(wish)
    // Keyed on the item, so opening a different one does not inherit these boxes.
    var savedText by remember(wish.id) { mutableStateOf(amountText(wish.saved)) }
    var monthlyText by remember(wish.id) { mutableStateOf(amountText(wish.monthlyPlan)) }
    var deadlineDay by remember(wish.id) { mutableLongStateOf(wish.deadline) }
    // Seeded from whatever this wish will actually be searched with, which is the
    // built query until the day he changes it and his own words afterwards.
    var searchText by remember(wish.id) { mutableStateOf(wishSearchTerms(wish)) }
    // Which end of the plan is known: the monthly sum, or the date.
    var byDate by remember(wish.id) { mutableStateOf(wish.deadline > 0L) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingHold by remember { mutableStateOf(false) }
    var addingSource by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf(false) }
    // «А якщо куплю зараз?» — see Afford.kt.
    var affording by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // Which of the three views of the history is showing: hryvnia, dollars, or
    // the two rebased against the rate. Hryvnia until asked otherwise — the other
    // two answer a question, and a chart that opens on one is answering a question
    // nobody asked.
    //
    // No haptic on changing it. Picking one of three views is a selection, not a
    // switch, and ToggleOn/ToggleOff would be claiming a two-state answer for a
    // control that has three — the same reason the delivery rail says nothing.
    var chartView by remember(wish.id) { mutableIntStateOf(CHART_HRYVNIA) }
    val touch = rememberTouch()
    val scope = rememberCoroutineScope()
    val store = remember(context) { Store(context) }

    // Whether each of the three detail sections below is unfolded. Seeded from the
    // preference and written back on every tap, so the choice carries to the next
    // wish opened rather than resetting with the screen. Shut by default: relieving
    // the wall is the whole point, and a default of open is the wall.
    var historyOpen by remember { mutableStateOf(store.sectionOpen(SECTION_HISTORY)) }
    var shopsOpen by remember { mutableStateOf(store.sectionOpen(SECTION_SHOPS)) }
    var planOpen by remember { mutableStateOf(store.sectionOpen(SECTION_PLAN)) }
    // Kept the same way as the three above and shut by default for the same
    // reason: a section that arrives open is the wall this page spent a release
    // folding down, and this one is also the only section that can spend money.
    var appraisalOpen by remember { mutableStateOf(store.sectionOpen(SECTION_APPRAISAL)) }
    // The two inside «Про товар», kept the same way and for the same reason:
    // whether the blurb is worth reading in full is a fact about the reader, not
    // about a pair of headphones, so it is not keyed on the wish.
    var aboutTextOpen by remember { mutableStateOf(store.sectionOpen(SECTION_ABOUT_TEXT)) }
    var specsOpen by remember { mutableStateOf(store.sectionOpen(SECTION_ABOUT_SPECS)) }

    val today = remember { LocalDate.now() }
    // «У мене є Картка Rozetka» — read on each opening, so a switch flipped in
    // Налаштування reaches the next wish opened.
    val hasCard = remember(wish.id) { PriceStore(context).rozetkaCard() }
    // The day the stored rate was fetched, so a converted price can say how old
    // the rate behind it is. Zero until a rate has ever been loaded.
    val rateDay = remember {
        store.fxRate().second.takeIf { it > 0L }
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay() }
            ?: 0L
    }
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
    val stale = isStale(wish.freshness)
    // A verdict is a claim about a price that can be paid. While the reading is
    // doubtful there is no such price, so the app says nothing rather than judging
    // a figure the shop has stopped standing behind.
    val verdict = if (stale) BuyVerdict.UNKNOWN else insight.verdict
    val held = onHold(wish, today.toEpochDay())

    // What each folded heading says while it is shut. Every figure is one the screen
    // has already worked out: a summary that could disagree with the block under it
    // would be worse than no summary at all.
    val historySummary = when {
        stale -> "Ціна не читається"
        insight.changes < 1 -> "Змін ціни ще не було"
        insight.atReferenceLow -> "Найнижча за ${daysLabel(insight.referenceDays)}"
        else -> "${signedPercent(change)} від першої ціни"
    }
    val shopsSummary = run {
        val watched = wishSources(wish)
        val cheapest = bestSource(watched)
        when {
            watched.isEmpty() -> "Магазинів ще немає"
            cheapest != null -> "${shopsLabel(watched.size)} · від ${money(cheapest.price)}"
            else -> "${shopsLabel(watched.size)} · ціни не читаються"
        }
    }
    // The same five cases the tiles inside the section choose between, in one line.
    val planSummary = when {
        held && !plan.reached -> "Бажання відкладене — план зараз не рахується"
        wish.skipMonth == monthKey(today) && !plan.reached -> "Пропущено цього місяця"
        plan.reached -> "Сума зібрана"
        byDate && deadlineDate == null -> "Дату покупки ще не обрано"
        byDate && monthsLeft == 0 -> "Потрібно ${money(plan.remaining)} одразу"
        plan.needsRate -> "Щомісячну суму ще не вказано"
        else -> "${money(plan.monthly)} на місяць · ${monthsLabel(plan.months)}"
    }

    // Only a query that differs from the built one is stored. Typing the app's own
    // suggestion back in leaves the field empty, so a later improvement to
    // [searchTerms] still reaches this wish — a wish is pinned to his words only
    // where they are his.
    LaunchedEffect(searchText) {
        val typed = searchText.trim()
        val kept = if (typed == builtSearchTerms(wish)) "" else typed
        if (kept != wish.searchQuery) onChange(wish.copy(searchQuery = kept))
    }

    // A jar's balance arrives from a monobank pass; the field follows it, so the
    // next edit to the plan does not write the old figure back over the jar's.
    LaunchedEffect(wish.saved, wish.jar) {
        if (wish.jar.isNotBlank()) savedText = amountText(wish.saved)
    }

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
                    overlayNumber = signedPercent(change, 0)
                    .takeIf { change <= -1.0 && wish.history.size > 1 && !stale },
                    overlayNote = firstPriceNote(wish)
                        .takeIf { change <= -1.0 && wish.history.size > 1 && !stale },
                    chip = freshnessLabel(wish.freshness)
                        ?: verdictLabel(verdict).takeIf { verdict != BuyVerdict.UNKNOWN },
                    chipIcon = if (stale) Icons.Default.ErrorOutline else Icons.Default.Bolt,
                    chipColor = when {
                        stale -> Negative
                        wish.freshness == Freshness.MANUAL -> TextPrimary
                        verdict == BuyVerdict.GOOD -> Accent
                        verdict == BuyVerdict.POOR -> Negative
                        else -> TextPrimary
                    },
                    // The verdict flipping is the moment this whole screen exists
                    // for, so the chip changes outline rather than swapping words
                    // under a cross-fade.
                    chipCorners = buyVerdictCorners(insight.verdict)
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
            } else {
                // The same pastel square the card shows, so opening a wish with no
                // photo lands on the thing it came from rather than on nothing.
                EmojiHeader(wishEmoji(wish.name), tileColours(listOf(wish.id)).first())
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
                        valueSize = Type.heroSize,
                        color = if (stale) TextSecondary else TextPrimary
                    )
                    if (!stale) {
                        Spacer(Modifier.width(Space.md))
                        Text(
                            signedPercent(change),
                            color = if (change <= 0) Accent else Negative,
                            fontSize = Type.captionSize,
                            fontWeight = Type.strong,
                            modifier = Modifier.padding(bottom = Space.sm),
                            style = Tabular
                        )
                    }
                }
                // What the shop actually printed, when the figure above had to be
                // converted to get there. Without it a hryvnia figure and a
                // converted one look identical, and only one of them moves when
                // the currency does.
                bestSource(wishSources(wish))?.let { source ->
                    val converted = PriceInUah(
                        uah = source.price,
                        amount = source.amount,
                        currency = source.currency,
                        rate = source.rate,
                        noRate = false
                    )
                    convertedPriceLine(converted)?.let { line ->
                        Text(
                            line,
                            color = TextSecondary,
                            fontSize = Type.captionSize,
                            lineHeight = Type.captionLine,
                            modifier = Modifier.padding(top = Space.sm)
                        )
                    }
                    // A converted price is only as current as the rate under it,
                    // and the rate is fetched by the same pass that reads prices —
                    // so a phone that has been offline converts today's dollars at
                    // last week's hryvnia.
                    staleRateNote(converted, rateDay, today.toEpochDay())?.let { note ->
                        Text(
                            note,
                            color = TextSecondary,
                            fontSize = Type.captionSize,
                            modifier = Modifier.padding(top = Space.xs)
                        )
                    }
                }
                // The Rozetka card's price, only for an owner who holds the card.
                cardLine(wish, hasCard)?.let { PriceAside(it) }
                // The market on Hotline, when one is bound: a yardstick, not the price.
                MarketRow(wish, today.toEpochDay()) { openLink(context, it) }
                // Why the figure above is the colour it is, in one sentence. The
                // card can only carry a two-word badge; this is where it is explained.
                freshnessNote(wish.freshness)?.let { note ->
                    Text(
                        note,
                        color = if (stale) Negative else TextSecondary,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine,
                        modifier = Modifier.padding(top = Space.sm)
                    )
                }
                // How long this has been wanted. The one number a wishlist owes the
                // person keeping it, and the app had the date all along without
                // ever putting it on screen.
                wantedLabel(wish, today.toEpochDay())?.let { age ->
                    Text(
                        age,
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        modifier = Modifier.padding(top = Space.xs)
                    )
                }

                // Only where there is nothing to buy. See [ElsewhereCard] for why
                // it is here and not further down, and [cannotBeBought] for why it
                // is not on every wish: a price-comparison row on a thing that is
                // sitting in a shop right now is a row answering a question nobody
                // asked, on the screen he already called too crowded.
                if (worthComparing(wish)) {
                    ElsewhereCard(
                        wish = wish,
                        terms = searchText,
                        onTerms = { searchText = it },
                        onSearch = {
                            openLink(context, hotlineSearch(searchText))
                        }
                    )
                }
            }

            HoldBlock(
                wish = wish,
                today = today,
                onPick = { pickingHold = true },
                onRelease = {
                    touch.switched(false)
                    onChange(wish.copy(holdUntil = 0L))
                },
                onHoldFor = { days ->
                    touch.switched(true)
                    onChange(wish.copy(holdUntil = today.toEpochDay() + days))
                }
            )

            // Only where the shop actually said something. A heading over an
            // empty block is worse than no block: it reads as something broken
            // rather than as a shop that publishes nothing.
            if (!wish.about.isEmpty) {
                SectionTitle("Про товар")
                Column(Modifier.padding(horizontal = Space.screen)) {
                    Card(
                        Modifier.fillMaxWidth().litEdge(Radius.md),
                        colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
                        shape = Radius.md
                    ) {
                        Column(Modifier.padding(Space.lg)) {
                            if (wish.about.brand.isNotBlank() || wish.about.rating > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (wish.about.brand.isNotBlank()) {
                                        Text(
                                            wish.about.brand,
                                            color = Accent,
                                            fontSize = Type.captionSize,
                                            fontWeight = Type.strong
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    ratingLine(wish.about).takeIf { it.isNotBlank() }?.let {
                                        Text(it, color = TextSecondary, fontSize = Type.captionSize)
                                    }
                                }
                                Spacer(Modifier.height(Space.sm))
                            }
                            if (wish.about.description.isNotBlank()) {
                                ClampedText(wish.about.description, aboutTextOpen) {
                                    aboutTextOpen = it
                                    store.saveSectionOpen(SECTION_ABOUT_TEXT, it)
                                }
                            }
                            if (wish.about.specs.isNotEmpty()) {
                                Spacer(Modifier.height(Space.sm))
                                // A short table is left standing; a long one goes
                                // behind a heading that says how many rows it is.
                                // The count is the whole point of the closed state —
                                // «Характеристики · 14» is worth a tap and
                                // «Характеристики» on its own is a guess.
                                if (foldsSpecs(wish.about.specs.size)) {
                                    CardFold(
                                        title = "Характеристики",
                                        summary = specsCountLabel(wish.about.specs.size),
                                        open = specsOpen,
                                        onToggle = {
                                            specsOpen = it
                                            store.saveSectionOpen(SECTION_ABOUT_SPECS, it)
                                        }
                                    ) {
                                        Column {
                                            wish.about.specs.forEach { (name, value) ->
                                                LeaderRow(name, value)
                                            }
                                        }
                                    }
                                } else {
                                    wish.about.specs.forEach { (name, value) ->
                                        LeaderRow(name, value)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // After «Про товар» and before the chart, because the order of the
            // three questions is "what is this", "what do I make of it", "what has
            // it cost". Absent entirely — not folded, not hedged — for a wish the
            // app cannot honestly ask about, and absent in every build with no key,
            // which is every debug build. See [appraisalGate] for the whole rule.
            if (appraisalShows(wish, hasAppraisalKey(BuildConfig.GEMINI_KEY))) {
                AppraisalSection(
                    wish = wish,
                    insight = insight,
                    freeCash = freeCash,
                    today = today.toEpochDay(),
                    key = BuildConfig.GEMINI_KEY,
                    open = appraisalOpen,
                    onToggle = {
                        appraisalOpen = it
                        store.saveSectionOpen(SECTION_APPRAISAL, it)
                    },
                    onChange = onChange
                )
            }

            // The chart, the verdict and the notes under them. First of the three
            // because "what has this cost before now" is the question the page is
            // read for once you know what the thing is.
            CollapsibleSection(
                title = "Історія ціни",
                summary = historySummary,
                open = historyOpen,
                onToggle = { historyOpen = it; store.saveSectionOpen(SECTION_HISTORY, it) }
            ) {
                Column(Modifier.padding(horizontal = Space.screen)) {
                    Card(
                        Modifier.fillMaxWidth().litEdge(Radius.md),
                        colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
                        shape = Radius.md
                    ) {
                        Column(Modifier.padding(Space.lg)) {
                            // The range bar goes first because it is the densest thing
                            // on the screen: where today sits between the cheapest and
                            // the dearest ever seen, with the usual thirty days shaded
                            // behind it, read without an axis and without a sentence.
                            //
                            // Gone while the reading is doubtful, for the same reason
                            // the verdict below goes quiet: placing a price the shop has
                            // stopped standing behind would be a picture of a claim the
                            // next paragraph refuses to make in words.
                            if (!stale && insight.highest > insight.lowest) {
                                PriceRangeBar(insight)
                                Spacer(Modifier.height(Space.lg))
                            }
                            val usdPoints = remember(wish.history) { inDollars(wish.history) }
                            // Offered only once two points carry a rate. One converted
                            // point is a number, not a history, and the other two views
                            // would draw a single dot saying nothing about direction.
                            val twoCurrencies = hasDollarHistory(wish.history)
                            val view = if (twoCurrencies) chartView else CHART_HRYVNIA
                            if (twoCurrencies) {
                                // One track with one marker, rather than three chips
                                // that each look independently switchable.
                                SegmentedControl(
                                    listOf("₴", "$", "Ціна і курс"),
                                    view
                                ) { chartView = it }
                                Spacer(Modifier.height(Space.md))
                            }
                            when (view) {
                                // Both lines set to 100 at the first reading: the one
                                // picture that separates a thing getting dearer from
                                // the hryvnia moving underneath it.
                                CHART_REBASED -> RebasedPriceAndRate(wish.history)
                                CHART_DOLLAR -> PriceChart(usdPoints, format = ::dollars)
                                // The step line breaks where the thing was sold out,
                                // and a point the scrub ends on can be set aside as a
                                // shop's glitch — see PricesUi.kt.
                                else -> WishHistoryChart(
                                    wish,
                                    // Every chart here is drawn on its own scale, so two
                                    // of them side by side cannot be compared by eye.
                                    // This figure is what makes them comparable, and it
                                    // is the price of being allowed a per-item scale at
                                    // all. It measures from the left edge of the chart,
                                    // which is the first price ever recorded. Withheld
                                    // while the reading is doubtful, like everything else
                                    // on this card that depends on the price being real.
                                    note = "від першої ціни ${signedPercent(change)}"
                                        .takeIf { wish.history.isNotEmpty() && !stale },
                                    onChange = onChange
                                )
                            }
                            // «Схоже на збій» and the points already set aside.
                            GlitchNotes(wish, onChange)
                            if (view == CHART_DOLLAR && usdPoints.isNotEmpty()) {
                                Text(
                                    "Зараз ${dollars(usdPoints.last().price)} " +
                                        "· курс записано з кожною ціною",
                                    color = TextSecondary,
                                    fontSize = Type.captionSize,
                                    modifier = Modifier.padding(top = Space.sm)
                                )
                            }
                            if (twoCurrencies) {
                                // The line the two-currency history exists to write: a
                                // flat hryvnia price that has quietly got cheaper, or a
                                // rise that was only ever the rate moving.
                                currencyMoveNote(wish.history)?.let { note ->
                                    Text(
                                        note,
                                        color = TextSecondary,
                                        fontSize = Type.captionSize,
                                        lineHeight = Type.captionLine,
                                        modifier = Modifier.padding(top = Space.sm)
                                    )
                                }
                            }
                            Spacer(Modifier.height(Space.md))
                            Text(
                                verdictLabel(verdict),
                                color = when (verdict) {
                                    BuyVerdict.GOOD -> Accent
                                    BuyVerdict.POOR -> Negative
                                    else -> TextSecondary
                                },
                                fontSize = Type.cardTitleSize,
                                fontWeight = Type.medium
                            )
                            Text(
                                when {
                                    stale -> "Поки ціна не читається, оцінювати нічого"
                                    // The rule in [priceInsight] is a week and two
                                    // recorded prices; this used to promise a fortnight.
                                    verdict == BuyVerdict.UNKNOWN ->
                                        "Потрібно щонайменше тиждень спостережень і дві зміни ціни"
                                    verdict == BuyVerdict.GOOD ->
                                        if (insight.atReferenceLow)
                                            "Це найнижча ціна за останні ${daysLabel(insight.referenceDays)}"
                                        else "Ціна в нижній частині діапазону останніх ${daysLabel(insight.referenceDays)}"
                                    verdict == BuyVerdict.FAIR ->
                                        "Ціна в середині діапазону останніх ${daysLabel(insight.referenceDays)}"
                                    // The low itself, and how far above it today is.
                                    // The percentage used to be the distance from the
                                    // window's high — 7% where the truth was 29%, and
                                    // «на 0% нижче» at the top of the range.
                                    else ->
                                        "За останні ${daysLabel(insight.referenceDays)} ціна опускалась " +
                                            "до ${money(insight.referenceLow)} — зараз на " +
                                            "${figure(overLowPercent(insight), 0)}% дорожче"
                                },
                                color = TextSecondary,
                                fontSize = Type.captionSize,
                                lineHeight = Type.captionLine,
                                modifier = Modifier.padding(top = Space.xs)
                            )
                            // The shop's own discount, checked against the app's record of
                            // what the price actually was before it. This is the figure EU
                            // law makes a shop quote, and the reason the rule exists.
                            //
                            // When the page itself crosses a figure out, that claim is
                            // checked instead — see Discounts.kt — and the app's own
                            // inference stays quiet so the two are not said twice.
                            if (!stale) {
                                val listPrice = bestSource(wishSources(wish))?.listPrice ?: 0.0
                                val todayDay = today.toEpochDay()
                                val shopClaim = shopDiscountNote(listPrice, wish.price, wish.history, todayDay)
                                (shopClaim ?: priorLowNote(insight))?.let { claim ->
                                    Text(
                                        claim,
                                        color = if (
                                            shopClaim != null &&
                                            discountIsReal(listPrice, wish.price, wish.history, todayDay)
                                        ) {
                                            TextPrimary
                                        } else {
                                            Negative
                                        },
                                        fontSize = Type.captionSize,
                                        lineHeight = Type.captionLine,
                                        modifier = Modifier.padding(top = Space.sm)
                                    )
                                }
                                blackFridayNote(today, insight.daysTracked)?.let { note ->
                                    Text(
                                        note,
                                        color = TextSecondary,
                                        fontSize = Type.captionSize,
                                        lineHeight = Type.captionLine,
                                        modifier = Modifier.padding(top = Space.sm)
                                    )
                                }
                            }
                            // The reference the verdict is actually measured against, as a
                            // figure rather than a description of one.
                            referenceWindowNote(insight)?.let { reference ->
                                Text(
                                    reference,
                                    color = TextPrimary,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine,
                                    fontWeight = Type.medium,
                                    modifier = Modifier.padding(top = Space.sm)
                                )
                            }
                            if (insight.changes > 1) {
                                val lowDay = lowestPointDay(wish.history)
                                Text(
                                    "За весь час: найнижча ${money(insight.lowest)}" +
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
                }
            }

            // Where the price comes from. Shown even for one shop, because that is
            // where the button to add a second one lives — and because a wish with
            // one shop that has stopped answering needs somewhere to say so that is
            // not the price itself.
            CollapsibleSection(
                title = "Де стежимо",
                summary = shopsSummary,
                open = shopsOpen,
                onToggle = { shopsOpen = it; store.saveSectionOpen(SECTION_SHOPS, it) }
            ) {
                Column(Modifier.padding(horizontal = Space.screen)) {
                    Card(
                        Modifier.fillMaxWidth().litEdge(Radius.md),
                        colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
                        shape = Radius.md
                    ) {
                        Column(Modifier.padding(Space.lg)) {
                            val sources = wishSources(wish)
                            val cheapest = bestSource(sources)
                            sources.forEachIndexed { index, source ->
                                if (index > 0) Spacer(Modifier.height(Space.md))
                                SourceRow(
                                    source = source,
                                    cheapest = cheapest != null && source.url == cheapest.url &&
                                        sources.size > 1,
                                    removable = sources.size > 1,
                                    onOpen = { openLink(context, source.url) },
                                    onRemove = { onChange(withoutSource(wish, source.url)) }
                                )
                            }
                            sourceSpreadNote(sources)?.let { spread ->
                                Text(
                                    spread,
                                    color = Accent,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine,
                                    modifier = Modifier.padding(top = Space.md)
                                )
                            }
                            TextButton(
                                { addingSource = true },
                                Modifier.padding(top = Space.sm)
                            ) { Text("Додати магазин") }
                            // The market on Hotline: bound here, read with the prices.
                            MarketShopRow(
                                wish,
                                onSearch = {
                                    openLink(context, hotlineSearch(searchText.ifBlank { wishSearchTerms(wish) }))
                                },
                                onOpen = { openLink(context, it) },
                                onUnbind = { onChange(wish.copy(market = null)) }
                            )
                        }
                    }
                }
            }

            // Last, because it is the longest block on the page and the least often
            // the reason for opening it.
            CollapsibleSection(
                title = "План накопичення",
                summary = personal(planSummary),
                open = planOpen,
                onToggle = { planOpen = it; store.saveSectionOpen(SECTION_PLAN, it) }
            ) {
                Column(Modifier.padding(horizontal = Space.screen)) {
                    HeroPanel(
                        label = if (plan.reached) "Сума зібрана" else "Залишилось зібрати",
                        // Savings are the owner's; the goal beside them would give them away.
                        value = personalFigure(money(plan.remaining)),
                        caption = personal("${money(plan.saved)} з ${money(plan.goal)}"),
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

                    // Kept in a monobank jar: the jar is what has been saved.
                    JarLink(
                        wish,
                        onLink = { jar, fromJar ->
                            val saved = fromJar ?: wish.saved
                            savedText = amountText(saved)
                            onChange(wish.copy(jar = jar, saved = saved))
                        },
                        onUnlink = { onChange(wish.copy(jar = "")) }
                    )
                    if (wish.jar.isBlank()) {
                        PersonalNumberField("Вже відкладено, ₴", savedText) { savedText = it }
                    }

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
                                    // The same «Вільно» as Огляд: after life too, when it is on.
                                    personal(
                                        (if ((moneyHost?.plan?.month?.life ?: 0.0) > 0.0) "Вільно після платежів і життя " else "Вільно після витрат ") +
                                            "${money(freeCash)} на місяць"
                                    ),
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
                        PersonalNumberField("Відкладаю щомісяця, ₴", monthlyText) { monthlyText = it }
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
                            // A date already behind is not «less than a month away».
                            if (deadlineDate?.isAfter(today) == true) "Менше місяця до дати" else "Дата вже минула",
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

                    // A held wish asks nothing of the month — the app does not nag
                    // about what was decided (HANDOFF §12) — and says so here. Any
                    // other plan can skip this month: «Пропустити», with its price.
                    if (held && !plan.reached) {
                        Text(
                            "Поки бажання відкладене, його план не входить у «Плани не сходяться» " +
                                "і не зменшує «Подарунок собі».",
                            Modifier.padding(top = Space.md),
                            color = TextSecondary,
                            fontSize = Type.captionSize,
                            lineHeight = Type.captionLine
                        )
                    } else if (moneyHost != null) {
                        WishSkipRow(wish, today, onChange)
                    }
                }
            }

            // Outside every fold, because an action that can be folded away is an
            // action nobody finds. It used to live inside the price history, which
            // is now a section that spends most of its life shut.
            Column(Modifier.padding(horizontal = Space.screen)) {
                Spacer(Modifier.height(Space.xl))
                // Buying is what the whole page is for, so it gets the filled button
                // and the full width. Everything else here is secondary — except on
                // a wish deliberately on hold, where a one-tap buy is the thing the
                // hold was set up to stand in the way of, so it goes quiet instead.
                if (held) {
                    OutlinedButton(
                        { buying = true },
                        Modifier.fillMaxWidth(),
                        shape = Radius.sm,
                        border = BorderStroke(1.dp, HairLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Icon(Icons.Default.ShoppingCartCheckout, null)
                        Text("  Я купив це")
                    }
                } else {
                    Button(
                        { buying = true },
                        Modifier.fillMaxWidth(),
                        shape = Radius.sm
                    ) {
                        Icon(Icons.Default.ShoppingCartCheckout, null)
                        Text("  Я купив це")
                    }
                }
                // What buying it now would do to the money — before it is done.
                if (moneyHost != null && wish.price > 0.0) {
                    TextButton({ affording = true }, Modifier.fillMaxWidth()) {
                        Text("А якщо куплю зараз?", color = TextPrimary)
                    }
                }
                Spacer(Modifier.height(Space.md))
                // Three in a row since «Hotline ↗» joined (4 October): tighter gaps,
                // tighter insides and one-word labels, so all three fit a 360 dp
                // phone without a label wrapping — and where a larger font would
                // not let them, [ActionsRow] puts the third under the other two.
                // Checked on the JVM render (PricesShots).
                ActionsRow(gap = Space.sm) {
                    OutlinedButton(
                        {
                            scope.launch {
                                refreshing = true
                                message = null
                                val started = wish
                                val reading =
                                    refreshed(started, today.toEpochDay(), store.fxRate().first)
                                // Edited while the page was being read: the edit wins,
                                // and the reading is not laid over it. Asking again
                                // is one tap; retyping a figure is not.
                                if (latest != started && reading !is Reading.Failed) {
                                    refreshing = false
                                    message = "Бажання змінилось, поки читалась сторінка — оновіть ще раз"
                                    return@launch
                                }
                                when (reading) {
                                    is Reading.Priced -> {
                                        // The one moment on this screen worth a
                                        // haptic: you asked, and the price you were
                                        // waiting for is the answer. Only on the
                                        // crossing — a price already under its
                                        // target before the tap is not news, and
                                        // buzzing for it would make every refresh
                                        // of a reached wish feel like an event.
                                        if (targetHit(reading.wish, today.toEpochDay()) &&
                                            !targetHit(wish, today.toEpochDay())
                                        ) {
                                            touch.landed()
                                        }
                                        onChange(reading.wish)
                                        message = "Ціну оновлено"
                                    }
                                    // The wish is saved even though no price came
                                    // back: the new freshness is itself the news.
                                    is Reading.Stale -> {
                                        onChange(reading.wish)
                                        message = freshnessNote(reading.wish.freshness)
                                    }
                                    Reading.Failed -> {
                                        // You asked and the network refused. This is
                                        // the other half of the pair above, and it
                                        // fires only because the refusal answers a
                                        // tap: the background pass that reads the
                                        // same page on a schedule stays silent.
                                        touch.refused()
                                        message = "Не вдалося прочитати сторінку"
                                    }
                                }
                                refreshing = false
                            }
                        },
                        Modifier,
                        enabled = !refreshing,
                        shape = Radius.sm,
                        border = BorderStroke(1.dp, HairLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        contentPadding = CompactButtonPadding
                    ) {
                        if (refreshing) {
                            BusyMark()
                        } else {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        }
                        Text(" Оновити", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        { openLink(context, wish.url) },
                        Modifier,
                        shape = Radius.sm,
                        border = BorderStroke(1.dp, HairLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        contentPadding = CompactButtonPadding
                    ) { Text("Магазин ↗", maxLines = 1, softWrap = false) }
                    // Any wish, in one tap: its bound Hotline page, or the Hotline
                    // search with the wish's own query — the same words the card
                    // for a thing nobody sells uses. See [hotlineLink].
                    OutlinedButton(
                        { openLink(context, hotlineLink(wish, searchText.ifBlank { wishSearchTerms(wish) })) },
                        Modifier,
                        shape = Radius.sm,
                        border = BorderStroke(1.dp, HairLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        contentPadding = CompactButtonPadding
                    ) { Text("Hotline ↗", maxLines = 1, softWrap = false) }
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

    if (affording && moneyHost != null) {
        AffordSheet(
            moneyHost,
            initialName = wish.name,
            initialPrice = bestSource(wishSources(wish))?.price?.takeIf { it > 0.0 } ?: wish.price,
            wishId = wish.id
        ) { affording = false }
    }

    if (addingSource) {
        AddSourceSheet(wish, store.fxRate().first, { addingSource = false }) { source ->
            addingSource = false
            onChange(withSource(latest, source))
        }
    }

    if (buying) {
        BoughtSheet(wish, { buying = false }) { trackingNumber, paid ->
            buying = false
            onBought(trackingNumber, paid)
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

    if (pickingHold) {
        val millisPerDay = 86_400_000L
        val state = rememberDatePickerState(
            // A month out by default: long enough for the urge to pass, short
            // enough that picking it does not feel like giving the thing up.
            initialSelectedDateMillis = today.plusMonths(1).toEpochDay() * millisPerDay,
            selectableDates = object : SelectableDates {
                // A hold that ended before it began is not a hold.
                override fun isSelectableDate(utcTimeMillis: Long) =
                    utcTimeMillis / millisPerDay > today.toEpochDay()
            }
        )
        DatePickerDialog(
            onDismissRequest = { pickingHold = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let {
                        // The hold going on, felt at the moment it does. Not on the
                        // button that opened this dialog — that only asked until
                        // when — and the matching answer comes when it is lifted.
                        touch.switched(true)
                        onChange(wish.copy(holdUntil = it / millisPerDay))
                    }
                    pickingHold = false
                }) { Text("Відкласти") }
            },
            dismissButton = { TextButton({ pickingHold = false }) { Text("Скасувати") } }
        ) {
            DatePicker(state)
        }
    }
}

/**
 * The deliberate pause, and the question waiting at the end of it.
 *
 * A wishlist's job is to put distance between the urge and the decision. The list
 * on its own does not do that — it keeps everything equally present for ever — so
 * this is the app using time as the tool: out of the way and silent until the day
 * comes, then back with the only question that matters.
 */
@Composable
fun HoldBlock(
    wish: Wish,
    today: LocalDate,
    onPick: () -> Unit,
    onRelease: () -> Unit,
    /** A pause of so many days from today, in one tap. */
    onHoldFor: (Long) -> Unit = {}
) {
    val day = today.toEpochDay()
    val held = onHold(wish, day)
    val ended = holdEnded(wish, day)
    if (!held && !ended) {
        Column(Modifier.padding(horizontal = Space.screen).padding(top = Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Snooze, null, Modifier.size(Space.lg), tint = TextSecondary)
                Text("  Відкласти", color = TextSecondary, fontSize = Type.captionSize)
            }
            // A day first. In the CHI 2019 study a twenty-five-hour delay lowered
            // both the urge and the intent to buy, and ten minutes did nothing —
            // so the shortest pause offered is the one that measurably works.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                HOLD_PRESETS.forEach { (label, days) ->
                    FilterChip(selected = false, onClick = { onHoldFor(days) }, label = { Text(label) })
                }
                FilterChip(selected = false, onClick = onPick, label = { Text("Дата…") })
            }
        }
        return
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(top = Space.lg).litEdge(Radius.sm),
        colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
        shape = Radius.sm
    ) {
        Column(Modifier.padding(Space.lg)) {
            Text(
                if (ended) "Ще хочеш?" else "Відкладено",
                fontSize = Type.cardTitleSize,
                fontWeight = Type.medium,
                color = if (ended) Accent else TextPrimary
            )
            Text(
                if (ended) {
                    // His own reason, read back at the moment of deciding.
                    if (wish.why.isNotBlank()) {
                        "Пауза скінчилась. Ти писав: «${wish.why}». Це досі так?"
                    } else {
                        "Пауза скінчилась. Якщо річ і досі потрібна — це вже рішення, а не порив."
                    }
                } else {
                    "Картка не турбуватиме до ${formatDate(LocalDate.ofEpochDay(wish.holdUntil))}."
                },
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )
            Row(
                Modifier.padding(top = Space.sm),
                horizontalArrangement = Arrangement.spacedBy(Space.md)
            ) {
                TextButton(onRelease, contentPadding = PaddingValues(0.dp)) {
                    Text(if (ended) "Так, хочу" else "Повернути в список")
                }
                TextButton(onPick, contentPadding = PaddingValues(0.dp)) {
                    Text(if (ended) "Ще почекаю" else "Інша дата", color = TextSecondary)
                }
            }
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
    today: Long,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    val change = priceChangePercent(wish)
    val plan = savingsPlan(wishGoal(wish), wish.saved, wish.monthlyPlan)
    val stale = isStale(wish.freshness)
    val held = onHold(wish, today)
    val holdText = holdLabel(wish, today)
    // Null wherever the price has never moved: a range bar with nothing to span
    // would draw a dot in the middle of a grey track, which on twenty cards is
    // twenty pieces of furniture saying nothing.
    val rangeInsight = remember(wish.history, wish.price, wish.checkedDay) {
        priceInsight(wish.history, wish.price, wish.checkedDay)
            .takeIf { it.highest > it.lowest }
    }
    // The card's own outline says whether the price you named has arrived. A held
    // wish is excluded on purpose: a hold is you telling the app not to raise this
    // until March, and a card that reshaped itself to say "now!" would be the app
    // overruling the one decision the hold exists to protect.
    val reached = targetHit(wish, today) && !held
    // One shape, handed to both the card and its lit edge. The edge is a border
    // that follows whatever outline it is given — that is the whole reason it is a
    // border rather than a line across the top — so a card clipped to the morph
    // while its edge still traced an 18dp rectangle would have the hairline cutting
    // across the opened corners and hanging off them, which is exactly the failure
    // litEdge was shaped to avoid.
    val cardShape = wishCardShape(reached)
    val press = remember { MutableInteractionSource() }
    Card(
        onClick = onOpen,
        interactionSource = press,
        modifier = modifier.pressScale(press).fillMaxWidth().litEdge(cardShape),
        colors = CardDefaults.cardColors(containerColor = SurfaceBase),
        shape = cardShape
    ) {
        Box {
            if (wish.image.isNotBlank()) {
                AsyncImage(
                    crossfadeImage(wish.image),
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
                        // A held wish is faded rather than hidden: it should read as
                        // set aside on purpose, not as something the app has lost.
                        .background(
                            AppBackground.copy(alpha = if (held || stale) 0.55f else 0.14f)
                        )
                )
            } else {
                // No photo yet: a pastel square with what the thing is, instead of
                // an empty grey one that reads as a picture that failed to load.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(tileColours(listOf(wish.id)).first()),
                    contentAlignment = Alignment.Center
                ) {
                    EmojiGlyph(wishEmoji(wish.name), 56.dp)
                }
            }
            // The one thing worth knowing without opening the item: it got cheaper.
            // Withheld while the reading is doubtful, because a fall computed from a
            // price the shop no longer states is a claim about nothing.
            if (wish.history.size > 1 && change <= -1.0 && !stale && !held) {
                // A dark chip with lime figures rather than a lime chip: on a grid
                // of falling prices the lime blocks were most of the screen's colour.
                Text(
                    signedPercent(change, 0),
                    color = Accent,
                    fontSize = Type.captionSize,
                    fontWeight = Type.strong,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Space.sm)
                        .background(AppBackground.copy(alpha = 0.82f), Radius.pill)
                        .padding(horizontal = Space.sm, vertical = 2.dp),
                    style = Tabular
                )
            }
            freshnessLabel(wish.freshness)?.takeIf { stale }?.let { warning ->
                Text(
                    warning,
                    color = TextPrimary,
                    fontSize = Type.overlineSize,
                    fontWeight = Type.medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(Space.sm)
                        .background(SurfaceHigh, Radius.pill)
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
                overflow = TextOverflow.Ellipsis,
                color = if (held) TextSecondary else TextPrimary
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                money(wish.price),
                fontSize = Type.cardTitleSize,
                fontWeight = Type.strong,
                // A price nobody can currently buy at must not carry the weight of
                // one that was read this morning.
                color = if (stale || held) TextDisabled else TextPrimary,
                // The grid puts two of these side by side, which is precisely where
                // digits of unequal width stop lining up and the column looks nudged.
                style = Tabular
            )
            // Where this price sits in its own range, unlabelled and five pixels
            // tall. The percentage badge over the photograph says how far the price
            // has come since the first reading, which is a different fact and the
            // one that misleads on its own: a wish can be ten percent below where it
            // started and still be sitting at the top of the last month. This is the
            // other half. Withheld while the reading is doubtful or the wish is set
            // aside, for the same reason the badge is — there is no live price to
            // place. Each card is on its own scale, so the bars are not comparable
            // between cards and nothing here invites reading them that way; the
            // comparable figure is the percentage, in text, above.
            if (!stale && !held && rangeInsight != null) {
                Spacer(Modifier.height(Space.sm))
                PriceRangeBar(rangeInsight, labels = false, height = 5.dp)
            }
            if (holdText != null) {
                Text(
                    holdText,
                    color = if (held) TextSecondary else Accent,
                    fontSize = Type.overlineSize,
                    fontWeight = if (held) Type.regular else Type.medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (wish.targetPrice > 0) {
                Text(
                    "ціль ${money(wish.targetPrice)}",
                    color = TextSecondary,
                    fontSize = Type.overlineSize,
                    style = Tabular
                )
            }
            if (!held && (wish.saved > 0 || wish.monthlyPlan > 0)) {
                Spacer(Modifier.height(Space.sm))
                PillProgress(plan.progress, height = 4.dp)
            }
        }
    }
}


@Composable
fun CalculatorScreen(store: Store) {
    val touch = rememberTouch()
    var amount by remember { mutableStateOf("") }
    var deal by remember { mutableStateOf(Deal.BUY) }
    // Dollars by default: «скільки коштуватиме купити сто доларів» is the question
    // this is opened with. The arrows beside the field switch to hryvnias.
    var amountInUah by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf("+") }
    val cached = remember { store.fxRate() }
    var rate by remember { mutableStateOf(cached.first) }
    var fetchedAt by remember { mutableLongStateOf(cached.second) }
    var loading by remember { mutableStateOf(false) }
    var rateError by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(store.rateHistory()) }
    // The corridor that replaced the single threshold (RateWatch.kt). Reading it
    // the first time turns an old threshold into its edge.
    val rateContext = LocalContext.current
    val prices = remember(rateContext) { PriceStore(rateContext) }
    var corridor by remember { mutableStateOf(prices.rateCorridor(store)) }
    var askingTarget by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /**
     * [asked] is what separates the refresh button from the one this screen runs
     * for itself on open. Both set the same error text, but only the one you tapped
     * is allowed to answer in the hand: a phone that shivers every time the rate
     * tab is opened without a signal is reporting its own housekeeping.
     */
    fun refresh(asked: Boolean) {
        scope.launch {
            loading = true
            rateError = false
            // Recorded here as well as on a schedule, so a phone that is opened daily
            // builds a month of chart whether or not background work ran. What is
            // kept, and what reaches the chart, is the same decision the background
            // pass makes — see refreshUsdRate.
            val kept = runCatching { refreshUsdRate(store) }.getOrNull()
            // Shown as the phone now holds it, which can be newer than this screen's
            // copy: the background pass may have saved a reading meanwhile.
            val (held, heldAt) = store.fxRate()
            rate = kept ?: held
            fetchedAt = heldAt
            history = store.rateHistory()
            // Nothing new kept is a refresh that failed — including the NBU answering
            // while a bank reading from minutes ago is on the phone. The figure and
            // its time stay as they were, and the line under them says so.
            rateError = kept == null
            if (rateError && asked) touch.refused()
            loading = false
        }
    }

    // Only reach for the network when the cached rate is actually stale. The
    // refresh button always asks, which is what it is for.
    LaunchedEffect(Unit) {
        val age = System.currentTimeMillis() - fetchedAt
        if (rate.sell <= 0 || age > 30 * 60 * 1000L) refresh(asked = false)
    }

    val a = parseAmount(first)
    val b = parseAmount(second)
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
            // The source the figure on this screen actually came from. It always
            // said MONOBANK, including on the days the rate was the NBU's.
            item {
                ScreenHeader(
                    "Курс",
                    trailing = {
                        Text(
                            when (rate.source) {
                                SOURCE_MONOBANK -> "Monobank"
                                SOURCE_NBU -> "НБУ"
                                else -> ""
                            },
                            color = TextSecondary,
                            fontSize = Type.captionSize
                        )
                    }
                )
            }
            item {
                Column(Modifier.padding(horizontal = Space.screen)) {
                    // Both of the bank's figures side by side: what a dollar brings
                    // when you sell it, and what one costs when you buy it. The NBU
                    // has a single official figure and no two sides, so it gets one
                    // tile — never the same number twice dressed as two.
                    if (rate.sell > 0 && rate.source != SOURCE_NBU) {
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            RateTile(
                                "Купівля",
                                rate.buy,
                                "стільки дадуть за $1, коли продаєте",
                                "💰",
                                TileMint,
                                Modifier.revealOnEnter(0).weight(1f).fillMaxHeight()
                            )
                            RateTile(
                                "Продаж",
                                rate.sell,
                                "стільки коштує $1, коли купуєте",
                                "💵",
                                TileSky,
                                Modifier.revealOnEnter(1).weight(1f).fillMaxHeight()
                            )
                        }
                    } else {
                        RateTile(
                            if (rate.sell > 0) "Офіційний курс" else "Курс",
                            rate.sell,
                            if (rate.sell > 0) {
                                "₴ за $1. Купівлю й продаж покаже Monobank, щойно відповість"
                            } else {
                                "ще не завантажено"
                            },
                            "🏦",
                            TileSand,
                            Modifier.revealOnEnter(0).fillMaxWidth()
                        )
                    }
                    // Never the figure without its source: the official rate and a
                    // bank's differ by most of a hryvnia, and an unlabelled number
                    // invites reading one as the other.
                    Row(
                        Modifier.fillMaxWidth().padding(top = Space.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            listOfNotNull(
                                rateSourceLabel(rate).takeIf { it.isNotBlank() },
                                if (fetchedAt > 0) "станом на ${timeLabel(fetchedAt)}" else null
                            ).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                                Text(it, color = TextSecondary, fontSize = Type.captionSize, lineHeight = Type.captionLine)
                            }
                            spreadLine(rate)?.let {
                                Text(it, color = TextSecondary, fontSize = Type.captionSize, lineHeight = Type.captionLine)
                            }
                            if (rateError) {
                                Text(
                                    if (fetchedAt > 0) "Оновити не вдалося" else "Ні Monobank, ні НБУ не відповіли, спробуйте пізніше",
                                    color = Negative,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine
                                )
                            }
                        }
                        IconButton({ refresh(asked = true) }) {
                            if (loading) BusyMark()
                            else Icon(Icons.Default.Refresh, "Оновити", tint = TextSecondary)
                        }
                    }
                    Spacer(Modifier.height(Space.md))
                    // The converter: which deal, how much, and what it comes to. The
                    // deal picks the rate, so the figure cannot be worked out at the
                    // wrong one of the two.
                    BentoTile(SurfaceRaised, Modifier.revealOnEnter(2).fillMaxWidth()) {
                        SegmentedControl(
                            options = listOf("Купую $", "Продаю $"),
                            selected = if (deal == Deal.BUY) 0 else 1
                        ) {
                            deal = if (it == 0) Deal.BUY else Deal.SELL
                        }
                        Spacer(Modifier.height(Space.md))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                NumberField(exchangeFieldLabel(amountInUah), amount) { amount = it }
                            }
                            Spacer(Modifier.width(Space.sm))
                            // Which currency is typed is a switch, not a command: the
                            // figure below keeps its digits and changes its meaning,
                            // which is easy to miss on a glance and impossible to miss
                            // in the hand.
                            IconButton({
                                amountInUah = !amountInUah
                                touch.switched(amountInUah)
                            }) {
                                Icon(
                                    Icons.Default.SwapVert,
                                    if (amountInUah) "Вводити в доларах" else "Вводити в гривнях",
                                    tint = TextPrimary
                                )
                            }
                        }
                        Spacer(Modifier.height(Space.lg))
                        val worked = exchange(deal, amountInUah, parseAmount(amount), rate)
                        HeroPanel(
                            label = exchangeResultLabel(deal, amountInUah),
                            value = if (worked.resultInUah) money(worked.result) else dollars(worked.result),
                            muted = worked.result == 0.0,
                            // The rate it was worked out at, where it would otherwise
                            // be scrolled up to and checked.
                            trailing = if (worked.rate > 0) {
                                {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "за курсом",
                                            color = AccentInk.copy(alpha = 0.65f),
                                            fontSize = Type.captionSize
                                        )
                                        Text(
                                            rateFigure(worked.rate),
                                            color = AccentInk,
                                            fontSize = Type.sectionSize,
                                            lineHeight = Type.sectionLine,
                                            fontWeight = Type.strong,
                                            style = Tabular
                                        )
                                    }
                                }
                            } else {
                                null
                            }
                        )
                    }

                    Row(
                        Modifier.padding(top = Space.xxl, bottom = Space.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EmojiGlyph("📈", 28.dp)
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            "Курс за місяць",
                            fontSize = Type.sectionSize,
                            lineHeight = Type.sectionLine,
                            fontWeight = Type.medium
                        )
                    }
                    Card(
                        Modifier.litEdge(Radius.lg),
                        shape = Radius.lg,
                        colors = CardDefaults.cardColors(containerColor = SurfaceBase)
                    ) {
                        Column(Modifier.padding(Space.lg)) {
                            if (history.isNotEmpty()) {
                                // A straight line here rather than a step, and the
                                // difference is not cosmetic: appendRate records a
                                // point every day whether or not the rate moved, so
                                // the gaps are days without a bank reading — the
                                // phone off, or Monobank not answering — not days the
                                // rate stood still. A step would claim it held for a
                                // week and then jumped.
                                PriceChart(
                                    history,
                                    kind = ChartLine.LINEAR,
                                    format = { "${rateFigure(it)} ₴" }
                                )
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
                            HorizontalDivider(
                                color = HairLine,
                                modifier = Modifier.padding(top = Space.md)
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = Space.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    corridorNote(corridor, rate),
                                    Modifier.weight(1f),
                                    color = TextSecondary,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine
                                )
                                Spacer(Modifier.width(Space.sm))
                                OutlinedButton(
                                    onClick = { askingTarget = true },
                                    shape = Radius.sm,
                                    border = BorderStroke(1.dp, HairLine),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = TextPrimary
                                    )
                                ) {
                                    Text(if (!corridor.watching) "Стежити" else "Змінити")
                                }
                            }
                        }
                    }

                    // A section heading sits closer to its own content than to what came
                    // before it, so the gap above is larger than the gap below.
                    Row(
                        Modifier.padding(top = Space.xxl, bottom = Space.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EmojiGlyph("🧮", 28.dp)
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            "Калькулятор сум",
                            fontSize = Type.sectionSize,
                            lineHeight = Type.sectionLine,
                            fontWeight = Type.medium
                        )
                    }
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
                        NumberFormat.getNumberInstance(UK).format(total),
                        total == 0.0,
                        hero = false
                    )
                }
            }
        }
        CollapsingTitle("Курс", listState)
    }
    if (askingTarget) {
        // Two optional edges and «Сплеск» — see RateWatch.kt. Saving schedules the
        // hourly check, and clearing everything stops it.
        RateCorridorDialog(
            corridor = corridor,
            rate = rate,
            onDismiss = { askingTarget = false },
            onSave = { next ->
                corridor = next
                prices.saveRateCorridor(next)
                RateWorker.schedule(rateContext)
                askingTarget = false
            }
        )
    }
}

/** One of the bank's figures for a dollar, as a tile. */
@Composable
fun RateTile(
    label: String,
    value: Double,
    caption: String,
    emoji: String,
    colour: Color,
    modifier: Modifier = Modifier
) {
    BentoTile(colour, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                Modifier.weight(1f),
                color = softInkOn(colour),
                fontSize = Type.captionSize,
                fontWeight = Type.medium
            )
            EmojiGlyph(emoji, 28.dp)
        }
        Spacer(Modifier.height(Space.xs))
        SplitFigure(if (value > 0) rateFigure(value) else "—", 30.sp)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(Space.xs))
        TileCaption(caption, colour)
    }
}

/**
 * A subscription's own price history, two or three points wide, in its row.
 *
 * A step rather than a slope, for the reason [ChartLine.STEP] sets out: a point is
 * written only where the price moved, so a sloping line between two figures would
 * draw six months of a rise that happened on one morning.
 *
 * **Why this is not [PriceChart], having looked.** The shared step chart landed and
 * it does not fit here, and forcing it in would be worse than these thirty lines.
 * It is a `Column`: a 132dp plate in [ChartGround] clipped to [Radius.sm], a
 * gridline across the middle, a 6dp inset, and — not optionally — an axis row
 * underneath carrying [axisNote]. In a 28×14dp slot the 12dp corner radius rounds
 * the plate into a lozenge, the insets leave two device pixels of plot, and the
 * axis line would print "вісь 199 – 249 ₴" beside [amountMoveLine], which already
 * says both figures in words. It also owns a press-and-drag scrub, and this sits
 * inside a row that is itself clickable to open the expense — two gestures
 * competing for the same 28 dp. None of that is a flaw in [PriceChart]; it is a
 * full-width card component being asked to be a sparkline.
 *
 * **What is adopted, rather than duplicated.** The scale comes from [chartAxis],
 * so the [MIN_AXIS_SPAN] floor applies here too — that was a real bug in the first
 * version of this, which fitted two points to their own min and max and so drew a
 * 1% raise as the same cliff as a doubling. The line colour is [ChartInk], so the
 * rule about calming the lime on a 2dp stroke holds in a list row exactly as it
 * does on the card. What stays local is the twenty lines of `drawLine`.
 *
 * Spaced evenly rather than by [chartPositions]. There is no horizontal axis here
 * and the date is in the text beside it, so date spacing would buy no readable
 * information and would cost the common case: two changes a week apart and one six
 * months later collapse the first tread to a fraction of a pixel at this width.
 */
@Composable
fun AmountStep(points: List<PricePoint>, modifier: Modifier = Modifier) {
    val prices = points.map { it.price }.filter { it > 0.0 }
    // The last move decides the colour. A raise is the fact worth noticing, and it
    // is a fact about the subscription rather than about the person paying it.
    val raised = prices.size >= 2 && prices.last() > prices[prices.size - 2]
    val axis = remember(prices) { chartAxis(prices) }
    Canvas(modifier) {
        if (prices.size < 2) return@Canvas
        val width = 2.dp.toPx()
        val usable = (size.height - width * 2).coerceAtLeast(1f)
        val slot = size.width / prices.size
        fun height(price: Double) = width + usable * (1f - axis.fraction(price))

        var x = 0f
        var previous = height(prices.first())
        prices.forEachIndexed { index, price ->
            val y = height(price)
            val last = index == prices.size - 1
            val ink = when {
                !last -> TextDisabled
                // [Negative] is left alone. The desaturation rule behind [ChartInk]
                // is about the lime specifically — 17:1 on this ground at a maxed
                // green channel is what blooms — and this salmon is nowhere near
                // that. Mixing it a quarter towards grey would only make the one
                // stroke that means "this went up" harder to tell from the ones
                // that do not.
                raised -> Negative
                else -> ChartInk
            }
            // The riser first, so the tread that follows caps it cleanly.
            if (index > 0) {
                drawLine(ink, Offset(x, previous), Offset(x, y), width, StrokeCap.Round)
            }
            drawLine(ink, Offset(x, y), Offset(x + slot, y), width, StrokeCap.Round)
            x += slot
            previous = y
        }
    }
}

/**
 * The month as one bar: what the standing costs take, and what survives them.
 *
 * One shape and one colour, because at five subscriptions a palette would be
 * five arbitrary hues nobody can hold in their head — and the question the bar
 * answers is not "which one" but "how much of the month is already gone".
 *
 * The third state is the reason this is a composable rather than a call to
 * [PillProgress]. With no income entered there is no denominator, and both a full
 * bar and an empty one would be a claim: one says the month is spent, the other
 * says it is free. So the lime runs from the left and fades out, which says
 * exactly what is true — this much is committed, and how much of the month that
 * is remains unknown.
 */
@Composable
fun CommittedBar(bar: Committed, modifier: Modifier = Modifier) {
    val height = 14.dp
    Column(modifier.fillMaxWidth()) {
        Text(
            committedHeadline(bar),
            // One line: the label and the figure are one sentence, and at the hero
            // size «Лишається 27 891,06 ₴» broke in two.
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.4).sp,
            maxLines = 1,
            fontWeight = Type.strong,
            color = when (bar.state) {
                // White, not lime: lime text this large haloes on near-black, and
                // the lime on this screen belongs to the hero panel above.
                CommittedState.KNOWN -> TextPrimary
                CommittedState.OVERSPENT -> Negative
                CommittedState.UNKNOWN -> TextSecondary
            },
            // This figure is rewritten the moment the income is edited, and the
            // bar it sits above changes width at the same time. Proportional
            // digits would make the headline change width too, so two things
            // would move when only one of them is the answer.
            style = Tabular
        )
        Spacer(Modifier.height(Space.sm))
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(SurfaceHigh, Radius.pill)
        ) {
            when (bar.state) {
                CommittedState.UNKNOWN -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(height)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Accent.copy(alpha = 0.55f), Color.Transparent)
                            ),
                            Radius.pill
                        )
                )

                else -> Box(
                    Modifier
                        .fillMaxWidth(bar.share.coerceIn(0f, 1f))
                        .height(height)
                        .background(
                            if (bar.state == CommittedState.OVERSPENT) Negative else TextPrimary,
                            Radius.pill
                        )
                )
            }
        }
        Spacer(Modifier.height(Space.sm))
        Text(
            committedDetail(bar),
            color = TextSecondary,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine
        )
    }
}

/** The two halves of the payments screen. */
const val PAYMENTS_SCHEDULE = 0
const val PAYMENTS_BY_MONTH = 1

@Composable
fun PaymentsScreen(
    items: List<Pay>,
    save: (List<Pay>) -> Unit,
    store: Store,
    adding: Boolean,
    setAdding: (Boolean) -> Unit,
    /** What has been marked paid, across every month still kept. */
    paid: List<PaidMark>,
    setPaid: (List<PaidMark>) -> Unit,
    onDelete: (Int) -> Unit,
    /** Purchases a plan «частинами» can be tied to — see PaymentsLife.kt. */
    orders: List<Order> = emptyList(),
    /** A plan's purchase went back: the purchase says so. */
    onOrderReturned: (orderId: String, day: Long) -> Unit = { _, _ -> },
    /** A letter about a subscription shared into the app, waiting here. */
    shared: SharedLetter? = null,
    onSharedUsed: () -> Unit = {},
    /** «На життя», the funds and the payday — MoneyPlan.kt. Null draws the screen as it was. */
    moneyHost: MoneyHost? = null
) {
    val touch = rememberTouch()
    // The rate the exchange screen already fetched and cached. Dollar entries are
    // converted at the sell rate, since that is what buying dollars costs.
    val rate = remember { store.fxRate().first }
    // Read once, so the timeline and the strip cannot disagree about which day it
    // is, nor the totals about which trials have run out.
    val today = remember { LocalDate.now() }
    val monthly = monthlyTotal(items, rate.sell, today)
    val yearly = yearlyTotal(items, rate.sell, today)
    // What the year becomes once the free periods end. Only shown while one is
    // running, because otherwise it is the same figure twice.
    val trials = trialsRunning(items, today)
    val committed = yearlyCommitment(items, rate.sell, today)
    var income by remember { mutableDoubleStateOf(store.income()) }
    var editingIncome by remember { mutableStateOf(false) }
    // The same «Вільно» as Огляд — life and the funds in it — from this screen's
    // own income, so an edit here shows at once.
    val honest = moneyHost?.let { honestMonth(it.inputs.copy(income = income, pays = items, marks = paid), it.plan.funds) }
    val month = honest?.asBudget() ?: budget(income, monthly)
    var editing by remember { mutableStateOf<Int?>(null) }
    val shift = yearlyShift(items, rate.sell, today)
    val thisMonth = monthKey(today)
    val record = monthRecord(items, paid, thisMonth, today, rate.sell)
    // Empty until a background pass has fetched the year, and empty is a working
    // state: the weekend rule stands on its own without it.
    val holidays = remember { store.holidaysAround(today) }
    val listState = rememberLazyListState()
    // Which half of the screen is showing. Not remembered: the schedule is what
    // the tab is opened for, and the history is a question asked on purpose.
    var view by remember { mutableIntStateOf(PAYMENTS_SCHEDULE) }
    // monobank: charges that look like payments, and subscriptions not on the list.
    // Worked out here from the stored statement — no network on this screen.
    val context = LocalContext.current
    val monoVersion = MonoStore.version
    val mono = remember { MonoStore(context) }
    val monoView = remember(monoVersion, items, paid, today) {
        if (!mono.connected()) {
            null
        } else {
            val client = mono.client()
            val txs = mono.txs()
            val currencies = accountCurrencies(client)
            val gone = mono.gone()
            MonoView(
                matches = monoMatches(items, txs, paid, mono.rejected(), currencies, today, rate.sell)
                    // A confirmed merchant ticks by itself on the next pass, unless that is off.
                    .filter { it.kind != MonoMatchKind.LEARNED || !mono.auto() },
                // A cancelled payment's merchant is watched for charges after the
                // cancellation, not offered back as a forgotten subscription.
                found = findSubscriptions(
                    txs, items, mono.ignored() + gone.map { it.merchant }, currencies, System.currentTimeMillis() / 1000
                ),
                drifts = monoDrifts(items, paid, today),
                silent = silentPayments(items, txs, paid, mono.waiting(), today),
                doubles = doubleCharges(items, txs, mono.doublesOk(), System.currentTimeMillis() / 1000),
                afterCancel = chargedAfterCancel(items, gone, txs, mono.afterOk(), currencies, rate.sell),
                currencies = currencies
            )
        }
    }
    // Answers to the monobank cards that act on a payment: «Прибрати» opens the
    // cancel question with what the bank's silence says was paid for.
    var removing by remember { mutableStateOf<SilentPay?>(null) }
    // The tiles rise in the first time this tab opens — see [Entrance].
    val entrance = LocalEntrance.current
    Box {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = navClearance() + Space.fabClearance)
        ) {
            // Item zero is the header alone: that is the block the compact bar watches.
            item { ScreenHeader("Платежі", trailing = { AddButton("Додати витрату") { setAdding(true) } }) }
            // Two halves of one screen: what is coming, and what already went. The
            // second used to live at the bottom of Огляд, which is not where anyone
            // looks for "скільки я заплатив за вересень".
            item {
                SegmentedControl(
                    options = listOf("Розклад", "По місяцях"),
                    selected = view,
                    modifier = Modifier.padding(horizontal = Space.screen).padding(bottom = Space.lg)
                ) { view = it }
            }
            if (view == PAYMENTS_BY_MONTH) {
                item(key = "paid-months") {
                    PaidMonths(
                        // Two months at least, so the one just ended is always there
                        // to be read or filled in. See [monthRecords].
                        months = monthRecords(items, paid, today, rate.sell, atLeast = 2),
                        pays = items,
                        marks = paid,
                        onToggle = { pay, month -> setPaid(togglePaid(paid, pay, month)) },
                        modifier = Modifier.padding(horizontal = Space.screen),
                        onCharged = { pay, month, amount, updateExpense ->
                            setPaid(withMarkAmount(paid, pay.name, month, amount))
                            if (updateExpense) {
                                val day = LocalDate.now().toEpochDay()
                                // Through the history, so «було → стало» and the
                                // digest's raise line see it like any other edit.
                                save(items.map { if (it.name == pay.name) withAmount(it, amount, day) else it })
                            }
                        }
                    )
                }
            } else {
                item {
                    Column(
                        Modifier
                            .padding(horizontal = Space.screen)
                            .padding(bottom = Space.xl)
                    ) {
                        // The loudest figure should be one you can act on. A monthly total is
                        // read and forgotten; the next payment is prepared for, so it takes
                        // the panel and the total moves down into the summary rows.
                        // What is still owed, as the pill and the digest count it: a
                        // bill already ticked off is not the next thing to prepare for.
                        val next = nextPayment(stillOwing(items, paid, today), today, rate.sell)
                        HeroPanel(
                            modifier = Modifier.revealOnEnter(0, entrance),
                            label = if (next != null) {
                                "Найближчий платіж · ${dueLabel(next.daysAway)}"
                            } else {
                                "Разом на місяць"
                            },
                            value = personalFigure(money(next?.total?.total ?: monthly.total)),
                            caption = when {
                                next == null -> null
                                next.total.rateMissing ->
                                    "${dayMonth(next.date)} · плюс ${dollars(next.total.usd)}, курс ще не завантажено"
                                else -> "${dayMonth(next.date)} · ${dueSummary(next.items)}"
                            }?.let { personal(it) },
                            muted = next == null,
                            // The next thirty days belong to the next payment's panel —
                            // one thought, "what leaves and when", in one block. On the
                            // page beneath it the strip was an island between two cards.
                            footer = if (items.isEmpty()) null else {
                                {
                                    DaysStrip(days = 30, marked = paymentOffsets(items, today), onLime = true)
                                }
                            },
                            // What leaves next, as its own emoji; a calendar when
                            // several things leave on the same day.
                            emoji = next?.let { coming -> coming.items.singleOrNull()?.let { shownEmoji(it) } ?: "🗓️" }
                        )
                        Spacer(Modifier.height(Space.md))
                        // The month as two tiles side by side. Not "here are your
                        // subscriptions" but "here is what survives them", beside
                        // what has already gone — until the second existed the
                        // screen could only ever state the plan.
                        Row(
                            Modifier.revealOnEnter(1, entrance).height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(Space.md)
                        ) {
                            MonthLeftTile(
                                committedOf(month),
                                Modifier.weight(1f).fillMaxHeight(),
                                lifeShare = honest?.let { lifeShare(it) } ?: 0f,
                                detail = honest?.let { monthBarDetail(it) }
                            ) { editingIncome = true }
                            BentoTile(TileSand, Modifier.weight(1f).fillMaxHeight()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Сплачено",
                                        Modifier.weight(1f),
                                        color = TileInkSoft,
                                        fontSize = Type.captionSize
                                    )
                                    EmojiGlyph("✅", 24.dp, Modifier.popOnRise(record.paidCount))
                                }
                                Spacer(Modifier.height(Space.xs))
                                SplitFigure(personalFigure(totalLabel(record.paid)), 22.sp)
                                Spacer(Modifier.weight(1f))
                                Spacer(Modifier.height(Space.sm))
                                TileCaption(
                                    if (record.plannedCount == 0) {
                                        "цього місяця нічого"
                                    } else {
                                        "позначено ${record.paidCount} з ${record.plannedCount}"
                                    },
                                    TileSand
                                )
                            }
                        }
                        if (items.isNotEmpty()) {
                            Spacer(Modifier.height(Space.md))
                            Card(
                                modifier = Modifier.revealOnEnter(2, entrance).fillMaxWidth().litEdge(Radius.md),
                                colors = CardDefaults.cardColors(containerColor = SurfaceBase),
                                shape = Radius.md
                            ) {
                                Column(Modifier.padding(Space.lg)) {
                                    // A year of the same costs, because that is the scale at
                                    // which a subscription is worth arguing with.
                                    LeaderRow("Разом на рік", personalFigure(money(yearly.total)))
                                    // When a plan «частинами» ends, the month gets that much back.
                                    freedLine(items, today, rate.sell)?.let { line ->
                                        Text(
                                            personal(line),
                                            Modifier.padding(top = Space.xs),
                                            color = TextPrimary,
                                            fontSize = Type.captionSize,
                                            lineHeight = Type.captionLine
                                        )
                                    }
                                    // And what that same year cost before the quiet
                                    // raises. Each one is a few tens of hryvnia and
                                    // reads as nothing; twelve months of all of them
                                    // is the figure that gets something cancelled.
                                    yearlyShiftNote(shift)?.let { note ->
                                        Text(
                                            personal(note),
                                            Modifier.padding(top = Space.xs),
                                            color = TextSecondary,
                                            fontSize = Type.captionSize,
                                            lineHeight = Type.captionLine
                                        )
                                    }
                                    // The row above counts a trial as the nought it
                                    // currently is. That is true of this month and
                                    // false of the year, so the commitment behind the
                                    // free period is stated rather than left to be
                                    // discovered on the first statement.
                                    if (trials.isNotEmpty()) {
                                        LeaderRow(
                                            // A promo is a discount, not a free period.
                                            if (trials.any { isPromo(it) }) "Після знижок і пробних" else "Після пробних періодів",
                                            personalFigure(money(committed.total))
                                        )
                                    }
                                    if (monthly.rateMissing) {
                                        // Both totals are short by this much, so it is said as
                                        // a gap rather than folded in as a smaller number.
                                        LeaderRow(
                                            personal("Плюс ${dollars(yearly.usd)} на рік"),
                                            "курс ще не завантажено"
                                        )
                                    } else if (monthly.hasUsd) {
                                        LeaderRow(
                                            personal("З них ${dollars(yearly.usd)} на рік"),
                                            personalFigure("≈ ${approxMoney(yearly.usdInUah)}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                monoView?.matches?.takeIf { it.isNotEmpty() }?.let { matches ->
                    item(key = "mono-matches") {
                        MonoMatchesTile(
                            matches,
                            Modifier.padding(horizontal = Space.screen).padding(bottom = Space.md),
                            onYes = { match ->
                                touch.switched(true)
                                setPaid(withMonoMark(paid, match))
                                // Remembered, so the next charge from this merchant ticks itself.
                                val merchant = merchantKey(match.tx.description)
                                save(items.map { if (it.name == match.pay.name) it.copy(monoMerchant = merchant) else it })
                            },
                            onNo = { match ->
                                mono.reject("${match.tx.id}|${match.pay.name}")
                                MonoStore.bump()
                            }
                        )
                    }
                }
                monoView?.drifts?.takeIf { it.isNotEmpty() }?.let { drifts ->
                    item(key = "mono-drifts") {
                        MonoDriftsTile(
                            drifts,
                            Modifier.padding(horizontal = Space.screen).padding(bottom = Space.md)
                        ) { pay, charged ->
                            // Through the history, so «було → стало» and the digest see it.
                            save(items.map { if (it.name == pay.name) withAmount(it, charged, today.toEpochDay()) else it })
                        }
                    }
                }
                monoView?.found?.takeIf { it.isNotEmpty() }?.let { found ->
                    item(key = "mono-found") {
                        FoundSubscriptionsTile(
                            found,
                            Modifier.padding(horizontal = Space.screen).padding(bottom = Space.md),
                            onAdd = { item ->
                                val amount = kotlin.math.round(item.amount * 100) / 100.0
                                save(
                                    items + Pay(
                                        prettyMerchant(item.title),
                                        amount,
                                        item.day.coerceIn(1, 31),
                                        item.currency,
                                        amounts = listOf(PricePoint(amount, today.toEpochDay())),
                                        monoMerchant = item.key
                                    )
                                )
                                // Opened at once, to name it properly and check the day.
                                editing = items.size
                            },
                            onIgnore = { item ->
                                mono.ignore(item.key)
                                MonoStore.bump()
                            }
                        )
                    }
                }
                // What the statement says about payments' lives — Mono.kt. Money taken
                // after a cancellation first: it should not have been taken at all.
                val currencies = monoView?.currencies ?: emptyMap()
                monoView?.afterCancel?.takeIf { it.isNotEmpty() }?.let { after ->
                    item(key = "mono-after") {
                        AfterCancelTile(
                            after,
                            Modifier.padding(horizontal = Space.screen).padding(bottom = Space.md),
                            onNotIt = {
                                mono.afterIsOk(it.tx.id)
                                MonoStore.bump()
                            },
                            onBringBack = { found ->
                                val listed = found.pay
                                if (listed != null) {
                                    save(items.map { if (it == listed) unstopped(it) else it })
                                } else {
                                    found.gone?.pay?.takeIf { it.isNotBlank() }
                                        ?.let { json -> runCatching { payOf(JSONObject(json)) }.getOrNull() }
                                        ?.let { back -> save(items + back) }
                                    mono.saveGone(mono.gone().filterNot { it == found.gone })
                                }
                                // It did charge: that month is paid, at what was taken.
                                val month = monthKey(txDay(found.tx))
                                if (!isPaid(paid, found.name, month)) {
                                    setPaid(paid + PaidMark(found.name, month, found.charged, found.currency))
                                }
                                mono.afterIsOk(found.tx.id)
                                MonoStore.bump()
                            }
                        )
                    }
                }
                monoView?.doubles?.takeIf { it.isNotEmpty() }?.let { doubles ->
                    item(key = "mono-doubles") {
                        DoubleChargeTile(
                            doubles,
                            currencies,
                            Modifier.padding(horizontal = Space.screen).padding(bottom = Space.md)
                        ) {
                            mono.doubleOk(it.key)
                            MonoStore.bump()
                        }
                    }
                }
                monoView?.silent?.takeIf { it.isNotEmpty() }?.let { silent ->
                    item(key = "mono-silent") {
                        SilentTile(
                            silent,
                            Modifier.padding(horizontal = Space.screen).padding(bottom = Space.md),
                            onWait = {
                                mono.saveWaiting(it.pay.name, today.toEpochDay() + SILENT_WAIT_DAYS)
                                MonoStore.bump()
                            },
                            onRemove = { removing = it }
                        )
                    }
                }
                // A cancellation whose paid period has run out asks, the day the next
                // charge would have come, whether it really did not — PaymentsLife.kt.
                // Not where the statement already shows the charge: the card above
                // asks about that one with the bank's own figures.
                val charged = monoView?.afterCancel.orEmpty().mapNotNull { it.pay }
                items.withIndex()
                    .filter { lifeOf(it.value, today) == PayLife.ENDED && it.value !in charged }
                    .forEach { (position, pay) ->
                        item(key = "ended-$position-${pay.name}") {
                            EndedTile(
                                pay,
                                Modifier.padding(horizontal = Space.screen).padding(bottom = Space.md),
                                onNotCharged = { onDelete(position) },
                                onCharged = {
                                    save(items.mapIndexed { i, item -> if (i == position) unstopped(item) else item })
                                    val month = monthKey(LocalDate.ofEpochDay(pay.stopsAfter + 1))
                                    if (!isPaid(paid, pay.name, month)) {
                                        setPaid(paid + PaidMark(pay.name, month, markAmount(pay, month), pay.currency))
                                    }
                                }
                            )
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
                // Every payment in the coming month as a tile, two to a row, soonest
                // first. The date rides on each tile instead of heading a group: in a
                // grid, a heading would sit over one tile and not over its neighbour.
                val timeline = paymentGroups(items, today).flatMap { group -> group.positions.map { group.date to it } }
                val colours = tileColours(timeline.map { (_, position) -> items[position].name })
                timeline.chunked(2).forEachIndexed { row, pair ->
                    item(key = "pays-" + pair.joinToString("|") { (date, position) -> "$date-$position" }) {
                        Row(
                            Modifier
                                .animateItem()
                                .revealOnEnter(row + 3, entrance)
                                .padding(horizontal = Space.screen)
                                .padding(bottom = Space.md)
                                .height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(Space.md)
                        ) {
                            pair.forEachIndexed { column, (date, position) ->
                                val pay = items[position]
                                // The month this tick is about, which is not
                                // always this one — see [tickMonth].
                                val markMonth = tickMonth(pay, today, holidays)
                                val done = isPaid(paid, pay.name, markMonth)
                                PaymentTile(
                                    pay = pay,
                                    date = date,
                                    today = today,
                                    // A day of the month is a lie four or five times a
                                    // year: on a weekend or a holiday the tile names the
                                    // day the money actually has to be there by.
                                    dayNote = paymentDayNote(paymentDay(date, holidays)),
                                    usdSell = rate.sell,
                                    colour = colours[row * 2 + column],
                                    done = done,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    onToggle = {
                                        touch.switched(!done)
                                        setPaid(togglePaid(paid, pay, markMonth))
                                    },
                                    onOpen = { editing = position },
                                    // An annual payment with a fund wears its ring.
                                    extra = moneyHost?.plan?.funds?.firstOrNull { fundPay(it, items) == pay }?.let { fund ->
                                        { tile -> FundOnTile(fund, pay, rate.sell, tile) }
                                    }
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                // Cancelled payments still running out what was paid for, and paused
                // ones: off the timeline, because neither takes money, and each with
                // its one way back — PaymentsLife.kt.
                val winding = items.withIndex()
                    .filter { lifeOf(it.value, today) == PayLife.CANCELLED && !isLive(it.value, today) }
                if (winding.isNotEmpty()) {
                    item(key = "cancelled-head") {
                        Column(Modifier.padding(horizontal = Space.screen).padding(top = Space.lg, bottom = Space.sm)) {
                            SectionTitle("Скасовані — до кінця оплаченого")
                        }
                    }
                    winding.forEach { (position, pay) ->
                        item(key = "cancelled-$position-${pay.name}") {
                            LifeTile(
                                pay,
                                cancelledLine(pay, today).orEmpty(),
                                "Відновити",
                                Modifier.padding(horizontal = Space.screen).padding(bottom = Space.sm),
                                onAction = { save(items.mapIndexed { i, item -> if (i == position) unstopped(item) else item }) },
                                onOpen = { editing = position }
                            )
                        }
                    }
                }
                val resting = items.withIndex().filter { lifeOf(it.value, today) == PayLife.PAUSED }
                if (resting.isNotEmpty()) {
                    item(key = "paused-head") {
                        Column(Modifier.padding(horizontal = Space.screen).padding(top = Space.lg, bottom = Space.sm)) {
                            SectionTitle("На паузі")
                        }
                    }
                    resting.forEach { (position, pay) ->
                        item(key = "paused-$position-${pay.name}") {
                            LifeTile(
                                pay,
                                pausedLine(pay).orEmpty(),
                                "Відновити",
                                Modifier.padding(horizontal = Space.screen).padding(bottom = Space.sm),
                                onAction = { save(items.mapIndexed { i, item -> if (i == position) resumed(item, today) else item }) },
                                onOpen = { editing = position }
                            )
                        }
                    }
                }
                // Plans whose last payment is behind them: off the schedule, kept until
                // deleted, so what they cost stays readable.
                val finishedPlans = items.withIndex().filter { isFinished(it.value, today) }
                if (finishedPlans.isNotEmpty()) {
                    item(key = "plans-done") {
                        Column(Modifier.padding(horizontal = Space.screen).padding(top = Space.lg, bottom = Space.sm)) {
                            SectionTitle("Розстрочки, які закінчились")
                        }
                    }
                    finishedPlans.forEach { (position, pay) ->
                        item(key = "plan-done-$position-${pay.name}") {
                            BentoTile(
                                SurfaceRaised,
                                Modifier.padding(horizontal = Space.screen).padding(bottom = Space.sm).fillMaxWidth(),
                                onClick = { editing = position },
                                onClickLabel = "Змінити"
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    EmojiGlyph(shownEmoji(pay), 32.dp)
                                    Spacer(Modifier.width(Space.md))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            pay.name,
                                            fontSize = Type.bodySize,
                                            fontWeight = Type.medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        // Paid off early and returned say so — PaymentsLife.kt.
                                        TileCaption(finishedPlanLine(pay), SurfaceRaised)
                                    }
                                    EmojiGlyph("✅", 24.dp)
                                }
                            }
                        }
                    }
                }
                // The annual blind spot, given a place to be visible from.
                //
                // A charge that happens once in twelve months is off the timeline for
                // eleven of them, and until this section existed that meant off the
                // screen entirely — the quietest way a subscription tracker can lie.
                // They are listed at their real amounts on their real dates rather
                // than averaged into the monthly total, because an averaged figure in
                // the month the charge actually lands shows a month that fits when it
                // does not.
                val dormant = annualElsewhere(items, today)
                // 🫙 Фонди take the place of the «Далі ніж за місяць» line: each annual
                // charge can be saved for a little a month, and a fund of its own —
                // a cushion, the car's service — sits beside them. See Funds.kt.
                if (moneyHost != null) {
                    item(key = "funds") {
                        Column(
                            Modifier
                                .padding(horizontal = Space.screen)
                                .padding(top = Space.xl, bottom = Space.sm)
                        ) {
                            SectionTitle(if (dormant.isNotEmpty()) "Раз на рік" else "Фонди")
                            FundsTile(moneyHost)
                        }
                    }
                }
                if (dormant.isNotEmpty()) {
                    if (moneyHost == null) item(key = "annual-elsewhere") {
                        Column(
                            Modifier
                                .padding(horizontal = Space.screen)
                                .padding(top = Space.xl, bottom = Space.sm)
                        ) {
                            SectionTitle("Раз на рік")
                            annualElsewhereNote(items, today, rate.sell)?.let { note ->
                                Text(
                                    personal(note),
                                    color = TextSecondary,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine
                                )
                            }
                        }
                    }
                    // Position in the key: two identical annual expenses are equal as
                    // values, and a repeated key throws rather than drawing twice.
                    itemsIndexed(
                        dormant,
                        key = { index, pay -> "annual-$index-${pay.name}-${pay.billingMonth}-${pay.day}" }
                    ) { _, pay ->
                        Card(
                            Modifier
                                .padding(horizontal = Space.screen, vertical = Space.xs)
                                .fillMaxWidth()
                                .litEdge(Radius.md),
                            colors = CardDefaults.cardColors(containerColor = SurfaceLow),
                            shape = Radius.md
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { editing = items.indexOf(pay) }
                                    .padding(Space.lg),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                EmojiGlyph(shownEmoji(pay), 36.dp)
                                Spacer(Modifier.width(Space.md))
                                Column(Modifier.weight(1f).padding(end = Space.md)) {
                                    Text(
                                        pay.name,
                                        fontSize = Type.cardTitleSize,
                                        fontWeight = Type.medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        personal(annualDueLine(pay, today)),
                                        color = TextSecondary,
                                        fontSize = Type.captionSize,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        // A stack of dates and amounts, one per row.
                                        style = Tabular
                                    )
                                }
                                // The smoothed figure, and only ever here beside the
                                // real one above it. With a fund, what the fund holds
                                // instead — one payment never shows two sums a month.
                                val fund = moneyHost?.plan?.funds?.firstOrNull { fundPay(it, items) == pay }
                                Text(
                                    personal(
                                        if (fund != null) {
                                            fundProgressLine(fund, pay, rate.sell)
                                        } else {
                                            "≈${amountLabel(
                                                kotlin.math.round(monthlyEquivalent(pay)),
                                                pay.currency
                                            )}/міс"
                                        }
                                    ),
                                    color = TextDisabled,
                                    fontSize = Type.captionSize,
                                    // Right-aligned against the row's edge, so unequal
                                    // digits would leave the column of them ragged.
                                    style = Tabular
                                )
                            }
                        }
                    }
                }
            }
        }
        CollapsingTitle("Платежі", listState, trailing = { AddButton("Додати витрату") { setAdding(true) } })
    }
    // A shared letter opens the same form, filled in; nothing is saved until «Додати».
    val letter = shared
    if (adding || letter is SharedLetter.NewPayment) {
        AddPaymentSheet(
            { setAdding(false); onSharedUsed() },
            prefill = (letter as? SharedLetter.NewPayment)?.draft
        ) {
            save(items + it)
            setAdding(false)
            onSharedUsed()
        }
    }
    // A payment on the list at a new price: one button, through its own history.
    if (letter is SharedLetter.PriceChange) {
        PriceChangeDialog(letter, today, close = onSharedUsed) {
            val day = LocalDate.now().toEpochDay()
            save(items.map { if (it.name == letter.pay.name) withAmount(it, letter.draft.amount, day) else it })
            onSharedUsed()
        }
    }
    if (editingIncome) {
        IncomeDialog(
            income,
            { editingIncome = false },
            payday = moneyHost?.settings?.payday,
            holidays = moneyHost?.inputs?.holidays ?: emptySet(),
            savePayday = { payday -> moneyHost?.let { it.saveSettings(it.settings.copy(payday = payday)) } }
        ) { value ->
            income = value
            store.saveIncome(value)
            editingIncome = false
        }
    }
    // «Прибрати» from «Мовчать»: the cancel question, with what the bank's silence
    // says was paid for — usually already over, so it goes to the bin at once.
    removing?.let { silent ->
        CancelDialog(
            name = silent.pay.name,
            initial = silentPaidUntil(silent),
            today = today,
            onDismiss = { removing = null }
        ) { until ->
            removing = null
            val index = items.indexOf(silent.pay)
            if (index >= 0) {
                save(items.mapIndexed { i, item -> if (i == index) cancelled(item, until, today) else item })
                if (until.isBefore(today)) onDelete(index)
            }
        }
    }
    editing?.let { index ->
        items.getOrNull(index)?.let { pay ->
            EditPaymentSheet(
                pay,
                close = { editing = null },
                delete = {
                    onDelete(index)
                    editing = null
                },
                marks = paid,
                setMarks = setPaid,
                orders = orders,
                onReturned = onOrderReturned
            ) { changed ->
                save(items.mapIndexed { i, item -> if (i == index) changed else item })
                // Marks are matched by name, so a rename carries them across. Left
                // behind, the renamed rent read as unpaid this month, the reminder
                // started again, and every past month went red.
                if (changed.name != pay.name) {
                    setPaid(renamePaidMarks(paid, pay.name, changed.name))
                    // A fund for this payment follows the new name, as the marks do.
                    moneyHost?.updateFunds { renamedFunds(it, pay.name, changed.name) }
                }
                editing = null
            }
        }
    }
}

/**
 * One payment as a tile: what it is, when, how much, and the tick.
 *
 * Everything the timeline row used to say is still here — the yearly figure, the
 * free trial, the quiet raise, the weekend shift — stacked, because a tile is
 * narrow and tall where the row was wide and short. The amount sits at the foot,
 * so two tiles side by side line their amounts up.
 */
@Composable
fun PaymentTile(
    pay: Pay,
    date: LocalDate,
    today: LocalDate,
    dayNote: String?,
    usdSell: Double,
    colour: Color,
    done: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    /** Drawn under the trial line, in the tile's colour — a fund's ring (MoneyPlanUi.kt). */
    extra: (@Composable (Color) -> Unit)? = null
) {
    // A paid tile goes quiet: the pastel gives way to the dark ground, so a row of
    // tiles reads as what is still to pay (colour) and what is done (dark). Fading
    // the pastel instead turned peach into mud.
    val shown by animateColorAsState(if (done) SurfaceRaised else colour, Motion.effects(), label = "paid tile")
    BentoTile(shown, modifier, onClick = onOpen, onClickLabel = "Змінити") {
        Row(verticalAlignment = Alignment.Top) {
            // Jumps when the tick goes on — the tile's own answer to the tap.
            EmojiGlyph(shownEmoji(pay), 36.dp, Modifier.popOnRise(if (done) 1 else 0))
            Spacer(Modifier.weight(1f))
            // One tap, on the tile you are already looking at — this is the
            // control used without looking, halfway through paying something on
            // another screen, so the two states have to feel different.
            Box(
                Modifier
                    .offset(x = Space.sm, y = -Space.sm)
                    .size(Space.touchRow)
                    .clip(CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                PaidCheck(
                    done,
                    Modifier.semantics {
                        contentDescription = if (done) "Скасувати позначку про оплату" else "Позначити оплаченим"
                    },
                    ring = inkOn(shown).copy(alpha = 0.4f),
                    fill = inkOn(shown),
                    tick = shown
                )
            }
        }
        TileChip(if (date == today) "Сьогодні" else dayMonth(date), shown, strong = date == today)
        dayNote?.let { TileCaption(it, shown, Modifier.padding(top = Space.xs), maxLines = 3) }
        Text(
            pay.name,
            Modifier.padding(top = Space.sm),
            fontSize = Type.bodySize,
            lineHeight = Type.bodyLine,
            fontWeight = Type.medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // The annual figure is the one that changes minds about a subscription;
        // an annual charge says both denominators. See [billingLine].
        // With a fund, its ring says what goes in a month, so the smoothed
        // «≈…/міс» steps back: one payment, one sum a month.
        TileCaption(personal(instalmentLine(pay, today) ?: if (extra != null && isAnnual(pay)) "раз на рік" else billingLine(pay)), shown)
        if (isInstalment(pay)) {
            InstalmentBar(instalmentsBehind(pay, today), pay.instalments, inkOn(shown), Modifier.padding(top = Space.xs))
        }
        // Cancelled with charges still to come before what was paid for runs out.
        cancelledLine(pay, today)?.let { TileCaption(it, shown) }
        // The date the free ride ends: the one fact about this expense that expires.
        trialLabel(pay, today)?.let { free ->
            Text(
                // «150 ₴ до 1 лютого» is the owner's price; «безкоштовно до …» has none.
                personal(free),
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                fontWeight = Type.strong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        extra?.invoke(shown)
        // What it used to cost: the whole defence against a quiet raise.
        amountMoveLine(pay)?.let { TileCaption(personal(it), shown) }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(Space.sm))
        SplitFigure(personalFigure(amountLabel(pay.amount, pay.currency)), 20.sp)
        if (pay.currency == USD && usdSell > 0) {
            TileCaption(personal("≈ ${approxMoney(pay.amount * usdSell)}"), shown, maxLines = 1)
        }
    }
}

/**
 * What survives the standing costs this month, on the dark ground beside the
 * sand «Сплачено». Tapping it edits the income, as the row it replaced did.
 */
@Composable
fun MonthLeftTile(
    bar: Committed,
    modifier: Modifier = Modifier,
    /** «На життя»'s share of the income: its own 🛒 segment, after the payments'. */
    lifeShare: Float = 0f,
    /** The caption when life or the funds are in the month — see [monthBarDetail]. */
    detail: String? = null,
    onEditIncome: () -> Unit
) {
    BentoTile(SurfaceRaised, modifier, onClick = onEditIncome, onClickLabel = "Змінити дохід") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (bar.state) {
                    CommittedState.KNOWN -> "Лишається"
                    CommittedState.OVERSPENT -> "Бракує"
                    CommittedState.UNKNOWN -> "Уже зайнято"
                },
                Modifier.weight(1f),
                color = TextSecondary,
                fontSize = Type.captionSize
            )
            Icon(Icons.Default.Edit, null, Modifier.size(16.dp), tint = TextSecondary)
        }
        Spacer(Modifier.height(Space.xs))
        when (bar.state) {
            CommittedState.KNOWN -> SplitFigure(personalFigure(money(bar.left)), 22.sp)
            CommittedState.OVERSPENT -> SplitFigure(personalFigure(money(-bar.left)), 22.sp, colour = Negative)
            CommittedState.UNKNOWN -> SplitFigure(personalFigure(money(bar.committed)), 22.sp)
        }
        // No bar without an income: its denominator would be invented.
        if (bar.state != CommittedState.UNKNOWN) {
            Spacer(Modifier.height(Space.sm))
            val grown = entranceFraction(bar.share.coerceIn(0f, 1f), delayMs = 300L).coerceIn(0f, 1f)
            // Life rides after the payments in the same lime, lighter: one accent,
            // two parts of what the month is spoken for by.
            val life = if (bar.share > 0f) (lifeShare / bar.share).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(6.dp).background(HairLine, Radius.pill)) {
                Row(Modifier.fillMaxWidth(grown).height(6.dp).clip(Radius.pill)) {
                    if (life < 1f) {
                        Box(
                            Modifier
                                .weight(1f - life)
                                .height(6.dp)
                                .background(if (bar.state == CommittedState.OVERSPENT) Negative else Accent)
                        )
                    }
                    if (life > 0f) {
                        Box(
                            Modifier
                                .weight(life)
                                .height(6.dp)
                                .background((if (bar.state == CommittedState.OVERSPENT) Negative else Accent).copy(alpha = 0.45f))
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(Space.sm))
        TileCaption(
            if (bar.state == CommittedState.UNKNOWN) "Торкніться, щоб вказати дохід" else personal(detail ?: committedDetail(bar)),
            SurfaceRaised,
            maxLines = 3
        )
    }
}

@Composable
fun AddPaymentSheet(
    close: () -> Unit,
    /** What a letter about a subscription said, to start the form from — SubscriptionText.kt. */
    prefill: SubscriptionDraft? = null,
    add: (Pay) -> Unit
) {
    // The chips fill the name in, they are not the name. Two subscriptions are
    // rarely the same subscription, so the field is always present and always
    // editable: tapping a chip simply types the word for you.
    val presets = listOf("Оренда квартири", "Комуналка", "Інтернет", "Мобільний", "Підписка")
    var name by remember { mutableStateOf(presets.first()) }
    var amount by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("1") }
    var currency by remember { mutableStateOf(UAH) }
    var warnDays by remember { mutableIntStateOf(DEFAULT_WARN_DAYS) }
    var trialEnd by remember { mutableLongStateOf(0L) }
    // The price until [trialEnd]: empty is a free trial, as before. See [priceOn].
    var promo by remember { mutableStateOf("") }
    var billingMonth by remember { mutableIntStateOf(0) }
    var emoji by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf(false) }
    var planCount by remember { mutableStateOf("") }
    var planDone by remember { mutableStateOf("0") }
    // A letter — shared into the app, or pasted below — fills what it says and
    // leaves the rest as it is. Nothing is saved until «Додати».
    var fromLetter by remember { mutableStateOf<String?>(null) }
    fun fill(draft: SubscriptionDraft) {
        name = draft.name
        if (draft.amount > 0.0) amount = amountText(draft.amount)
        draft.day?.let { day = it.toString() }
        currency = draft.currency
        billingMonth = draft.billingMonth
        trialEnd = draft.trialEnd
        plan = false
        // «199 ₴/міс» kept on one line: a break inside it reads as two figures.
        fromLetter = "З листа: ${draftLine(draft)}".replace(" ₴", "\u00A0₴").replace("/", "/\u2060")
    }
    LaunchedEffect(prefill) { prefill?.let(::fill) }
    // A free trial or a yearly fee is a decision as well as a charge, and a day's
    // notice at nine in the morning is often too late to make it. So the notice
    // moves to three days the moment either is set — visibly, on the chips, and
    // only while it is still the default, so a choice already made is kept.
    LaunchedEffect(trialEnd > 0L || billingMonth > 0) {
        if ((trialEnd > 0L || billingMonth > 0) && warnDays == DEFAULT_WARN_DAYS) {
            warnDays = LONG_NOTICE_DAYS
        }
    }
    val today = remember { LocalDate.now() }
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
                        warnDays,
                        // The opening figure, dated. An expense whose history starts
                        // here can later say when its old price started; one seeded
                        // at the first edit instead can only say that it did.
                        listOf(PricePoint(value, today.toEpochDay())),
                        trialEnd,
                        billingMonth,
                        emoji,
                        instalments = planTotal(plan, planCount),
                        instalmentStart = planStart(plan, planCount, planDone, day, today),
                        promoPrice = if (trialEnd > 0L) parseAmount(promo).coerceAtLeast(0.0) else 0.0
                    )
                )
            }
        },
        onDismiss = close
    ) {
        // A letter copied from the mail is easier to paste than to share.
        PasteLetterButton(fromLetter, ::fill) { fromLetter = it }
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
        // Follows the name as it is typed, until somebody picks one.
        EmojiField(emoji, payEmoji(name)) { emoji = it }
        CurrencySegments(currency) { currency = it }
        NumberField(if (currency == USD) "Сума, $" else "Сума, ₴", amount) { amount = it }
        // A plan is monthly by definition, so the rhythm choice steps aside.
        if (!plan) BillingSegments(billingMonth, today) { billingMonth = it }
        NumberField("День оплати", day) { day = it }
        InstalmentFields(
            on = plan,
            count = planCount,
            done = planDone,
            day = day.toIntOrNull()?.coerceIn(1, 31) ?: 1,
            today = today,
            setOn = {
                plan = it
                if (it) billingMonth = 0
            },
            setCount = { planCount = it },
            setDone = { planDone = it }
        )
        PromoField(trialEnd, promo, parseAmount(amount), currency, today, { trialEnd = it }, { promo = it })
        // Only a free period hides the first charge; a promo charges from its first date.
        if (parseAmount(promo) <= 0.0) {
            firstChargeNote(day.toIntOrNull()?.coerceIn(1, 31) ?: 1, trialEnd, today, billingMonth)
                ?.let { note ->
                    Text(
                        note,
                        Modifier.padding(top = Space.xs),
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine
                    )
                }
        }
        WarnDaysChips(warnDays) { warnDays = it }
    }
}

@Composable
fun OrdersScreen(
    items: List<Order>,
    save: (List<Order>) -> Unit,
    /** As on the wishlist: the list as it is when a carrier's answer lands. */
    update: ((List<Order>) -> List<Order>) -> Unit,
    context: Context,
    adding: Boolean,
    setAdding: (Boolean) -> Unit,
    store: Store,
    /**
     * Which parcel's page is open, held by id so the page keeps showing the live
     * parcel after a status refresh rewrites it.
     */
    opened: String?,
    setOpened: (String?) -> Unit,
    /** Owned above this screen, which is where the undo and the bin live. */
    onDelete: (Order) -> Unit,
    /** A number shared into the app, to open the add form with. */
    sharedTracking: String? = null,
    onSharedTrackingUsed: () -> Unit = {}
) {
    var tracking by remember { mutableStateOf<Order?>(null) }
    var closing by remember { mutableStateOf<Order?>(null) }
    // The purchase whose «Повертаю» sheet is up.
    var returning by remember { mutableStateOf<Order?>(null) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Read once, so a session that crosses midnight cannot change its mind partway
    // down the list about which scans count as "сьогодні".
    val today = remember { LocalDate.now() }
    val day = today.toEpochDay()
    // A closed purchase is history, not a parcel: it is not asked about again, and
    // it does not sit in the list of things still on their way.
    val open = items.filter { it.archivedDay == 0L }
    // A purchase being sent back is filed, but it is an errand again until the money
    // is back, so it stands with the parcels rather than in the archive.
    val goingBack = items.filter { isReturning(it) }.sortedBy { it.refund?.startedDay ?: 0L }
    val archived = items.filter { it.archivedDay > 0L && !isReturning(it) }.sortedByDescending { it.archivedDay }
    val trackable = open.count { isAutoTracked(it) } + goingBack.count { followsReturn(it) }
    // Shut unless opened, and remembered like every other fold. Finished purchases
    // are the record, not the errand, and they sat open under the parcels in flight.
    var archiveOpen by remember { mutableStateOf(store.sectionOpen(SECTION_ORDER_ARCHIVE)) }
    // «На гарантії»: the archive narrowed to what is still covered. Not remembered —
    // it is a question asked now, not a way of looking at the archive.
    var warrantyOnly by remember { mutableStateOf(false) }
    val entrance = LocalEntrance.current

    // A waybill shared in that is already a parcel here opens that parcel, rather
    // than starting a second copy of it in the add form.
    LaunchedEffect(sharedTracking) {
        val number = sharedTracking ?: return@LaunchedEffect
        knownParcel(items, number)?.let { known ->
            onSharedTrackingUsed()
            setOpened(known.id)
        }
    }

    fun checkAll() {
        scope.launch {
            checking = true
            message = null
            var moved = 0
            val now = System.currentTimeMillis()
            val myPhone = ParcelPrefs(context).phone()
            // Answers are collected by id and laid onto the list as it is when they
            // are all in, so a parcel added, edited or deleted during the check
            // stays the way it was left — see Merge.kt.
            val answers = items
                .filter { it.archivedDay == 0L && isAutoTracked(it) }
                .mapNotNull { order ->
                    val status = runCatching { parcelStatus(order.tracking, phoneFor(order, myPhone)) }.getOrNull()
                        ?: return@mapNotNull null
                    if (status.stage.isNotBlank() && status.stage != order.status) moved++
                    order.id to status
                }
                .toMap()
            // The waybills going back, each into its own return record.
            val backAnswers = items
                .filter { followsReturn(it) }
                .mapNotNull { order ->
                    val number = order.refund?.tracking ?: return@mapNotNull null
                    val status = runCatching { parcelStatus(number, myPhone) }.getOrNull()
                        ?: return@mapNotNull null
                    if (status.stage == RECEIVED) moved++
                    order.id to status
                }
                .toMap()
            update { list ->
                list.map { order ->
                    answers[order.id]?.let { applyStatus(order, it, now) }
                        ?: backAnswers[order.id]?.let { applyReturnStatus(order, it, now, LocalDate.now().toEpochDay()) }
                        ?: order
                }
            }
            runCatching { refreshPickupPoints(context, store.orders()) }
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
                BusyMark()
            } else {
                Icon(Icons.Default.Sync, "Перевірити статуси", tint = TextSecondary)
            }
        }
    }
    val listState = rememberLazyListState()
    // Keyed on the id rather than on the parcel, so a status refresh landing while
    // the page is open rewrites the rows in place instead of cross-fading the whole
    // page out and back in under the finger that asked for it.
    AnimatedContent(opened, label = "parcel") { openedId ->
    val openedOrder = openedId?.let { id -> items.firstOrNull { it.id == id } }
    if (openedOrder != null) {
        OrderDetailScreen(
            order = openedOrder,
            store = store,
            context = context,
            onBack = { setOpened(null) },
            onChange = { changed -> update { now -> now.map { if (it.id == changed.id) changed else it } } },
            onApply = { change -> update { now -> now.map { if (it.id == openedOrder.id) change(it) else it } } },
            onEditTracking = { tracking = openedOrder },
            onDelete = { onDelete(openedOrder); setOpened(null) },
            onClose = { closing = openedOrder },
            onEditReturn = { returning = openedOrder },
            familiar = familiarPoint(items, openedOrder)
        )
    } else {
    Box {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = navClearance() + Space.fabClearance)
        ) {
            // Item zero is the header alone: that is the block the compact bar watches.
            item {
                ScreenHeader(
                    "Покупки",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            checkAction()
                            AddButton("Додати покупку") { setAdding(true) }
                        }
                    }
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
            if (open.isEmpty() && archived.isEmpty() && goingBack.isEmpty()) {
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
            parcelsAtAGlance(open)?.let { glance ->
                item(key = "parcels-glance") {
                    ParcelsSummaryTile(
                        glance,
                        Modifier
                            .revealOnEnter(0, entrance)
                            .padding(horizontal = Space.screen)
                            .padding(bottom = Space.md)
                    )
                }
            }
            // A tile per purchase. A parcel used to be a card a third of the screen
            // tall — a photo, the whole four-stop rail with its labels and four
            // buttons — so two fitted on a screen. The rail and the buttons live on
            // the parcel's own page; the tile says where it is in four segments and
            // opens that page.
            val colours = tileColours(open.map { it.id })
            itemsIndexed(open, key = { _, order -> order.id }) { index, order ->
                ParcelRow(
                    order = order,
                    today = today,
                    // Trouble is pink whatever the name would have given it.
                    colour = if (order.problem) TilePink else colours[index],
                    modifier = Modifier.animateItem().revealOnEnter(index + 1, entrance).padding(bottom = Space.md),
                    onOpen = { setOpened(order.id) },
                    onClose = { closing = order }
                )
            }
            // Things on their way back, after the things on their way here. Each is
            // an errand until its money is back — see ParcelsMore.kt.
            val backColours = tileColours(goingBack.map { "back-${it.id}" })
            itemsIndexed(goingBack, key = { _, order -> "back-${order.id}" }) { index, order ->
                ReturnRow(
                    order = order,
                    today = day,
                    colour = if (order.refund?.let { refundOverdueDays(it, day) } != null) TilePink else backColours[index],
                    modifier = Modifier.animateItem().revealOnEnter(open.size + index + 1, entrance).padding(bottom = Space.md),
                    onOpen = { setOpened(order.id) },
                    onShopGot = { update { now -> now.map { if (it.id == order.id) shopReceived(it, day) else it } } },
                    onMoneyBack = { update { now -> now.map { if (it.id == order.id) moneyBack(it, day) else it } } }
                )
            }
            if (archived.isNotEmpty()) {
                item {
                    // Folded, with the tally on the shut heading: the one line in the
                    // app that answers whether watching prices was worth doing still
                    // shows without opening anything, and the cards behind it stop
                    // standing in the way of the parcels still on their way.
                    CollapsibleSection(
                        title = "Архів покупок",
                        summary = purchasesLabel(archived.size) + " · " + personal(
                            purchaseTallyLine(
                                purchaseTally(
                                    // A purchase given back was not kept, so it is not
                                    // a purchase made on time or in a hurry.
                                    archived.filter { countsAsBought(it) }
                                        .map { purchaseReview(it.paid, it.lowestSeen, it.uses) }
                                )
                            )
                        ),
                        open = archiveOpen,
                        onToggle = {
                            archiveOpen = it
                            store.saveSectionOpen(SECTION_ORDER_ARCHIVE, it)
                        },
                        icon = Icons.Default.Inventory2
                    ) {}
                }
            }
            val covered = archived.count { onWarranty(it, day) }
            if (archived.isNotEmpty() && archiveOpen && covered > 0) {
                item(key = "archive-filter") {
                    Row(
                        Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xs),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm)
                    ) {
                        FilterChip(selected = !warrantyOnly, onClick = { warrantyOnly = false }, label = { Text("Усі") })
                        FilterChip(
                            selected = warrantyOnly,
                            onClick = { warrantyOnly = true },
                            label = { Text("На гарантії · $covered") },
                            leadingIcon = { EmojiGlyph("🛡️", 16.dp) }
                        )
                    }
                }
            }
            if (archived.isNotEmpty() && archiveOpen) {
                val shownArchive = if (warrantyOnly && covered > 0) archived.filter { onWarranty(it, day) } else archived
                items(shownArchive, key = { "archived-${it.id}" }) { order ->
                    ArchivedPurchase(
                        order = order,
                        onEdit = { closing = order },
                        // Through the bin like every other deletion: a finished
                        // purchase is the one record in the app that cannot be
                        // rebuilt, because the price it is judged against was only
                        // ever observed while the thing was still a wish.
                        onDelete = { onDelete(order) },
                        onKeep = {
                            update { now -> now.map { if (it.id == order.id) it.copy(returnBy = 0L) else it } }
                        },
                        onReturn = { returning = order },
                        onDelight = { answer ->
                            update { now -> now.map { if (it.id == order.id) answerDelight(it, answer, day) else it } }
                        },
                        onAgain = { yes ->
                            update { now -> now.map { if (it.id == order.id) answerAgain(it, yes) else it } }
                        },
                        onUndoRefund = {
                            update { now -> now.map { if (it.id == order.id) undoMoneyBack(it) else it } }
                        }
                    )
                }
            }
        }
        CollapsingTitle(
            "Покупки",
            listState,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    checkAction()
                    AddButton("Додати покупку") { setAdding(true) }
                }
            }
        )
    }
    }
    }
    // Outside the swap, so a sheet opened from the parcel page is not torn down
    // by the page closing underneath it.
    closing?.let { selected ->
        CloseOrderSheet(
            selected,
            { closing = null },
            // «Як на фото? Ні»: filed, and straight into «Повертаю».
            onReturn = { filed ->
                update { now -> now.map { if (it.id == filed.id) filed else it } }
                closing = null
                setOpened(null)
                returning = filed
            }
        ) { closed ->
            update { now -> now.map { if (it.id == closed.id) closed else it } }
            closing = null
            // A closed purchase is history and leaves the list of things in
            // flight, so the page about where it is has nothing left to say.
            setOpened(null)
        }
    }
    returning?.let { selected ->
        // The purchase as it is now, so a sheet opened before a refresh landed
        // does not save the stale copy back over it.
        val current = items.firstOrNull { it.id == selected.id } ?: selected
        ReturnSheet(current, day, { returning = null }) { changed ->
            update { now -> now.map { if (it.id == changed.id) changed else it } }
            returning = null
        }
    }
    // A shared waybill that is already a parcel opens it instead — see above.
    val sharedNew = sharedTracking?.takeIf { knownParcel(items, it) == null }
    if (adding || sharedNew != null) {
        AddOrderSheet(
            close = { setAdding(false); onSharedTrackingUsed() },
            initialTracking = sharedNew.orEmpty()
        ) { added ->
            update { now -> now + added }
            setAdding(false)
            onSharedTrackingUsed()
        }
    }
    tracking?.let { selected ->
        TrackingDialog(selected, { tracking = null }) { number, digital, name, phone ->
            update { now ->
                now.map {
                    if (it.id == selected.id) {
                        it.copy(tracking = number, digital = digital, name = name, recipientPhone = phone)
                    } else {
                        it
                    }
                }
            }
            tracking = null
        }
    }
}

/** A label and a figure on one dotted row, drawn only when there is a figure. */
@Composable
private fun Fact(label: String, value: String, alarm: Boolean = false) {
    if (value.isBlank()) return
    LeaderRow(label, value, Modifier.padding(horizontal = Space.screen), alarm)
}

/**
 * A label over its value, for the ones too long for a dotted row.
 *
 * A warehouse address is most of a line by itself, and pushed to the right of a
 * leader it would wrap into a ragged column under the dots.
 */
@Composable
private fun FactBlock(label: String, value: String) {
    if (value.isBlank()) return
    Column(Modifier.padding(horizontal = Space.screen).padding(vertical = Space.xs)) {
        Text(label, color = TextSecondary, fontSize = Type.captionSize)
        Text(
            value,
            color = TextPrimary,
            fontSize = Type.bodySize,
            lineHeight = Type.bodyLine
        )
    }
}

/** A sentence under a section, for the things that are explanations rather than facts. */
@Composable
private fun FactNote(text: String, color: Color = TextDisabled) {
    Text(
        text,
        Modifier.padding(horizontal = Space.screen).padding(top = Space.xs, bottom = Space.xs),
        color = color,
        fontSize = Type.captionSize,
        lineHeight = Type.captionLine
    )
}

/** Where the folded sections of the parcel page remember their state. */
const val SECTION_PARCEL_BOX = "parcelbox"
const val SECTION_PARCEL_PAY = "parcelpay"
const val SECTION_PARCEL_OPTIONS = "parcelopts"

/**
 * Everything the carrier will say about one parcel, on a page of its own.
 *
 * **A screen rather than a sheet, and that is a considered call.** Every
 * [FormSheet] in this app is a form: a title, some fields, «Скасувати» and a
 * confirm button, dismissed by finishing or abandoning a task. This is the
 * opposite kind of surface — nothing is being entered, there is no confirm, and
 * the content is a dozen rows across five groups plus a list that grows for as
 * long as the parcel is in transit. A sheet would put all of that in a scroller
 * inside the scroller it was opened from, with a drag handle at the top that
 * competes with the list for the same vertical gesture, and with the purchases
 * list dimmed but still visible behind it as though the parcel were a modal
 * interruption of itself. The two reading surfaces this app already has — the
 * wish page and the recap — are both full screens, and this is the third of that
 * kind: something you open, read, scroll, act on, and come back from. So it
 * follows them, down to the back arrow, the overline, and the system back button
 * closing the page before it leaves the app.
 *
 * **What is not here.** `getStatusDocuments` returns a hundred and twenty-eight
 * fields. Left out: every `Ref…` identifier, the sender's internal paperwork, the
 * recipient's own name and phone number (he is the recipient), loyalty cards,
 * masked card numbers, the money-transfer ledger, marketplace tokens, and the
 * redelivery block, which is written for the shop rather than for the person
 * waiting. What is left is the parcel as an errand: where it is going, when it
 * last moved, what it weighs, who pays, and what can still be done with it.
 */
@Composable
fun OrderDetailScreen(
    order: Order,
    store: Store,
    context: Context,
    onBack: () -> Unit,
    onChange: (Order) -> Unit,
    /** A change applied to this parcel as it is when an answer lands. */
    onApply: ((Order) -> Order) -> Unit,
    onEditTracking: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    /** «Змінити повернення»: the return sheet for this purchase. */
    onEditReturn: () -> Unit = {},
    /** The owner has collected from this pickup point before — see [familiarPoint]. */
    familiar: Boolean = false
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var boxOpen by remember { mutableStateOf(store.sectionOpen(SECTION_PARCEL_BOX)) }
    var payOpen by remember { mutableStateOf(store.sectionOpen(SECTION_PARCEL_PAY)) }
    var optionsOpen by remember { mutableStateOf(store.sectionOpen(SECTION_PARCEL_OPTIONS)) }
    val trackable = isAutoTracked(order)
    // A purchase on its way back: the page leads with the return, not the delivery.
    val goingBack = isReturning(order)
    // Everything below the rail is one carrier's answer. On anything that carrier
    // never answered for it would be a page of blanks — see [carrierSectionsApply].
    val carrier = carrierSectionsApply(order)
    val trackingPage = trackingSite(order)
    // Read once, so a page left open across midnight cannot start disagreeing with
    // itself about which of its dates is "сьогодні".
    val now = remember { LocalDateTime.now() }
    val today = remember { now.toLocalDate() }
    val zone = remember { java.time.ZoneId.systemDefault() }
    val details = order.details
    val parcelPrefs = remember { ParcelPrefs(context) }
    // The directory's word on the pickup point, a week old at most.
    var point by remember(details.warehouseRef) { mutableStateOf(parcelPrefs.point(details.warehouseRef)) }
    val myPhone = remember { parcelPrefs.phone() }

    fun refresh() {
        scope.launch {
            busy = true
            val phone = phoneFor(order, parcelPrefs.phone())
            val status = if (trackable && !goingBack) {
                runCatching { parcelStatus(order.tracking, phone) }.getOrNull()
            } else {
                null
            }
            // The waybill going back, into the return's own record.
            val back = order.refund?.takeIf { followsReturn(order) }?.let { refund ->
                runCatching { parcelStatus(refund.tracking, parcelPrefs.phone()) }.getOrNull()
            }
            busy = false
            if (status == null && back == null) {
                message = "Не вдалося отримати статус"
            } else {
                message = null
                val at = System.currentTimeMillis()
                onApply { current ->
                    val delivered = status?.let { applyStatus(current, it, at) } ?: current
                    back?.let { applyReturnStatus(delivered, it, at, LocalDate.now().toEpochDay()) } ?: delivered
                }
            }
        }
    }

    // A parcel added before this page existed has none of these fields stored, and
    // opening it to a page of blanks would read as a carrier that knows nothing.
    // One fetch fills it in; afterwards the button above does. A parcel waiting at
    // a point stored before the point's id was kept is asked once for it as well.
    LaunchedEffect(order.id) {
        val noPointId = order.status == AT_BRANCH && order.archivedDay == 0L && details.warehouseRef.isBlank()
        if (trackable && !goingBack && (details.isEmpty || noPointId)) refresh()
    }
    // The pickup point's hours, asked once a week per point.
    LaunchedEffect(details.warehouseRef, order.status) {
        val ref = pointToAsk(order) ?: return@LaunchedEffect
        if (pointFresh(point, System.currentTimeMillis())) return@LaunchedEffect
        fetchPickupPoint(ref)?.let { fresh ->
            parcelPrefs.savePoint(fresh)
            point = fresh
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = navClearance() + Space.huge)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = TextPrimary)
                }
                Text(
                    when {
                        goingBack -> "ПОВЕРНЕННЯ"
                        order.digital -> "ПОКУПКА"
                        else -> "ПОСИЛКА"
                    },
                    color = Accent,
                    fontSize = Type.overlineSize,
                    fontWeight = Type.strong,
                    letterSpacing = Type.overlineTracking
                )
                Spacer(Modifier.weight(1f))
                if ((trackable && !goingBack) || followsReturn(order)) {
                    IconButton(onClick = { refresh() }, enabled = !busy) {
                        if (busy) BusyMark() else Icon(Icons.Default.Sync, "Оновити", tint = TextSecondary)
                    }
                }
                IconButton(onEditTracking) {
                    Icon(
                        Icons.Default.Edit,
                        if (order.digital) "Змінити покупку" else "Трек-номер",
                        tint = TextSecondary
                    )
                }
                IconButton(onDelete) {
                    Icon(Icons.Default.DeleteOutline, "Видалити", tint = TextSecondary)
                }
            }

            if (order.image.isNotBlank()) {
                PhotoHeader(
                    imageUrl = order.image,
                    description = order.name,
                    modifier = Modifier.padding(horizontal = Space.screen),
                    height = 200.dp
                ) { imageModifier ->
                    AsyncImage(
                        order.image, order.name,
                        imageModifier.clip(Radius.md),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.height(Space.md))
            } else {
                EmojiHeader(shownEmoji(order), tileColours(listOf(order.id)).first())
                Spacer(Modifier.height(Space.md))
            }

            Column(Modifier.padding(horizontal = Space.screen)) {
                Text(
                    order.name,
                    fontSize = Type.screenTitleSize,
                    lineHeight = Type.screenTitleLine,
                    letterSpacing = Type.screenTitleTracking,
                    fontWeight = FontWeight.Black
                )
                // Applied to the parcel as it is, like every other change on this
                // page, so a status landing at the same moment is not undone.
                EmojiField(order.emoji, orderEmoji(order.name, order.digital)) { picked ->
                    onApply { it.copy(emoji = picked) }
                }
                if (order.price > 0) {
                    Text(
                        money(order.price),
                        fontSize = Type.bodySize,
                        fontWeight = Type.strong,
                        style = Tabular,
                        modifier = Modifier.padding(top = Space.xs)
                    )
                }
                if (order.statusDetail.isNotBlank()) {
                    Text(
                        order.statusDetail,
                        color = TextPrimary,
                        fontSize = Type.bodySize,
                        lineHeight = Type.bodyLine,
                        modifier = Modifier.padding(top = Space.sm)
                    )
                }
                if (order.tracking.isNotBlank()) {
                    Text(
                        "Трек: ${order.tracking}",
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        modifier = Modifier.padding(top = Space.xs)
                    )
                }
                if (order.problem) {
                    Text(
                        problemNote(order.statusCode),
                        color = Negative,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine,
                        modifier = Modifier.padding(top = Space.xs)
                    )
                }
                // About following the delivery; on a return it would read as about
                // the waybill going back, which the return block speaks for.
                untrackedNote(order)?.takeIf { !goingBack }?.let { note ->
                    Text(
                        note,
                        color = TextDisabled,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine,
                        modifier = Modifier.padding(top = Space.xs)
                    )
                }
            }
            message?.let {
                FactNote(it, Negative)
            }
            // A refusal, a stopped storage, a door nobody opened — none of them is
            // one of the four dots, so the dot below stays where the parcel really
            // was. Saying that is the difference between a parcel visibly stuck and
            // a parcel that looks like it is still fine where it is.
            if (order.problem && !goingBack) {
                FactNote(STAGE_HELD_NOTE, TextSecondary)
            }
            if (goingBack) {
                // The return leads: its own three stops, what is owed, the waybill
                // going back. The delivery's own story stays below, unchanged.
                val day = today.toEpochDay()
                ReturnBlock(
                    order,
                    day,
                    Modifier.padding(horizontal = Space.screen).padding(top = Space.md),
                    onShopGot = { onApply { shopReceived(it, day) } },
                    onMoneyBack = { onApply { moneyBack(it, day) } },
                    onEdit = onEditReturn
                )
            } else {
                if (!order.digital) {
                    StageRail(
                        stages = PARCEL_STAGES,
                        current = order.status,
                        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
                        label = { stageLabel(it, order.statusCode) }
                    ) { picked -> onChange(order.copy(status = picked)) }
                }
                // Up here rather than at the foot of the page: on a parcel the app does
                // not follow, and on a download, it is most of what the page is for.
                CloseOrderButton(
                    order,
                    Modifier.padding(horizontal = Space.screen).padding(top = Space.sm)
                ) { onClose() }
            }
            trackingPage?.let { site ->
                TextButton(
                    { openLink(context, site.url) },
                    Modifier.padding(horizontal = Space.sm).padding(top = Space.xs)
                ) { Text("Відстежити на сайті ${site.name} ↗") }
            }
        }

        // The block this whole screen was asked for. Two times that look alike and
        // are not: one is the parcel moving, one is the app asking.
        if (carrier) item {
            Card(
                Modifier.padding(horizontal = Space.screen).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceLow),
                shape = Radius.md
            ) {
                Column(Modifier.padding(Space.lg)) {
                    LeaderRow(SCAN_LABEL, scanValue(details.scannedAt, today))
                    LeaderRow(ASKED_LABEL, askedValue(order.checkedAt))
                    standstillNote(details.scannedAt, now, order.status)?.let {
                        Text(
                            it,
                            color = TextSecondary,
                            fontSize = Type.captionSize,
                            fontWeight = Type.strong,
                            modifier = Modifier.padding(top = Space.sm)
                        )
                    }
                    Text(
                        TIMES_NOTE,
                        color = TextDisabled,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine,
                        modifier = Modifier.padding(top = Space.sm)
                    )
                }
            }
        }

        // Nine rows once, and four of them said nothing the rows beside them had not
        // already said: two full-length addresses that were the branch with the city
        // glued on the front, the branch number that the pickup point's own name
        // quotes, and the kind of place that the same name starts with. They are
        // dropped by rule rather than by deletion — [addressBeyond] and
        // [alreadySaid] check each one against its neighbour, so a courier delivery,
        // where the address really is the only line with a street on it, still
        // prints it.
        if (carrier) item {
            SectionTitle("Куди їде", Icons.Default.Place)
            Fact("Звідки", details.citySender)
            FactBlock("Відділення відправника", details.warehouseSender)
            FactBlock(
                "Адреса відправника",
                addressBeyond(
                    details.warehouseSenderAddress,
                    details.warehouseSender,
                    details.citySender
                )
            )
            Fact("Куди", details.cityRecipient)
            Fact(
                "Відділення",
                details.warehouseNumber
                    .takeIf { !alreadySaid(it, details.warehouseRecipient) }
                    ?.let { "№$it" }.orEmpty()
            )
            FactBlock("Точка видачі", details.warehouseRecipient)
            FactBlock(
                "Адреса",
                addressBeyond(
                    details.warehouseRecipientAddress,
                    details.warehouseRecipient,
                    details.cityRecipient
                )
            )
            // The point's hours and what it has, while a parcel waits there. At a
            // point collected from before, only «скоро зачиняється» — see ParcelsMore.kt.
            point?.takeIf { pointToAsk(order) != null }?.let { known ->
                PointChipsRow(
                    pickupChips(known, LocalDateTime.now(), familiar),
                    Modifier.padding(horizontal = Space.screen).padding(vertical = Space.xs)
                )
            }
            Fact(
                "Тип точки",
                warehouseCategoryLabel(details.warehouseCategory)
                    .takeIf { !alreadySaid(it, details.warehouseRecipient) }
                    .orEmpty()
            )
            Fact("Спосіб доставки", serviceTypeLabel(details.serviceType))
            // A locker and a counter are different errands, so this is a sentence
            // rather than the value of a field called CategoryOfWarehouse.
            collectionNote(details.warehouseCategory, order.paidStorageFrom > 0)?.let {
                FactNote(it, TextSecondary)
            }
            // Nova Poshta's public method answers with where the parcel is and
            // nothing about how it got there. Saying so once here is the honest
            // alternative to drawing a line between two cities — and saying that
            // their own app does show those stops is what stops this page reading
            // as the broken one when the two are held side by side.
            FactNote(NO_STOPS_NOTE)
        }

        if (carrier) item {
            SectionTitle("Коли", Icons.Default.Schedule)
            Fact(
                "Передано перевізнику",
                details.createdAt?.let { momentLabel(it, today) }.orEmpty()
            )
            Fact(
                "Обіцяють доставити",
                order.scheduledDelivery.takeIf { it > 0 && order.status != RECEIVED }
                    ?.let { formatDate(LocalDate.ofEpochDay(it)) }.orEmpty()
            )
            if (order.paidStorageFrom > 0) {
                val left = freeStorageDaysLeft(
                    LocalDate.ofEpochDay(order.paidStorageFrom),
                    today
                ) ?: 0
                Fact(
                    "Безкоштовне зберігання",
                    if (left > 0) daysLabel(left) else "закінчилось",
                    alarm = left <= 2
                )
                FactNote("платне з ${formatDate(LocalDate.ofEpochDay(order.paidStorageFrom))}")
            }
            Fact(
                "Перевізник оновив запис",
                details.trackingUpdatedAt?.let { momentLabel(it, today) }.orEmpty()
            )
        }

        val weight = details.factualWeight.takeIf { it > 0 } ?: details.documentWeight
        val boxSummary = listOfNotNull(
            weightLabel(weight).takeIf { weight > 0 },
            seatsLabel(details.seats).takeIf { details.seats > 0 },
            cargoTypeLabel(details.cargoType).takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { "Перевізник ще не зважив" }
        if (carrier) item {
            CollapsibleSection("Сама посилка", boxSummary, boxOpen, {
                boxOpen = it
                store.saveSectionOpen(SECTION_PARCEL_BOX, it)
            }, icon = Icons.Default.Inventory2) {
                Column {
                    Fact("Фактична вага", weightLabel(details.factualWeight).takeIf { details.factualWeight > 0 }.orEmpty())
                    Fact("Заявлена вага", weightLabel(details.documentWeight).takeIf { details.documentWeight > 0 }.orEmpty())
                    Fact("Об'ємна вага", weightLabel(details.volumeWeight).takeIf { details.volumeWeight > 0 }.orEmpty())
                    Fact("Місць", seatsLabel(details.seats).takeIf { details.seats > 0 }.orEmpty())
                    Fact("Тип відправлення", cargoTypeLabel(details.cargoType))
                    // Only with a phone number on the request — see [parcelStatus].
                    FactBlock("Відправник", details.sender)
                }
            }
        }

        val paySummary = listOfNotNull(
            money(order.amountToPay).takeIf { order.amountToPay > 0 },
            payerLabel(details.payerType).takeIf { it.isNotBlank() }?.let { "платить $it" }
        ).joinToString(" · ").ifBlank { "Нічого доплачувати" }
        if (carrier) item {
            // Cash on delivery is the owner's money to hand over (Privacy.kt).
            CollapsibleSection("Оплата", personal(paySummary), payOpen, {
                payOpen = it
                store.saveSectionOpen(SECTION_PARCEL_PAY, it)
            }, icon = Icons.Default.Payments) {
                Column {
                    Fact(
                        "До сплати при отриманні",
                        personalFigure(money(order.amountToPay)).takeIf { order.amountToPay > 0 }.orEmpty()
                    )
                    // The next three arrive only when the request carries the
                    // recipient's phone; without it they are blank and not drawn.
                    Fact("Післяплата за товар", personalFigure(money(details.goodsToPay)).takeIf { details.goodsToPay > 0 }.orEmpty())
                    Fact("Вартість доставки", personalFigure(money(details.deliveryCost)).takeIf { details.deliveryCost > 0 }.orEmpty())
                    Fact(
                        "Платне зберігання",
                        personalFigure(money(details.storageCharged)).takeIf { details.storageCharged > 0 }.orEmpty(),
                        alarm = true
                    )
                    Fact("Доставку оплачує", payerLabel(details.payerType))
                    Fact("Спосіб оплати", paymentMethodLabel(details.paymentMethod))
                    if (trackable && phoneFor(order, myPhone).isEmpty()) {
                        FactNote(PHONE_HINT)
                    }
                }
            }
        }

        // Three lines about things that are done in Nova Poshta's own app rather
        // than in this one, so they arrive folded: the page is opened to find out
        // where the parcel is, and this is the part of it nobody came for. The
        // summary names them, so a shut fold still says what is inside it.
        val options = parcelOptions(details)
        // Shown for every Nova Poshta parcel now, because the one thing everything
        // in it leads to — Nova Poshta's own app — is a button here.
        if (carrier && (options.isNotEmpty() || trackable)) {
            item {
                CollapsibleSection(
                    "Що з нею ще можна зробити",
                    parcelOptionsSummary(details).ifBlank { "відкрити в Новій пошті" },
                    optionsOpen,
                    {
                        optionsOpen = it
                        store.saveSectionOpen(SECTION_PARCEL_OPTIONS, it)
                    },
                    icon = Icons.Default.Tune
                ) {
                    Column {
                        for (option in options) FactNote(option, TextSecondary)
                        if (options.isNotEmpty()) {
                            FactNote("Робиться це в застосунку або на сайті Нової Пошти.")
                        }
                        // The new app, else the old one, else the tracking page.
                        TextButton(
                            { openNovaPoshta(context, order.tracking) },
                            Modifier.padding(horizontal = Space.sm)
                        ) { Text("Відкрити в Новій пошті ↗") }
                    }
                }
            }
        }

        if (carrier) item {
            SectionTitle(SIGHTINGS_TITLE, Icons.Default.Visibility)
            FactNote(sightingsNote(order.sightings, order.checkedAt))
        }
        // Newest first: the question this list is opened with is what happened
        // last, and a journey read from the bottom of the screen upwards is a
        // journey nobody reads.
        if (carrier) itemsIndexed(
            order.sightings.reversed(),
            // Position, not content: two checks in the same millisecond would
            // otherwise collide on a key and take the list down with them.
            key = { position, _ -> "sighting-$position" }
        ) { _, seen ->
            Column(
                Modifier
                    .padding(horizontal = Space.screen)
                    .padding(vertical = Space.sm)
                    .fillMaxWidth()
            ) {
                Text(
                    seen.text,
                    fontSize = Type.bodySize,
                    lineHeight = Type.bodyLine,
                    color = TextPrimary
                )
                Text(
                    "FlowPay побачив це ${sightingLabel(seen, today, zone)}",
                    color = TextDisabled,
                    fontSize = Type.captionSize
                )
            }
        }

        if (order.url.isNotBlank()) {
            item {
                TextButton(
                    { openLink(context, order.url) },
                    Modifier.padding(horizontal = Space.sm).padding(top = Space.sm)
                ) { Text("До магазину ↗") }
            }
        }
    }
}

/**
 * What was paid, month by month, each one opening into what it was made of.
 *
 * What was planned is everywhere in this app; what was paid used to be one fold at
 * the bottom of Огляд, where the owner did not find it when he went looking for
 * "скільки я витратив за вересень" — he went looking on Платежі, which is where the
 * marks are made. So it lives there now, as the second half of that screen.
 *
 * Newest first, because the question is almost always about the month that just
 * ended — which is why that one opens by itself.
 */
@Composable
fun PaidMonths(
    months: List<MonthRecord>,
    pays: List<Pay>,
    marks: List<PaidMark>,
    onToggle: (Pay, String) -> Unit,
    modifier: Modifier = Modifier,
    /** What was really charged for one mark, and whether the expense now costs that. */
    onCharged: (pay: Pay, month: String, amount: Double, updateExpense: Boolean) -> Unit = { _, _, _, _ -> }
) {
    // One at a time, because the point is to read or fix the month you came for,
    // not to audit the year.
    var openMonth by remember { mutableStateOf(months.getOrNull(1)?.month) }
    var correcting by remember { mutableStateOf<Pair<MonthLine, String>?>(null) }
    correcting?.let { (line, month) ->
        ChargedDialog(line, close = { correcting = null }) { amount, update ->
            onCharged(line.pay, month, amount, update)
            correcting = null
        }
    }
    Column(modifier) {
        // Said once, while there is nothing to read yet. The months below still
        // draw, because a month showing nought paid is the thing being explained.
        if (months.all { it.paidCount == 0 }) {
            Text(
                "Позначайте платежі галочкою в розкладі — тут буде видно, скільки насправді " +
                    "пішло за кожен місяць. Пропущений місяць можна позначити і тут.",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(bottom = Space.md)
            )
        }
        months.forEachIndexed { at, record ->
            val open = openMonth == record.month
            // The month's state is its colour, so a year scrolls as a row of
            // answers: mint settled, pink ended with nothing marked, peach partly
            // marked, sky still running, sand nothing was due.
            val colour = when (record.state) {
                MonthState.SETTLED -> TileMint
                MonthState.UNRECORDED -> TilePink
                MonthState.PARTIAL -> TilePeach
                MonthState.NOTHING_DUE -> TileSand
                MonthState.RUNNING -> TileSky
            }
            val ink = inkOn(colour)
            val soft = softInkOn(colour)
            BentoTile(
                colour,
                Modifier.revealOnEnter(at).fillMaxWidth().padding(bottom = Space.md),
                onClick = { openMonth = if (open) null else record.month },
                onClickLabel = if (open) "Згорнути місяць" else "Що сплачено цього місяця"
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        record.title,
                        fontSize = Type.cardTitleSize,
                        fontWeight = Type.medium,
                        modifier = Modifier.weight(1f)
                    )
                    EmojiGlyph(
                        when (record.state) {
                            MonthState.SETTLED -> "✅"
                            MonthState.UNRECORDED -> "❗"
                            MonthState.PARTIAL -> "⏳"
                            MonthState.NOTHING_DUE -> "😴"
                            MonthState.RUNNING -> "🗓️"
                        },
                        28.dp
                    )
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        tint = soft
                    )
                }
                // The figure the screen is opened for, as the loudest thing on
                // the tile. What it was meant to be follows in the line below.
                SplitFigure(
                    personalFigure(totalLabel(record.paid)),
                    26.sp,
                    Modifier.padding(top = Space.xs),
                    colour = if (record.paidCount > 0) ink else soft
                )
                Text(
                    personal(monthRecordLine(record)),
                    // A month that ended with nothing marked is not a month with
                    // nothing to pay, and the difference is worth a colour.
                    color = if (record.state == MonthState.UNRECORDED) TileAlarm else soft,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine,
                    fontWeight = if (record.state == MonthState.UNRECORDED) Type.medium else null
                )
                monthRecordDetail(record).takeIf { it.isNotBlank() }?.let {
                    TileCaption(it, colour, maxLines = 3)
                }
                if (open) {
                    HorizontalDivider(
                        color = ink.copy(alpha = 0.15f),
                        modifier = Modifier.padding(vertical = Space.md)
                    )
                    val lines = monthLines(pays, marks, record.month)
                    if (lines.isEmpty()) {
                        Text("Цього місяця нічого не було до сплати", color = soft, fontSize = Type.captionSize)
                    }
                    lines.forEach { line ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(Radius.sm)
                                // A month reported as unpaid with no way to
                                // correct it is an accusation you cannot
                                // answer. A tap here is how a forgotten
                                // payment gets marked after the fact.
                                .clickable { onToggle(line.pay, record.month) }
                                .padding(vertical = Space.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PaidCheck(
                                line.paid,
                                Modifier.semantics {
                                    contentDescription = if (line.paid) {
                                        "Скасувати позначку про оплату"
                                    } else {
                                        "Позначити оплаченим"
                                    }
                                },
                                ring = ink.copy(alpha = 0.4f),
                                fill = ink,
                                tick = colour
                            )
                            Spacer(Modifier.width(Space.md))
                            EmojiGlyph(shownEmoji(line.pay), 24.dp, Modifier.popOnRise(if (line.paid) 1 else 0))
                            Spacer(Modifier.width(Space.sm))
                            Text(
                                line.pay.name,
                                Modifier.weight(1f).padding(end = Space.sm),
                                color = if (line.paid) ink else soft,
                                fontSize = Type.bodySize,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // A paid figure can be corrected to what the bank
                            // actually took. Underlined, because nothing else in
                            // a row of figures says it is a control.
                            Text(
                                personalFigure(amountLabel(line.amount, line.currency)),
                                color = if (line.paid) ink else soft,
                                fontSize = Type.bodySize,
                                fontWeight = Type.medium,
                                style = Tabular,
                                textDecoration = if (line.paid) TextDecoration.Underline else null,
                                modifier = if (line.paid) {
                                    Modifier.clickable { correcting = line to record.month }
                                } else {
                                    Modifier
                                }
                            )
                        }
                    }
                    if (record.gap > 0.0 && record.state != MonthState.NOTHING_DUE) {
                        Spacer(Modifier.height(Space.sm))
                        LeaderRow(
                            "Різниця з планом",
                            personalFigure(money(record.gap)),
                            alarm = record.state != MonthState.RUNNING,
                            ink = ink,
                            softInk = soft,
                            alarmInk = TileAlarm
                        )
                    }
                }
            }
        }
    }
}

/**
 * What was really charged for one paid month, and whether that is the new price.
 *
 * The second question is asked only when the figure moved enough to be one — see
 * [amountDrifted] — and it is ticked by default then, because a bill that came in
 * at a new amount is the moment a quiet raise gets noticed or does not.
 */
@Composable
fun ChargedDialog(line: MonthLine, close: () -> Unit, save: (Double, Boolean) -> Unit) {
    var text by remember { mutableStateOf(amountText(line.amount)) }
    val charged = parseAmount(text)
    // Against what that month was meant to take. A promo month is never offered as
    // the payment's new price: its regular price is another figure — PaymentsLife.kt.
    val drifted = line.planned == line.pay.amount && amountDrifted(line.planned, charged)
    var update by remember(drifted) { mutableStateOf(drifted) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Скільки списали") },
        text = {
            Column {
                Text(line.pay.name, color = TextSecondary, fontSize = Type.captionSize)
                NumberField("Сума, ${if (line.currency == USD) "$" else "₴"}", text) { text = it }
                if (drifted) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { update = !update }
                            .padding(top = Space.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(update, { update = it })
                        Text(
                            "Відтепер ${line.pay.name} коштує ${amountLabel(charged, line.currency)}",
                            fontSize = Type.captionSize,
                            lineHeight = Type.captionLine
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button({ save(charged, update && drifted) }, enabled = charged > 0.0) { Text("Зберегти") }
        },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
}

/**
 * One figure from another tab, and the way into it.
 *
 * The label above, the figure large, one line of context — the same order every
 * tile on the overview reads in, so four of them scan as a set.
 */
@Composable
fun OverviewTile(
    label: String,
    value: String,
    detail: String,
    emoji: String,
    colour: Color,
    modifier: Modifier = Modifier,
    alarm: Boolean = false,
    onClick: () -> Unit
) {
    BentoTile(colour, modifier, onClick = onClick, onClickLabel = "Відкрити") {
        EmojiGlyph(emoji, 32.dp)
        Spacer(Modifier.height(Space.sm))
        Text(label, color = softInkOn(colour), fontSize = Type.captionSize, maxLines = 1)
        Spacer(Modifier.height(Space.xs))
        // In the display face, a word or a figure alike: «2 посилки», «41,60».
        SplitFigure(
            value,
            20.sp,
            colour = when {
                !alarm -> inkOn(colour)
                isLightFill(colour) -> TileAlarm
                else -> Negative
            }
        )
        Spacer(Modifier.height(Space.xs))
        TileCaption(detail, colour)
    }
}

/**
 * One purchase in the list as a tile: what it is, where it is, and the way into
 * its page.
 *
 * The stage is four segments beside a word — the same four stops as the rail on
 * the page, small enough to read at a glance. A download has no segments,
 * because it has nowhere to travel. The photo stands in for the emoji when the
 * shop gave one: it is the actual thing.
 */
@Composable
fun ParcelRow(
    order: Order,
    today: LocalDate,
    colour: Color,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    val ink = inkOn(colour)
    BentoTile(
        colour,
        modifier.padding(horizontal = Space.screen).fillMaxWidth(),
        onClick = onOpen,
        onClickLabel = "Детальніше"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (order.image.isNotBlank()) {
                AsyncImage(
                    crossfadeImage(order.image),
                    order.name,
                    Modifier.size(52.dp).clip(Radius.sm),
                    contentScale = ContentScale.Crop
                )
            } else {
                EmojiGlyph(shownEmoji(order), 44.dp, Modifier.padding(4.dp))
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stageLabel(orderOverline(order), order.statusCode),
                        color = if (order.problem) TileAlarm else softInkOn(colour),
                        fontSize = Type.captionSize,
                        fontWeight = Type.medium
                    )
                    if (!order.digital) {
                        Spacer(Modifier.width(Space.sm))
                        StageSegments(
                            PARCEL_STAGES.indexOf(order.status).coerceAtLeast(0),
                            filled = ink,
                            empty = ink.copy(alpha = 0.18f)
                        )
                    }
                }
                Text(
                    order.name,
                    fontSize = Type.bodySize,
                    lineHeight = Type.bodyLine,
                    fontWeight = Type.medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                val tail = listOfNotNull(
                    money(order.price).takeIf { order.price > 0 },
                    order.tracking.takeIf { it.isNotBlank() }?.let { "трек …${it.takeLast(4)}" }
                ).joinToString(" · ")
                if (tail.isNotBlank()) {
                    Text(tail, color = softInkOn(colour), fontSize = Type.captionSize, style = Tabular)
                }
                // What the carrier will still take at the counter — ParcelsMore.kt.
                if (order.amountToPay > 0.0 && order.status != RECEIVED) {
                    Text(
                        personal("До сплати при отриманні ${money(order.amountToPay)}"),
                        color = softInkOn(colour),
                        fontSize = Type.captionSize,
                        fontWeight = Type.medium,
                        style = Tabular
                    )
                }
                // The one fact in the list that costs money to miss.
                if (order.paidStorageFrom > 0) {
                    val left = freeStorageDaysLeft(LocalDate.ofEpochDay(order.paidStorageFrom), today) ?: 0
                    Text(
                        if (left > 0) "Безкоштовне зберігання ще ${daysLabel(left)}" else "Зберігання вже платне",
                        color = if (left <= 2) TileAlarm else softInkOn(colour),
                        fontSize = Type.captionSize,
                        fontWeight = Type.medium
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = softInkOn(colour))
        }
        // Only when filing it is the thing left to do: in your hands, or a download.
        // A parcel still on its way is closed from its page.
        if (closeActionDue(order)) {
            Spacer(Modifier.height(Space.md))
            Row(
                Modifier
                    .clip(Radius.pill)
                    .background(TileInk)
                    .clickable(onClick = onClose)
                    .heightIn(min = 40.dp)
                    .padding(horizontal = Space.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.TaskAlt, null, Modifier.size(18.dp), tint = Accent)
                Spacer(Modifier.width(Space.sm))
                Text(closeActionLabel(order), color = Accent, fontSize = Type.captionSize, fontWeight = Type.strong)
            }
        }
    }
}

/**
 * The first tile on Покупки: how many are on their way, or waiting at a branch.
 * See [parcelsAtAGlance] for what leads.
 */
@Composable
fun ParcelsSummaryTile(glance: ParcelsAtAGlance, modifier: Modifier = Modifier) {
    val colour = if (glance.alarm) TilePink else TileSky
    BentoTile(colour, modifier) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(end = HeroStickerSize)) {
                Text(glance.label, color = softInkOn(colour), fontSize = Type.captionSize, fontWeight = Type.medium)
                Spacer(Modifier.height(Space.xs))
                SplitFigure(glance.figure, 26.sp)
                glance.caption?.let {
                    Spacer(Modifier.height(Space.xs))
                    TileCaption(it, colour)
                }
            }
            EmojiSticker(glance.emoji, 48.dp, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Four short bars, filled up to the stage the parcel has reached. */
@Composable
fun StageSegments(
    reached: Int,
    modifier: Modifier = Modifier,
    filled: Color = TextPrimary,
    empty: Color = HairLine
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(PARCEL_STAGES.size) { index ->
            // A status landing fills the next segment rather than snapping it on.
            val tone by animateColorAsState(
                if (index <= reached) filled else empty,
                Motion.effects(),
                label = "stage"
            )
            Box(
                Modifier
                    .width(12.dp)
                    .height(4.dp)
                    .background(tone, Radius.pill)
            )
        }
    }
}

/**
 * The button that files a purchase away, at whatever stage it is.
 *
 * See [closeActionLabel] and [closeActionDue] for the wording and the colour; this
 * only draws them, so the card and the parcel page cannot drift apart.
 */
@Composable
fun CloseOrderButton(order: Order, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val due = closeActionDue(order)
    OutlinedButton(
        onClick,
        modifier.fillMaxWidth(),
        shape = Radius.sm,
        border = BorderStroke(1.dp, if (due) Accent else HairLine),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (due) Accent else TextPrimary)
    ) {
        Icon(Icons.Default.TaskAlt, null)
        Text("  ${closeActionLabel(order)}")
    }
}

/**
 * A finished purchase, with the one thing it can still teach.
 *
 * No photograph and no stage rail: this thing is not going anywhere, and the card
 * exists for the verdict rather than for the object. The chip carries the verdict
 * in the same shapes the wishlist uses for its buy advice, so a purchase filed as
 * "ти поспішив" looks like the "дорого зараз" it was bought at.
 */
@Composable
fun ArchivedPurchase(
    order: Order,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** «Залишаю»: the window is no longer worth a reminder. */
    onKeep: () -> Unit = {},
    /** «↩️ Повертаю», beside «Залишаю» while the window is open. */
    onReturn: () -> Unit = {},
    /** «Як тобі …?» answered. */
    onDelight: (Delight) -> Unit = {},
    /** «Купити таке ще раз?» answered. */
    onAgain: (Boolean) -> Unit = {},
    /** «Гроші ще не прийшли»: a money-back marked too soon, back among the returns. */
    onUndoRefund: () -> Unit = {}
) {
    val review = purchaseReview(order.paid, order.lowestSeen, order.uses)
    val today = remember { LocalDate.now().toEpochDay() }
    val givenBack = isRefunded(order)
    Card(
        Modifier.padding(horizontal = Space.screen, vertical = Space.xs).fillMaxWidth().litEdge(Radius.md),
        colors = CardDefaults.cardColors(containerColor = SurfaceLow),
        shape = Radius.md
    ) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.name,
                    Modifier.weight(1f),
                    fontSize = Type.cardTitleSize,
                    lineHeight = Type.cardTitleLine,
                    fontWeight = Type.medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(Space.md))
                if (givenBack) {
                    // Not a verdict: a purchase given back was not kept, so it is
                    // neither on time nor in a hurry.
                    VerdictChip("Повернено", icon = Icons.AutoMirrored.Filled.Undo, tint = TextSecondary)
                } else {
                    VerdictChip(
                        purchaseVerdictLabel(review.verdict),
                        icon = Icons.Default.TaskAlt,
                        tint = verdictInk(review.verdict),
                        corners = purchaseVerdictCorners(review.verdict)
                    )
                }
            }
            Text(
                order.refund?.takeIf { givenBack }?.let { refund ->
                    personal("Гроші повернулись ${formatDate(LocalDate.ofEpochDay(refund.backDay))} · ${money(refund.amount)}")
                } ?: personal(purchaseVerdictDetail(review)),
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.sm)
            )
            warrantyChip(order, today)?.let { chip ->
                Row(Modifier.padding(top = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    EmojiGlyph("🛡️", 16.dp)
                    Spacer(Modifier.width(Space.xs))
                    Text(chip, color = TextSecondary, fontSize = Type.captionSize)
                }
            }
            if (delightDue(order, today)) {
                DelightQuestion(order, onDelight, onAgain)
            } else {
                delightAnswer(order)?.let { DelightAnswerView(it) }
            }
            costPerUseLine(review)?.takeIf { !givenBack }?.let {
                Row(
                    Modifier.fillMaxWidth().padding(top = Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Виходить", color = TextSecondary, fontSize = Type.captionSize)
                    Box(Modifier.weight(1f).padding(horizontal = Space.sm)) {
                        DottedLeader(Modifier.fillMaxWidth())
                    }
                    Text(personal(it), color = TextPrimary, fontSize = Type.captionSize, fontWeight = Type.strong)
                }
            }
            Text(
                "у архіві з ${formatDate(LocalDate.ofEpochDay(order.archivedDay))}",
                color = TextDisabled,
                fontSize = Type.captionSize,
                modifier = Modifier.padding(top = Space.xs)
            )
            // Bought «частинами» and returned: the plan's «Повернув» says so here.
            returnedPurchaseLine(order)?.let { line ->
                Text(
                    line,
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine,
                    modifier = Modifier.padding(top = Space.xs)
                )
            }
            returnLine(order, today)?.let { line ->
                Text(
                    line,
                    Modifier.padding(top = Space.xs),
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onKeep) { Text("Залишаю") }
                    TextButton(onReturn) {
                        EmojiGlyph("↩️", 16.dp)
                        Text(" Повертаю")
                    }
                }
            }
            Row(Modifier.padding(top = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                if (givenBack) {
                    // A thing given back is not used; what can still go wrong here is
                    // «Гроші повернулись» tapped before they had.
                    TextButton(onUndoRefund) { Text("Гроші ще не прийшли") }
                } else {
                    TextButton(onEdit) {
                        Text(if (order.uses > 0) "Оновити користування" else "Порахувати користування")
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onDelete) { Icon(Icons.Default.DeleteOutline, "Видалити з архіву") }
            }
        }
    }
}

@Composable
fun AddOrderSheet(close: () -> Unit, initialTracking: String = "", add: (Order) -> Unit) {
    var link by remember { mutableStateOf("") }
    var trackingNumber by remember { mutableStateOf(initialTracking) }
    val context = LocalContext.current
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Guessed from the shop until the switch is touched, then whatever was chosen.
    // A Steam link pasted in should not have to be explained to the form.
    var chosenKind by remember { mutableStateOf<Boolean?>(null) }
    val digital = chosenKind ?: isDigitalStore(link)
    val scope = rememberCoroutineScope()
    FormSheet(
        title = "Додати покупку",
        confirmLabel = if (loading) "Зчитую…" else "Додати",
        // A link, or a parcel number on its own: a parcel announced by SMS has no
        // shop page to hand, and it is still a purchase on its way.
        confirmEnabled = !loading && (isSupportedWebUrl(link) || (!digital && trackingNumber.isNotBlank())),
        onConfirm = {
            if (!isSupportedWebUrl(link)) {
                val number = trackingNumber.filter { !it.isWhitespace() }
                add(Order(System.currentTimeMillis().toString(), parcelNameFor(number), "", ORDERED, tracking = number))
                return@FormSheet
            }
            scope.launch {
                loading = true
                error = null
                runCatching {
                    readForAdd(
                        pricedPageHtml(link),
                        link.trim(),
                        System.currentTimeMillis().toString()
                    )
                }
                    .onSuccess { read ->
                        // A purchase is a record of a thing you already own, so a
                        // page that names it without pricing it is still worth
                        // everything: the price of an order is typed on the card
                        // anyway, because what you paid is rarely what the page
                        // asks today.
                        val item = when (read) {
                            is PageAdd.Priced -> read.wish
                            is PageAdd.Described -> read.wish
                            PageAdd.Blank -> null
                        }
                        add(
                            Order(
                                item?.id ?: System.currentTimeMillis().toString(),
                                item?.name ?: placeholderName(link.trim()),
                                item?.url ?: link.trim(),
                                ORDERED,
                                tracking = if (digital) "" else trackingNumber.trim(),
                                image = item?.image.orEmpty(),
                                price = item?.price ?: 0.0,
                                digital = digital
                            )
                        )
                    }
                    // A purchase is already made, so a shop that will not show its
                    // page — Temu behind its captcha, a dropped connection — costs
                    // the name and the photo, never the record. The name can be
                    // corrected from the card's pencil.
                    .onFailure {
                        add(
                            Order(
                                System.currentTimeMillis().toString(),
                                placeholderName(link.trim()),
                                link.trim(),
                                ORDERED,
                                tracking = if (digital) "" else trackingNumber.trim(),
                                digital = digital
                            )
                        )
                    }
                loading = false
            }
        },
        onDismiss = close
    ) {
        Text("Вставте посилання на сторінку придбаного товару.")
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth().padding(top = Space.md), label = { Text("Посилання") })
        OrderKindControl(digital, Modifier.padding(top = Space.md)) { chosenKind = it }
        if (!digital) {
            OutlinedTextField(
                trackingNumber,
                { trackingNumber = it },
                Modifier.fillMaxWidth().padding(top = Space.md),
                label = { Text("Трек-номер, якщо вже є") },
                singleLine = true,
                // Read from the clipboard only when this is tapped — Android shows
                // its own notice whenever an app reads it, and an app that reads it
                // unasked is one that notice exists to catch.
                trailingIcon = {
                    IconButton({
                        val clip = runCatching {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        }.getOrNull()
                        trackingNumberIn(clip)?.let { trackingNumber = it }
                    }) { Icon(Icons.Default.ContentPaste, "Вставити трек-номер") }
                }
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Space.sm)) }
    }
}

@Composable
fun SettingsScreen(
    /** The month that has ended, already built. Null when there is none worth showing. */
    recap: Recap?,
    onOpenRecap: () -> Unit,
    summary: Overview,
    store: Store,
    /** What the background passes last did. Null until it has been read. */
    health: HealthLine?,
    onOpenHealth: () -> Unit,
    /** The next bill still owed, for its tile. */
    next: NextPayment?,
    /** The dollar sell rate, for its tile. Nought when none has loaded. */
    usdRate: Double,
    /** A tile was tapped: show the tab it summarises. */
    onOpenTab: (Int) -> Unit,
    /** The next seven days as weather — see Ideas.kt. */
    weather: List<MoneyDay>,
    /** The card's own money from monobank, null when it is not connected. */
    balance: Double?,
    /** The one wish that could be bought now without hurting the month. */
    treat: Treat?,
    onOpenWish: (String) -> Unit,
    bin: List<BinEntry>,
    onRestore: (String) -> Unit,
    onDropFromBin: (String) -> Unit,
    onEmptyBin: () -> Unit,
    onImported: () -> Unit,
    /** «ще 2 посилки до оплати: 1 498 ₴», or null when no parcel asks for money. */
    parcelsToPay: String? = null,
    /** The parcels' money inside the forecast, day by day. */
    weatherParcels: List<CodChip> = emptyList(),
    /** «Мені винні»: returns still waiting for their money. Null when none. */
    owed: Owed? = null,
    /** «На життя», the payday, the funds and the plans — MoneyPlan.kt. Null draws the screen as it was. */
    moneyHost: MoneyHost? = null
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    // The plan's dialogs and sheets — see MoneyPlanUi.kt.
    var lifeOpen by remember { mutableStateOf(false) }
    var incomeOpen by remember { mutableStateOf(false) }
    var affordOpen by remember { mutableStateOf(false) }
    var skipping by remember { mutableStateOf<PlanAsk?>(null) }
    var checking by remember { mutableStateOf(false) }
    // «Мій номер для Нової пошти» — a preference of this phone, in no backup.
    val parcelPrefs = remember { ParcelPrefs(context) }
    var novaPhone by remember { mutableStateOf(parcelPrefs.phone()) }
    var phoneOpen by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<UpdateInfo?>(null) }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now().toEpochDay() }
    // Whether the bin is open, remembered across openings of the app like every
    // other fold. See [SECTION_BIN] for why the answer starts at no.
    var binOpen by remember { mutableStateOf(store.sectionOpen(SECTION_BIN)) }

    // The folder and the timestamp are read into state so that picking a folder or
    // running a copy updates the row, rather than leaving it describing the state
    // the screen opened in.
    var backupFolder by remember { mutableStateOf(store.backupFolder()) }
    var lastBackup by remember { mutableLongStateOf(store.lastBackupAt()) }
    var backingUp by remember { mutableStateOf(false) }
    var exportingCsv by remember { mutableStateOf(false) }
    // The year the export is about, which through January is the one just ended.
    val thisYear = remember { exportYear(LocalDate.now()) }
    val backupPermitted = remember(backupFolder, lastBackup) {
        holdsBackupPermission(context, backupFolder)
    }
    val backup = backupState(backupFolder, backupPermitted)
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Without the persistable grant the folder works until the phone is
            // restarted and then silently stops, which is worse than refusing it.
            if (takeBackupFolder(context, uri)) {
                store.saveBackupFolder(uri.toString())
                backupFolder = uri.toString()
                scope.launch {
                    backingUp = true
                    // The first copy is written straight away: a backup you have to
                    // wait a week to see is a backup you do not believe in.
                    message = if (backupNow(context, store)) {
                        lastBackup = store.lastBackupAt()
                        "Копію створено"
                    } else {
                        "Тека вибрана, але записати не вдалося"
                    }
                    backingUp = false
                }
            } else {
                message = "Android не дав постійний доступ до цієї теки"
            }
        }
    }
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
    // The owner's Apple emoji, from a file of his own — see [EmojiPack] for why
    // they are not inside the app. Counted into state so the row updates.
    var emojiCount by remember { mutableIntStateOf(EmojiPack.count(context)) }
    var monoOpen by remember { mutableStateOf(false) }
    var importingEmoji by remember { mutableStateOf(false) }
    val pickEmoji = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importingEmoji = true
                val result = withContext(Dispatchers.IO) { runCatching { EmojiPack.import(context, uri) } }
                importingEmoji = false
                message = result.fold(
                    onSuccess = { count ->
                        if (count > 0) {
                            emojiCount = count
                            "Емодзі завантажено: $count"
                        } else {
                            "У цьому файлі немає емодзі для FlowPay"
                        }
                    },
                    onFailure = { "Не вдалося прочитати файл з емодзі" }
                )
            }
        }
    }
    val listState = rememberLazyListState()
    val entrance = LocalEntrance.current
    Box {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = navClearance())) {
            // Item zero is the header alone: that is the block the compact bar watches.
            item { ScreenHeader("Огляд") }
            // Waiting, not interrupting. It sits above the figures because it is
            // the one thing on this screen that is only here this month.
            recap?.let { deck ->
                item(key = "recap-${deck.month}") {
                    RecapInvite(
                        deck,
                        Modifier
                            .revealOnEnter(0, entrance)
                            .padding(horizontal = Space.screen)
                            .padding(bottom = Space.lg),
                        onOpen = onOpenRecap
                    )
                }
            }
            item {
                Column(Modifier.padding(horizontal = Space.screen)) {
                    // What is free until the month ends, with the standing costs as the
                    // bar inside it — the question this screen is opened with. The
                    // savings figure used to hold this panel, and on a phone that had
                    // saved nothing yet the loudest thing here said «0 ₴», «0%» and an
                    // empty ring: three ways of saying nothing.
                    val bar = committedOf(
                        moneyHost?.plan?.month?.asBudget()
                            ?: Budget(summary.income, summary.monthlyExpenses, summary.freeCash, summary.overspent, summary.budgetUnknown)
                    )
                    // With «На життя» on, the figure is «після платежів і життя», and
                    // the panel opens the one number it rests on.
                    val lifeOn = moneyHost?.plan?.month?.let { it.life > 0.0 } == true
                    HeroPanel(
                        modifier = Modifier
                            .revealOnEnter(1, entrance)
                            .then(if (lifeOn) Modifier.clip(Radius.lg).clickable(onClickLabel = "Змінити витрати на життя") { lifeOpen = true } else Modifier),
                        label = moneyHost?.plan?.month?.let { heroLabel(it) } ?: when {
                            summary.budgetUnknown -> "Вкажіть дохід на Платежах"
                            summary.overspent -> "Не сходиться цього місяця"
                            else -> "Вільно до кінця місяця"
                        },
                        value = personalFigure(if (summary.budgetUnknown) money(summary.monthlyExpenses) else money(summary.freeCash)),
                        caption = personal(
                            moneyHost?.let { host ->
                                heroCaption(host.plan.month, paydayCountdown(host.inputs.payday, host.today, host.inputs.holidays))
                            } ?: committedDetail(bar)
                        ),
                        muted = summary.budgetUnknown,
                        emoji = "💰",
                        // The bar, and the eye that hides the sums on every screen
                        // (Privacy.kt) — always here, so the way back is in sight.
                        footer = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (summary.budgetUnknown) {
                                    Spacer(Modifier.weight(1f))
                                } else {
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(8.dp)
                                            .background(AccentInk.copy(alpha = 0.18f), Radius.pill)
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth(entranceFraction(bar.share.coerceIn(0f, 1f), delayMs = 350L).coerceIn(0f, 1f))
                                                .height(8.dp)
                                                .background(AccentInk, Radius.pill)
                                        )
                                    }
                                    Spacer(Modifier.width(Space.sm))
                                }
                                SumsEye()
                            }
                        }
                    )

                    // Below the panel rather than inside it: the figure is income less
                    // the standing costs, and a parcel's money is said beside it.
                    parcelsToPay?.let { ParcelsToPayLine(it) }
                    // What the card can spend a day until money arrives — monobank's
                    // own balance and a known payday only; without either nothing
                    // changes. And on a payday, the ritual. See MoneyPlan.kt.
                    moneyHost?.let { host ->
                        val allowanceNow = host.monoBalance?.takeIf { host.inputs.payday.known }
                            ?.let { allowance(host.inputs, host.plan, it) }
                        allowanceNow?.let {
                            Spacer(Modifier.height(Space.md))
                            AllowanceTile(it, host.monoAt, Modifier.revealOnEnter(2, entrance))
                        }
                        ritualFor(host.inputs, host.plan)?.let { ritual ->
                            Spacer(Modifier.height(Space.md))
                            RitualTile(
                                ritual,
                                host.today,
                                Modifier.revealOnEnter(2, entrance),
                                onDone = { chosen ->
                                    // Each list changed as it is now; the record kept for «Скасувати».
                                    host.updateWishes { now -> applyRitual(now, emptyList(), chosen, ritual.anchor, host.today).first }
                                    host.updateFunds { now -> applyRitual(emptyList(), now, chosen, ritual.anchor, host.today).second }
                                    val record = applyRitual(emptyList(), emptyList(), chosen, ritual.anchor, host.today).third
                                    host.saveRitual(record)
                                    host.say("Записано: відкладено ${money(record.total)}")
                                },
                                onUndo = { done ->
                                    host.updateWishes { now -> undoRitual(now, emptyList(), done).first }
                                    host.updateFunds { now -> undoRitual(emptyList(), now, done).second }
                                    host.saveRitual(null)
                                },
                                onLater = {
                                    host.saveRitual(RitualRecord(ritual.anchor.toEpochDay(), false, host.today.toEpochDay()))
                                }
                            )
                        }
                    }

                    // The week ahead as weather: a rainy Wednesday seen on Monday.
                    if (weather.isNotEmpty()) {
                        Spacer(Modifier.height(Space.md))
                        WeatherTile(weather, LocalDate.ofEpochDay(today), Modifier.revealOnEnter(2, entrance), balance, weatherParcels)
                    }
                    // Four tiles, each the one figure its own tab is about, each a door
                    // into that tab. Bento rather than a column of rows: the four
                    // answers sit where the eye can take them in one look.
                    Spacer(Modifier.height(Space.md))
                    Row(
                        Modifier.revealOnEnter(3, entrance).height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(Space.md)
                    ) {
                        OverviewTile(
                            "Відкладено",
                            personalFigure(money(summary.savedTotal)),
                            // The percentage stays; the total beside it would give the savings away.
                            if (summary.wishTotal > 0) personal("${(summary.savedProgress * 100).toInt()}% з ${money(summary.wishTotal)}")
                            else "бажань ще немає",
                            emoji = "🐷",
                            colour = TileLavender,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) { onOpenTab(TAB_WISHES) }
                        OverviewTile(
                            "Посилки",
                            when {
                                summary.parcelsAtBranch > 0 -> parcelsLabel(summary.parcelsAtBranch)
                                summary.parcelsMoving > 0 -> parcelsLabel(summary.parcelsMoving)
                                else -> "—"
                            },
                            when {
                                summary.parcelsAtBranch > 0 -> "чекають на відділенні"
                                summary.parcelsMoving > 0 -> "у дорозі"
                                else -> "нічого не їде"
                            },
                            emoji = if (summary.parcelsAtBranch > 0) "📬" else "📦",
                            // Waiting at a branch is the one tile that asks for
                            // something, so it is the one that changes colour.
                            colour = if (summary.parcelsAtBranch > 0) TilePink else TilePeach,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            alarm = summary.parcelsAtBranch > 0
                        ) { onOpenTab(TAB_ORDERS) }
                    }
                    Spacer(Modifier.height(Space.md))
                    Row(
                        Modifier.revealOnEnter(4, entrance).height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(Space.md)
                    ) {
                        OverviewTile(
                            "Курс долара",
                            if (usdRate > 0) rateFigure(usdRate) else "—",
                            "продаж, ₴ за $1",
                            emoji = "💵",
                            colour = SurfaceRaised,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) { onOpenTab(TAB_RATE) }
                        OverviewTile(
                            "Наступний платіж",
                            next?.let { personalFigure(money(it.total.total)) } ?: "—",
                            next?.let { "${dayMonth(it.date)} · ${dueSummary(it.items)}" } ?: "усе сплачено",
                            emoji = next?.items?.singleOrNull()?.let { shownEmoji(it) } ?: "🗓️",
                            colour = TileSky,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) { onOpenTab(TAB_PAYMENTS) }
                    }

                    // Money owed back for things returned. Pink once a shop is past
                    // its days; until then it is simply money on its way.
                    owed?.let { back ->
                        Spacer(Modifier.height(Space.md))
                        OverviewTile(
                            "Мені винні",
                            personalFigure(money(back.total)),
                            owedCaption(back),
                            emoji = "💸",
                            colour = if (back.overdue > 0) TilePink else TileMint,
                            modifier = Modifier.fillMaxWidth().revealOnEnter(5, entrance),
                            alarm = back.overdue > 0
                        ) { onOpenTab(TAB_ORDERS) }
                    }

                    // Not a warning but a permission: the one thing that would fit.
                    treat?.let { gift ->
                        Spacer(Modifier.height(Space.md))
                        TreatTile(gift, Modifier.revealOnEnter(5, entrance)) { onOpenWish(gift.wish.id) }
                    }
                    // «Чи потягну?» before a purchase, and how much of next month is
                    // already paid for — the second only once a «Подушка» exists.
                    moneyHost?.let { host ->
                        Spacer(Modifier.height(Space.md))
                        AffordInvite(Modifier.revealOnEnter(6, entrance)) { affordOpen = true }
                        monthAhead(host.inputs, host.plan)?.let { ahead ->
                            Spacer(Modifier.height(Space.md))
                            MonthAheadTile(ahead, Modifier.revealOnEnter(6, entrance))
                        }
                    }
                    if (summary.plansConflict) {
                        Spacer(Modifier.height(Space.md))
                        Card(
                            Modifier.fillMaxWidth().litEdge(Radius.md),
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
                                    personal(
                                        moneyHost?.let { plansConflictLine(it.plan) }
                                            ?: ("Плани по бажаннях просять ${money(summary.plannedMonthly)} на місяць, " +
                                                "а вільно ${money(summary.freeCash)}. " +
                                                "Не вистачає ${money(summary.plansOverBudget)}.")
                                    ),
                                    color = TextSecondary,
                                    fontSize = Type.captionSize,
                                    lineHeight = Type.captionLine,
                                    modifier = Modifier.padding(top = Space.xs)
                                )
                                // One tap instead of only a warning: skip the least wanted
                                // plan this month — its price said before it is done.
                                moneyHost?.let { skipCandidate(it.plan) }?.let { ask ->
                                    TextButton({ skipping = ask }, Modifier.padding(top = Space.xs)) {
                                        // White, not lime: the lime on this screen is the hero's.
                                        Text(
                                            "💤 Пропустити цього місяця: «${ask.name}»",
                                            color = TextPrimary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val moved = summary.movement
                    if (moved.tracked > 0) {
                        Spacer(Modifier.height(Space.md))
                        Card(
                            Modifier.fillMaxWidth().litEdge(Radius.md),
                            colors = CardDefaults.cardColors(containerColor = SurfaceBase),
                            shape = Radius.md
                        ) {
                            Column(Modifier.padding(Space.lg)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when {
                                            moved.change < 0 -> "Список подешевшав"
                                            moved.change > 0 -> "Список подорожчав"
                                            else -> "Ціни стоять на місці"
                                        },
                                        Modifier.weight(1f),
                                        color = TextSecondary,
                                        fontSize = Type.captionSize
                                    )
                                    EmojiGlyph(if (moved.change > 0) "📈" else "🏷️", 28.dp)
                                }
                                Spacer(Modifier.height(Space.xs))
                                Text(
                                    if (moved.change == 0.0) {
                                        money(0.0)
                                    } else {
                                        "${money(kotlin.math.abs(moved.change))} · " +
                                            signedPercent(moved.changePercent)
                                    },
                                    fontSize = Type.sectionSize,
                                    lineHeight = Type.sectionLine,
                                    fontWeight = Type.strong,
                                    color = when {
                                        // White, not lime: the lime on this screen is
                                        // the hero panel's. A rise keeps its warning red.
                                        moved.change < 0 -> TextPrimary
                                        moved.change > 0 -> Negative
                                        else -> TextPrimary
                                    },
                                    style = Tabular
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
                                            signedPercent(moved.biggestDropPercent),
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
                                            signedPercent(moved.biggestRisePercent),
                                            color = Negative,
                                            fontSize = Type.captionSize,
                                            fontWeight = Type.strong
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // The three rings that restated these figures are gone: they
                    // differed only in how see-through the lime was, and had no labels.
                    Spacer(Modifier.height(Space.lg))
                    Card(
                        Modifier.fillMaxWidth().litEdge(Radius.lg),
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

                // Folded, and shut unless he says otherwise. The count and the
                // oldest entry's deadline ride the shut heading, so the one
                // question a bin ever has to answer — is something of mine in
                // there and am I about to lose it — is answered without opening
                // it. See [SECTION_BIN].
                CollapsibleSection(
                    title = "Кошик",
                    summary = binSummary(bin, today),
                    open = binOpen,
                    onToggle = {
                        binOpen = it
                        store.saveSectionOpen(SECTION_BIN, it)
                    },
                    icon = Icons.Default.DeleteOutline
                ) {
                    Column(Modifier.padding(horizontal = Space.screen)) {
                        // Opened on an empty bin the fold would otherwise be a heading
                        // with nothing under it, which reads as a bug rather than as an
                        // answer.
                        if (bin.isEmpty()) {
                            EmptyInvite(
                                "Тут порожньо",
                                "Видалене бажання, витрата чи покупка лежить тут " +
                                    "${daysLabel(BIN_DAYS)} і повертається одним дотиком."
                            )
                        }
                        sortedBin(bin).forEach { entry ->
                            Card(
                                Modifier.fillMaxWidth().padding(bottom = Space.sm).litEdge(Radius.md),
                                colors = CardDefaults.cardColors(containerColor = SurfaceBase),
                                shape = Radius.md
                            ) {
                                Row(
                                    Modifier.padding(Space.lg),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).padding(end = Space.md)) {
                                        Text(
                                            binKindLabel(entry.kind).uppercase(),
                                            color = Accent,
                                            fontSize = Type.overlineSize,
                                            fontWeight = Type.strong,
                                            letterSpacing = Type.overlineTracking
                                        )
                                        Spacer(Modifier.height(Space.xs))
                                        Text(
                                            entry.title,
                                            fontSize = Type.cardTitleSize,
                                            fontWeight = Type.medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        listOfNotNull(
                                            // A payment's amount is the owner's; a wish's price is the shop's.
                                            entry.detail.takeIf { it.isNotBlank() }?.let { if (entry.kind == BIN_PAY) personal(it) else it },
                                            binLeftLabel(entry, today)
                                        ).joinToString(" · ").let {
                                            Text(
                                                it,
                                                color = TextSecondary,
                                                fontSize = Type.captionSize,
                                                lineHeight = Type.captionLine
                                            )
                                        }
                                    }
                                    TextButton({ onRestore(entry.id) }) { Text("Повернути") }
                                    IconButton({ onDropFromBin(entry.id) }) {
                                        Icon(
                                            Icons.Default.DeleteForever,
                                            "Видалити назавжди",
                                            tint = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                        if (bin.isNotEmpty()) {
                            TextButton({ onEmptyBin() }) {
                                Text("Очистити кошик", color = Negative)
                            }
                        }
                    }
                }

                SectionTitle("Налаштування")
                // When the money comes, and what life costs: the two numbers the
                // one «Вільно» rests on beside the income. See MoneyPlan.kt.
                moneyHost?.let { host ->
                    SettingsRow(
                        Icons.Default.Payments,
                        "Дохід і день зарплати",
                        personal(incomeRowDetail(host.inputs.income, host.settings.payday)),
                        onClick = { incomeOpen = true }
                    )
                    SettingsRow(
                        Icons.Default.ShoppingCart,
                        "Витрати на життя",
                        personal(lifeRowDetail(host.settings.life)),
                        onClick = { lifeOpen = true }
                    )
                }
                // Filled list items painted a large lighter block across the screen and
                // left a hard seam under the header. They sit on the page instead.
                // The calm half of what the strip above used to say on every visit.
                // Opens the same sheet, which is also where the digest hour is set.
                SettingsRow(
                    if (health?.alarm == true) Icons.Default.BatteryAlert else Icons.Default.Sync,
                    health?.title ?: "Фонове оновлення",
                    listOfNotNull(
                        health?.detail,
                        "кожні 12 годин перевіряються ціни та статуси посилок"
                    ).joinToString(" · ").replaceFirstChar { it.uppercase() },
                    alarm = health?.alarm == true,
                    onClick = onOpenHealth
                )
                SettingsRow(
                    Icons.Default.NotificationsNone,
                    "Сповіщення",
                    // What actually interrupts, and what waits for the morning.
                    // The old line promised alerts for every fall and every move
                    // of a parcel, which the app stopped sending long ago.
                    "Одразу — лише ціль досягнута, товар знову в наявності, кінець паузи й " +
                        "платне зберігання. Решта — в одному ранковому зведенні."
                )
                SettingsRow(
                    Icons.Default.Security,
                    "Приватність",
                    // Both halves of the old sentence were false: the appraisal
                    // key is built into the APK on purpose (HANDOFF §10), and the
                    // appraisal sends a wish's name and description to Google.
                    "Вішлісти й фінанси зберігаються лише на телефоні. Оцінка товару " +
                        "надсилає Google назву й опис товару — без ціни і без ваших сум."
                )
                // Optional. With it Nova Poshta answers in full — the cash on
                // delivery above all, which the forecast counts.
                SettingsRow(
                    Icons.Default.Phone,
                    "Мій номер для Нової пошти",
                    if (novaPhone.isBlank()) {
                        "Не вказано. З ним пошта показує суму до сплати при отриманні, " +
                            "вартість доставки й відправника"
                    } else {
                        "${maskedPhone(novaPhone)} · лише на цьому телефоні"
                    },
                    onClick = { phoneOpen = true }
                )
                HideSumsOutsideRow()
                // Off until the owner says they hold the card — PricesMore.kt.
                RozetkaCardRow(Modifier.padding(top = Space.sm))
                HorizontalDivider(color = HairLine, modifier = Modifier.padding(vertical = Space.lg))
                MonoSettingsItem { monoOpen = true }
                ListItem(
                    leadingContent = { EmojiGlyph("😀", 28.dp) },
                    headlineContent = { Text("Емодзі Apple", fontWeight = FontWeight.Bold) },
                    supportingContent = {
                        Text(
                            if (emojiCount > 0) {
                                "Завантажено $emojiCount емодзі з вашого файлу"
                            } else {
                                "Зараз показуються емодзі телефона. Виберіть файл FlowPay-emoji-Apple.zip"
                            }
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (emojiCount > 0) {
                                IconButton({
                                    EmojiPack.clear(context)
                                    emojiCount = 0
                                }) {
                                    Icon(Icons.Default.DeleteOutline, "Прибрати емодзі Apple", tint = TextSecondary)
                                }
                            }
                            OutlinedButton(
                                shape = Radius.sm,
                                border = BorderStroke(1.dp, HairLine),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                onClick = {
                                    pickEmoji.launch(
                                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                                    )
                                },
                                enabled = !importingEmoji
                            ) {
                                if (importingEmoji) BusyMark() else Text(if (emojiCount > 0) "Замінити" else "Вибрати")
                            }
                        }
                    }
                )
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
                            if (checking) BusyMark()
                            else Text("Перевірити")
                        }
                    }
                )
                // The manual export below only helps the person who remembers to
                // press it. The loss this guards against is a phone left in a taxi,
                // and nobody exports on the morning of that.
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Default.FolderOpen,
                            null,
                            tint = if (backup == BackupState.FOLDER_LOST) Negative else Accent
                        )
                    },
                    headlineContent = { Text("Автоматична копія", fontWeight = FontWeight.Bold) },
                    supportingContent = {
                        Column {
                            Text(
                                backupStatusLine(backup, lastBackup),
                                color = if (backup == BackupState.FOLDER_LOST) {
                                    Negative
                                } else {
                                    TextSecondary
                                }
                            )
                            if (backup == BackupState.READY) {
                                Text(backupKeepLine(), color = TextDisabled)
                            }
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (backup == BackupState.READY) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            backingUp = true
                                            message = if (backupNow(context, store)) {
                                                lastBackup = store.lastBackupAt()
                                                "Копію створено"
                                            } else {
                                                "Не вдалося записати у теку"
                                            }
                                            backingUp = false
                                        }
                                    },
                                    enabled = !backingUp
                                ) {
                                    if (backingUp) {
                                        BusyMark()
                                    } else {
                                        Icon(Icons.Default.Sync, "Створити копію зараз", tint = Accent)
                                    }
                                }
                            }
                            OutlinedButton(
                                shape = Radius.sm,
                                border = BorderStroke(1.dp, HairLine),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                onClick = { pickFolder.launch(null) },
                                enabled = !backingUp
                            ) {
                                Text(if (backup == BackupState.OFF) "Вибрати теку" else "Змінити")
                            }
                        }
                    }
                )
                // Written into the folder the weekly copy already uses, so the
                // spreadsheet lands beside the backups instead of asking for a
                // second folder the user would have to remember choosing.
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Default.TableChart,
                            null,
                            tint = if (backup == BackupState.READY) Accent else TextDisabled
                        )
                    },
                    headlineContent = { Text("Витрати за $thisYear рік", fontWeight = FontWeight.Bold) },
                    supportingContent = {
                        Text(
                            if (backup == BackupState.READY) {
                                "Сплачене, підписки та закриті покупки — таблиця для Excel"
                            } else {
                                "Спершу виберіть теку для копій — таблиця ляже туди ж"
                            }
                        )
                    },
                    trailingContent = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    exportingCsv = true
                                    val done = exportExpensesNow(context, store)
                                    message = when {
                                        done.name != null ->
                                            "Збережено ${done.name} · ${done.rows} рядків"
                                        done.rows == 0 ->
                                            "За $thisYear рік ще нічого не записано"
                                        else -> "Не вдалося записати таблицю у теку"
                                    }
                                    exportingCsv = false
                                }
                            },
                            enabled = backup == BackupState.READY && !exportingCsv
                        ) {
                            if (exportingCsv) {
                                BusyMark()
                            } else {
                                Icon(Icons.Default.ChevronRight, "Зберегти таблицю")
                            }
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
    if (monoOpen) MonoSheet { monoOpen = false }
    if (phoneOpen) {
        NovaPhoneDialog(novaPhone, { phoneOpen = false }) { typed ->
            parcelPrefs.savePhone(typed).also { saved -> if (saved) novaPhone = parcelPrefs.phone() }
        }
    }
    moneyHost?.let { host ->
        if (lifeOpen) {
            LifeDialog(host.settings.life, { lifeOpen = false }) { life ->
                host.saveSettings(host.settings.copy(life = life))
                lifeOpen = false
            }
        }
        if (incomeOpen) {
            IncomeDialog(
                host.inputs.income,
                { incomeOpen = false },
                payday = host.settings.payday,
                holidays = host.inputs.holidays,
                savePayday = { payday -> host.saveSettings(host.settings.copy(payday = payday)) }
            ) { value ->
                host.saveIncome(value)
                incomeOpen = false
            }
        }
        if (affordOpen) AffordSheet(host) { affordOpen = false }
        skipping?.let { ask ->
            SkipDialog(ask, host.today, close = { skipping = null }) {
                when (ask.kind) {
                    PlanKind.WISH -> host.updateWishes { list ->
                        list.map { if (it.id == ask.id) skippedWish(it, host.today, true) else it }
                    }
                    PlanKind.FUND -> host.updateFunds { list ->
                        list.map { if (it.id == ask.id) skippedFund(it, host.today, true) else it }
                    }
                }
                host.say(
                    "«${ask.name}» пропущено в ${monthLocative(host.today.monthValue)} — " +
                        "з 1 ${monthGenitive(host.today.plusMonths(1).monthValue)} знову"
                )
                skipping = null
            }
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
fun SettingsRow(
    icon: ImageVector,
    title: String,
    detail: String,
    alarm: Boolean = false,
    /** Null for a row that only explains; given, the row opens something. */
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = Space.screen, vertical = Space.md),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, null, tint = if (alarm) Negative else TextSecondary)
        Spacer(Modifier.width(Space.lg))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = Type.cardTitleSize, fontWeight = Type.medium)
            Text(
                detail,
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
        if (onClick != null) {
            Icon(Icons.Default.ChevronRight, null, tint = TextDisabled)
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
    /** What has been marked paid: a payoff writes its month, a cancellation reads its «діє до». */
    marks: List<PaidMark> = emptyList(),
    setMarks: (List<PaidMark>) -> Unit = {},
    /** Purchases a plan can be tied to, and what «Повернув» tells the one it is tied to. */
    orders: List<Order> = emptyList(),
    onReturned: (orderId: String, day: Long) -> Unit = { _, _ -> },
    save: (Pay) -> Unit
) {
    // A correction landing is worth feeling; opening the form to make one is not.
    val touch = rememberTouch()
    var name by remember { mutableStateOf(pay.name) }
    var amount by remember { mutableStateOf(amountText(pay.amount)) }
    var day by remember { mutableStateOf(pay.day.toString()) }
    var currency by remember { mutableStateOf(pay.currency) }
    var warnDays by remember { mutableIntStateOf(pay.warnDays) }
    var trialEnd by remember { mutableLongStateOf(pay.trialEnd) }
    var promo by remember { mutableStateOf(amountText(pay.promoPrice)) }
    var billingMonth by remember { mutableIntStateOf(pay.billingMonth) }
    var emoji by remember { mutableStateOf(pay.emoji) }
    var cancelUrl by remember { mutableStateOf(pay.cancelUrl) }
    var order by remember { mutableStateOf(pay.order) }
    val today = remember { LocalDate.now() }
    var plan by remember { mutableStateOf(isInstalment(pay)) }
    var planCount by remember { mutableStateOf(if (isInstalment(pay)) pay.instalments.toString() else "") }
    var planDone by remember { mutableStateOf(instalmentsBehind(pay, today).toString()) }
    // The life actions ask first; each then acts on the payment as it is stored and
    // closes the sheet, like «Видалити».
    var asking by remember { mutableStateOf<String?>(null) }
    FormSheet(
        title = "Змінити витрату",
        confirmLabel = "Зберегти",
        confirmEnabled = parseAmount(amount) > 0 && name.isNotBlank(),
        onConfirm = {
            parseAmount(amount).takeIf { it > 0 }?.let { value ->
                touch.landed()
                save(
                    // The amount and the currency go through [edited] rather than
                    // straight into the copy: this is the one moment the app can
                    // learn that a subscription has raised its price, and writing
                    // the new figure over the old one is how that moment was lost.
                    edited(pay, value, currency, today.toEpochDay()).copy(
                        name = name.trim().ifBlank { pay.name },
                        day = day.toIntOrNull()?.coerceIn(1, 31) ?: pay.day,
                        warnDays = warnDays,
                        trialEnd = trialEnd,
                        promoPrice = if (trialEnd > 0L) parseAmount(promo).coerceAtLeast(0.0) else 0.0,
                        billingMonth = if (plan) 0 else billingMonth,
                        emoji = emoji,
                        instalments = planTotal(plan, planCount),
                        instalmentStart = planStart(
                            plan,
                            planCount,
                            planDone,
                            day,
                            today
                        ),
                        // Kept only when it is an address; an empty field means the built-in one.
                        cancelUrl = cancelUrl.trim().takeIf { it.isBlank() || isSupportedWebUrl(it) } ?: pay.cancelUrl,
                        order = if (plan) order else ""
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
        EmojiField(emoji, payEmoji(name)) { emoji = it }
        CurrencySegments(currency) { currency = it }
        NumberField(if (currency == USD) "Сума, $" else "Сума, ₴", amount) { amount = it }
        // The last move, directly under the field showing today's figure. This is
        // the whole point of keeping a history: a subscription raises its price
        // quietly, and the only defence is the old number sitting beside the new.
        amountHistoryNote(pay)?.let { note ->
            Text(
                note,
                Modifier.padding(top = Space.xs),
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        }
        if (!plan) BillingSegments(billingMonth, today) { billingMonth = it }
        NumberField("День оплати", day) { day = it }
        InstalmentFields(
            on = plan,
            count = planCount,
            done = planDone,
            day = day.toIntOrNull()?.coerceIn(1, 31) ?: pay.day,
            today = today,
            setOn = { plan = it },
            setCount = { planCount = it },
            setDone = { planDone = it },
            stopped = isInstalment(pay) && pay.stopsAfter > 0L
        )
        PromoField(trialEnd, promo, parseAmount(amount), currency, today, { trialEnd = it }, { promo = it })
        // The reminder counts to this date, not to the free renewal before it. A
        // promo charges from its first date, so only a free period gets the note.
        if (parseAmount(promo) <= 0.0) {
            firstChargeNote(
                day.toIntOrNull()?.coerceIn(1, 31) ?: pay.day,
                trialEnd,
                today,
                billingMonth
            )
                ?.let { note ->
                    Text(
                        note,
                        Modifier.padding(top = Space.xs),
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine
                    )
                }
        }
        WarnDaysChips(warnDays) { warnDays = it }
        // A payment's life after it starts — PaymentsLife.kt. A plan ends by its own
        // buttons; anything else can be cancelled (with «Як скасувати» beside a
        // subscription or a trial) or paused.
        if (plan || isInstalment(pay)) {
            PlanActions(
                pay = pay,
                today = today,
                orders = orders,
                order = order,
                setOrder = { order = it },
                onPaidOff = { asking = ASK_PAY_OFF },
                onReturned = { asking = ASK_RETURN },
                onRestart = { save(unstopped(pay)) }
            )
        } else {
            if (cancelHelpFits(pay, today)) CancelHelp(pay, cancelUrl) { cancelUrl = it }
            LifeActions(
                pay = pay,
                today = today,
                onCancel = { asking = ASK_CANCEL },
                onPause = { save(paused(pay, today)) },
                onResume = { save(resumed(pay, today)) },
                onRestart = { save(unstopped(pay)) }
            )
        }
        val trail = amountTrailLines(pay)
        if (trail.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = Space.md)) {
                Text("Історія суми", color = TextSecondary, fontSize = Type.captionSize)
                trail.forEach { line ->
                    Text(
                        line,
                        Modifier.padding(top = Space.xs),
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine
                    )
                }
            }
        }
        // Deleting used to sit on the row itself, a thumb's width from the tap
        // that opens this form, and it asked nothing before erasing.
        Spacer(Modifier.height(Space.md))
        // Silent on purpose. The sheet closing and the row leaving the list say
        // what happened, and the bin holds it if that was a mistake. The old buzz
        // here was a long-press constant fired on a plain tap — it named a gesture
        // nobody had made, and a confirming tick on an erasure would be no better.
        TextButton(
            { delete() },
            Modifier.fillMaxWidth()
        ) {
            Text("Видалити витрату", color = Negative)
        }
    }
    when (asking) {
        ASK_CANCEL -> CancelDialog(
            name = pay.name,
            initial = paidUntil(pay, today, marks),
            today = today,
            onDismiss = { asking = null }
        ) { until ->
            asking = null
            save(cancelled(pay, until, today))
            // Already over: nothing left to ask about, so it goes to the bin now.
            if (until.isBefore(today)) delete()
        }
        ASK_PAY_OFF -> PayOffDialog(
            pay = pay,
            recorded = payOffMarks(marks, pay, today)
                .firstOrNull { it.name == pay.name && it.month == monthKey(today) }?.amount
                ?: payOffSum(pay, today),
            today = today,
            onDismiss = { asking = null }
        ) {
            asking = null
            setMarks(payOffMarks(marks, pay, today))
            save(paidOff(pay, today))
        }
        ASK_RETURN -> ReturnDialog(
            pay = pay,
            order = orders.firstOrNull { it.id == order },
            onDismiss = { asking = null }
        ) {
            asking = null
            save(returned(pay.copy(order = order), today))
            if (order.isNotBlank()) onReturned(order, today.toEpochDay())
        }
    }
}

/** Which question the payment sheet is asking before a life action. */
private const val ASK_CANCEL = "cancel"
private const val ASK_PAY_OFF = "payoff"
private const val ASK_RETURN = "return"

/**
 * Turns a wish into a parcel.
 *
 * The tracking number is optional, because you often order first and learn the
 * number hours later. The item keeps its name, photo, link and price, so the
 * purchases tab shows the same thing you had been saving for, and it can be
 * filled in from that card afterwards.
 */
@Composable
fun BoughtSheet(wish: Wish, close: () -> Unit, confirm: (tracking: String, paid: Double) -> Unit) {
    var trackingNumber by remember { mutableStateOf("") }
    // Prefilled with the watched price, because most of the time that is what was
    // paid, and an empty field here is the one that gets skipped — which would
    // leave the purchase unjudgeable for ever.
    var paidText by remember { mutableStateOf(amountText(wish.price)) }
    // A game has no parcel number to ask for, and a field asking for one is the
    // form insisting it is a parcel after all.
    val digital = remember(wish.url) { isDigitalStore(wish.url) }
    FormSheet(
        title = "Купив це",
        confirmLabel = "Перенести в покупки",
        confirmEnabled = true,
        onConfirm = { confirm(if (digital) "" else trackingNumber.trim(), parseAmount(paidText)) },
        onDismiss = close
    ) {
        Text(wish.name, fontSize = Type.captionSize, color = TextSecondary)
        // The reason, read back once more at the moment of paying.
        if (wish.why.isNotBlank()) {
            Text(
                "Навіщо: «${wish.why}»",
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                color = TextPrimary,
                modifier = Modifier.padding(top = Space.sm)
            )
        }
        Text(
            if (digital) {
                "Товар переїде в Покупки як цифрова покупка, без доставки. Сума потрібна, " +
                    "щоб потім чесно сказати, чи варто було чекати."
            } else {
                "Товар переїде в Покупки зі статусом «Замовлено». Сума потрібна, щоб " +
                    "потім чесно сказати, чи варто було чекати."
            },
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine,
            color = TextSecondary,
            modifier = Modifier.padding(top = Space.sm)
        )
        NumberField("Скільки заплатили, ₴", paidText) { paidText = it }
        if (!digital) {
            OutlinedTextField(
                trackingNumber,
                { trackingNumber = it },
                Modifier.fillMaxWidth().padding(top = Space.md),
                label = { Text("Трек-номер, якщо вже є") },
                singleLine = true
            )
        }
    }
}

/**
 * The last step of a purchase: what it cost, how much it gets used, and done.
 *
 * Opened when the parcel is in your hands, and again later whenever the use count
 * has moved. Correcting the sum is allowed for the same reason it is asked for at
 * all — a verdict computed from a figure nobody could fix would be decoration.
 */
@Composable
fun CloseOrderSheet(
    order: Order,
    close: () -> Unit,
    /** «Не таке, як на фото»: the purchase filed, for the return sheet to take over. */
    onReturn: ((Order) -> Unit)? = null,
    save: (Order) -> Unit
) {
    var paidText by remember(order.id) {
        mutableStateOf(amountText(if (order.paid > 0) order.paid else order.price))
    }
    var usesText by remember(order.id) {
        mutableStateOf(if (order.uses > 0) order.uses.toString() else "")
    }
    val paid = parseAmount(paidText)
    val uses = usesText.trim().toIntOrNull() ?: 0
    // Only asked when the purchase is being filed. Later edits are about use.
    val filing = order.archivedDay <= 0L
    var returnDays by remember(order.id) { mutableIntStateOf(defaultReturnDays(order)) }
    // The warranty runs from the day the carrier says it was collected, else from
    // the day it is filed. Asked when filing, and changeable later from the archive.
    val today = remember { LocalDate.now() }
    val filedDay = order.archivedDay.takeIf { it > 0L } ?: today.toEpochDay()
    val warrantyFrom = remember(order.id) { warrantyStart(order, filedDay) }
    var warrantyUntil by remember(order.id) { mutableLongStateOf(order.warrantyUntil) }

    /** The purchase as this sheet would file it. */
    fun filed(): Order = order.copy(
        paid = paid,
        uses = uses.coerceAtLeast(0),
        status = RECEIVED,
        // Filed on the day it was closed, and never re-dated by a later
        // correction to the use count.
        archivedDay = filedDay,
        returnBy = if (filing) {
            returnDays.takeIf { it > 0 }?.let { today.toEpochDay() + it } ?: 0L
        } else {
            order.returnBy
        },
        warrantyUntil = warrantyUntil
    )

    FormSheet(
        title = if (order.archivedDay > 0L) "Покупка в архіві" else "Завершити покупку",
        confirmLabel = if (order.archivedDay > 0L) "Зберегти" else "В архів",
        confirmEnabled = paid > 0.0,
        onConfirm = { save(filed()) },
        onDismiss = close
    ) {
        Text(order.name, fontSize = Type.captionSize, color = TextSecondary)
        NumberField("Скільки заплатили, ₴", paidText) { paidText = it }
        if (filing) {
            Text(
                "Повернути можна",
                color = TextSecondary,
                fontSize = Type.captionSize,
                modifier = Modifier.padding(top = Space.md)
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                RETURN_CHOICES.forEach { days ->
                    FilterChip(
                        selected = returnDays == days,
                        onClick = { returnDays = days },
                        label = { Text(returnChoiceLabel(days)) }
                    )
                }
            }
            Text(
                RETURN_NOTE,
                color = TextDisabled,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )
            // «Як на фото? Ні» — the quick way into a return, at the moment the
            // parcel has just been opened. Filed with what is typed above.
            if (onReturn != null && !order.digital) {
                TextButton(
                    { onReturn(filed()) },
                    Modifier.padding(top = Space.xs),
                    enabled = paid > 0.0
                ) {
                    Text("Не таке, як на фото? ")
                    EmojiGlyph("↩️", 16.dp)
                    Text(" Повертаю")
                }
            }
        }
        if (!isRefunded(order)) {
            WarrantyPicker(warrantyFrom, warrantyUntil, today) { warrantyUntil = it }
        }
        OutlinedTextField(
            usesText,
            { usesText = it.filter(Char::isDigit).take(5) },
            Modifier.fillMaxWidth().padding(top = Space.md),
            label = { Text("Скільки разів скористались") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Text(
            // Porting the point across without lecturing: the number is optional,
            // and this says what it buys you.
            "Необов'язково. Ціна за одне користування каже про річ більше, ніж її цінник.",
            color = TextSecondary,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine,
            modifier = Modifier.padding(top = Space.sm)
        )
        // A purchase sent back is not judged: it was not kept.
        if (paid > 0.0 && countsAsBought(order)) {
            val review = purchaseReview(paid, order.lowestSeen, uses)
            Text(
                purchaseVerdictLabel(review.verdict),
                color = verdictInk(review.verdict),
                fontSize = Type.cardTitleSize,
                fontWeight = Type.medium,
                modifier = Modifier.padding(top = Space.lg)
            )
            Text(
                purchaseVerdictDetail(review),
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )
            costPerUseLine(review)?.let {
                Text(
                    it,
                    color = TextPrimary,
                    fontSize = Type.captionSize,
                    modifier = Modifier.padding(top = Space.xs)
                )
            }
        }
    }
}

/** One colour per verdict, so the archive and the sheet cannot disagree. */
fun verdictInk(verdict: PurchaseVerdict): Color = when (verdict) {
    PurchaseVerdict.PATIENT -> Accent
    PurchaseVerdict.HASTY -> Negative
    PurchaseVerdict.UNJUDGED -> TextSecondary
}

/**
 * Sets the monthly income.
 *
 * Only ever stored on the phone, and only used to subtract the standing costs from
 * it, so the wishlist can plan against a real figure instead of a guess.
 */
@Composable
fun IncomeDialog(
    current: Double,
    close: () -> Unit,
    /**
     * The payday, asked here because it is part of the same question — when the
     * money comes. Null leaves the dialog as it was. See MoneyPlan.kt.
     */
    payday: Payday? = null,
    holidays: Set<Long> = emptySet(),
    savePayday: (Payday) -> Unit = {},
    save: (Double) -> Unit
) {
    var text by remember { mutableStateOf(amountText(current)) }
    var day by remember { mutableStateOf(payday ?: Payday()) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Дохід на місяць") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Потрібен лише для того, щоб порахувати, скільки лишається після " +
                        "постійних витрат. Нікуди не надсилається.",
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
                NumberField("Сума, ₴", text) { text = it }
                if (payday != null) PaydayFields(day, LocalDate.now(), holidays) { day = it }
            }
        },
        confirmButton = {
            Button({
                if (payday != null && day != payday) savePayday(day)
                save(parseAmount(text))
            }) { Text("Зберегти") }
        },
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
/** How many payments a plan has, from the form: nought when it is not a plan. */
fun planTotal(on: Boolean, count: String): Int =
    if (on) (count.trim().toIntOrNull() ?: 0).coerceIn(0, MAX_INSTALMENTS) else 0

/** The plan's first payment, from «усього» and «уже сплачено». Nought when it is not a plan. */
fun planStart(on: Boolean, count: String, done: String, day: String, today: LocalDate): Long {
    val total = planTotal(on, count)
    if (total <= 0) return 0L
    val behind = (done.trim().toIntOrNull() ?: 0).coerceIn(0, total)
    return instalmentStartFor(day.toIntOrNull()?.coerceIn(1, 31) ?: 1, behind, today)
}

/** Nobody pays anything off in more than ten years of monthly payments. */
const val MAX_INSTALMENTS = 120

/**
 * «Частинами»: a switch, then how many payments and how many are already behind,
 * and the date it all ends — said back, because that date is the point.
 */
@Composable
fun InstalmentFields(
    on: Boolean,
    count: String,
    done: String,
    day: Int,
    today: LocalDate,
    setOn: (Boolean) -> Unit,
    setCount: (String) -> Unit,
    setDone: (String) -> Unit,
    /** Paid off early or returned: the date below is the schedule's, not the plan's end. */
    stopped: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.sm).clickable { setOn(!on) }.padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = Space.md)) {
            Text("Частинами", fontSize = Type.bodySize, fontWeight = Type.medium)
            Text(
                "Розстрочка чи кредит: після останнього платежу зникне з розкладу сама",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        }
        Switch(on, setOn)
    }
    if (on) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Box(Modifier.weight(1f)) { NumberField("Усього платежів", count, setCount) }
            Box(Modifier.weight(1f)) { NumberField("Уже сплачено", done, setDone) }
        }
        val total = planTotal(true, count)
        if (total > 0) {
            val behind = (done.trim().toIntOrNull() ?: 0).coerceIn(0, total)
            val sample = Pay("", 0.0, day, instalments = total, instalmentStart = instalmentStartFor(day, behind, today))
            Text(
                when {
                    behind >= total -> "Усі платежі вже позаду"
                    stopped -> "За графіком останній платіж — ${formatDate(instalmentLast(sample))}"
                    else -> "Останній платіж — ${formatDate(instalmentLast(sample))}"
                },
                Modifier.padding(top = Space.xs),
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        }
    }
}

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
    val touch = rememberTouch()
    SegmentedControl(
        options = listOf("Гривня ₴", "Долар $"),
        selected = if (currency == USD) 1 else 0,
        modifier = Modifier.padding(top = Space.md)
    ) { index ->
        val dollars = index == 1
        // The same switch as the one on the exchange screen, and it earns the same
        // answer: which currency an expense is billed in changes what every total
        // on the payments screen means, and it is set by tapping half of a control
        // whose two halves look alike.
        if (dollars != (currency == USD)) touch.switched(dollars)
        set(if (dollars) USD else UAH)
    }
}

/**
 * Picks how often an expense is charged, and when the annual one lands.
 *
 * The month only appears once "раз на рік" is chosen, because an annual charge
 * without a month is not a thing the app could put on a calendar — which is why
 * the rhythm and the month are one field on [Pay] rather than two that can
 * disagree. Switching back to monthly clears it, so nothing keeps a stale March.
 */
@Composable
fun BillingSegments(billingMonth: Int, today: LocalDate, set: (Int) -> Unit) {
    val annual = billingMonth in 1..MONTHS_IN_YEAR
    Column(Modifier.fillMaxWidth()) {
        SegmentedControl(
            options = listOf("Щомісяця", "Раз на рік"),
            selected = if (annual) 1 else 0,
            modifier = Modifier.padding(top = Space.md)
        ) { index -> set(if (index == 1) billingMonth.takeIf { annual } ?: today.monthValue else 0) }
        if (annual) {
            Text(
                "Місяць списання",
                Modifier.padding(top = Space.md),
                color = TextSecondary,
                fontSize = Type.captionSize
            )
            LazyRow(
                Modifier.padding(top = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                items((1..MONTHS_IN_YEAR).toList()) { month ->
                    FilterChip(
                        billingMonth == month,
                        { set(month) },
                        { Text(monthShort(month), fontSize = Type.captionSize) }
                    )
                }
            }
        }
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
fun TrackingDialog(
    order: Order,
    close: () -> Unit,
    save: (tracking: String, digital: Boolean, name: String, phone: String) -> Unit
) {
    var number by remember { mutableStateOf(order.tracking) }
    // Editable, because a purchase added from a page that would not open is named
    // after its shop, and «Товар з temu.com» is nobody's name for a controller.
    var name by remember { mutableStateOf(order.name) }
    // The kind is corrected here as well, because the shop list in Purchases.kt
    // guesses and this is where a wrong guess is noticed.
    var digital by remember { mutableStateOf(order.digital) }
    // A parcel for somebody else: their number, so Nova Poshta answers in full.
    var phone by remember { mutableStateOf(order.recipientPhone) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (digital) "Покупка" else "Трек-номер") },
        text = {
            Column {
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Назва") },
                    singleLine = true
                )
                OrderKindControl(digital, Modifier.padding(top = Space.md)) { digital = it }
                if (!digital) {
                    OutlinedTextField(
                        number,
                        { number = it },
                        Modifier.fillMaxWidth().padding(top = Space.md),
                        label = { Text("Номер відправлення") },
                        singleLine = true
                    )
                    if (detectCarrier(number) == CARRIER_NOVA_POSHTA) {
                        RecipientPhoneField(phone) { phone = it }
                    }
                }
            }
        },
        confirmButton = {
            Button({
                save(
                    if (digital) "" else number.trim(),
                    digital,
                    name.trim().ifBlank { order.name },
                    // Kept only when it reads as a number; anything else is dropped
                    // rather than sent to the carrier as if it were one.
                    if (digital) "" else normalizedPhone(phone)
                )
            }) {
                Text("Зберегти")
            }
        },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
}

/**
 * Parcel or download, as one control with one marker.
 *
 * The same two-halves track the rest of the app uses for an either-or, so it cannot
 * be read as two independent switches.
 */
@Composable
fun OrderKindControl(digital: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    SegmentedControl(
        options = listOf("Посилка", "Без доставки"),
        selected = if (digital) 1 else 0,
        modifier = modifier
    ) { onChange(it == 1) }
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
        Modifier.fillMaxWidth().litEdge(Radius.md),
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


