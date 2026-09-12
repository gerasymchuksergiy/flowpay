package com.flowpay.app

import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Whether the background passes are running at all.
 *
 * Everything the app claims rests on work it does while nobody is looking: the
 * prices it says have not moved, the parcel it says has not arrived, the digest it
 * says had nothing in it. A worker that is never allowed to run produces exactly
 * the same screen as a quiet week, and that silence is indistinguishable from
 * working correctly — which is the worst failure mode an app can have.
 *
 * This is the phone it actually runs on: a Redmi on HyperOS, where autostart is
 * off by default and the power manager is the most aggressive of any Android OEM.
 * So the app records when each pass last finished, says so out loud, and where the
 * platform will name the reason a job is waiting, it repeats it.
 */

/** The unique work names, which are also the keys the last run is stored under. */
const val WORK_PRICES = "prices"
const val WORK_DIGEST = "payment-reminders"

/** What a pass is called on screen. */
fun workLabel(key: String): String = when (key) {
    WORK_PRICES -> "Ціни й посилки"
    WORK_DIGEST -> "Ранкове зведення"
    else -> key
}

/**
 * How long a pass may stay silent before something is wrong.
 *
 * Both allow more than one missed turn. WorkManager shifts periodic work around to
 * batch it with other apps' jobs, so a pass landing a few hours late is ordinary;
 * it is a pass that misses a whole cycle that means the OEM killed it.
 */
fun staleAfterHours(key: String): Int = when (key) {
    // Every twelve hours, so a full missed pass plus a wide margin.
    WORK_PRICES -> 30
    // Once a day at a chosen hour, so two missed mornings.
    else -> 48
}

/** A pass, and when it last got all the way through. Zero means never. */
data class WorkRun(val key: String, val atMillis: Long)

enum class WorkState {
    /** Has not finished once. On a fresh install this is normal and says nothing. */
    NEVER,
    FRESH,
    STALE
}

fun workState(run: WorkRun, nowMillis: Long): WorkState = when {
    run.atMillis <= 0L -> WorkState.NEVER
    nowMillis - run.atMillis > staleAfterHours(run.key) * 3_600_000L -> WorkState.STALE
    else -> WorkState.FRESH
}

/**
 * When a pass last finished, in words.
 *
 * A clock time for anything inside the last two days, because "о 10:05" is what
 * you check it against; a count of days beyond that, because the exact minute of
 * something four days old carries no information.
 */
fun lastRunLabel(atMillis: Long, nowMillis: Long): String {
    if (atMillis <= 0L) return "ще не виконувалась"
    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val days = (now.toEpochDay() - then.toEpochDay()).toInt()
    return when {
        // A run stamped in the future is a clock that moved, not a run to boast of.
        days < 0 -> "ще не виконувалась"
        days == 0 -> "сьогодні о ${timeLabel(atMillis)}"
        days == 1 -> "вчора о ${timeLabel(atMillis)}"
        else -> "${daysLabel(days)} тому"
    }
}

// ------------------------------------------------------------ pending reasons

/**
 * The Android version that will say why a job is still waiting.
 *
 * Android 16 added `JobScheduler.getPendingJobReasons(jobId)` and its history.
 * Android 14 has a single-reason `getPendingJobReason`, but it answers with one
 * code where the interesting cases have several at once, so nothing below 16 is
 * asked: half an answer about why background work is dead is worse than admitting
 * the platform will not say.
 */
const val REASONS_API = 36

/**
 * One reason code in Ukrainian, or null for the ones not worth repeating.
 *
 * Null covers the codes that describe an ordinary waiting job rather than a
 * blocked one — it is running right now, it is inside its own latency window, it
 * is a kind of constraint this app never sets. Printing those would bury the two
 * or three that mean the OEM has stopped the app.
 */
fun pendingReasonLabel(code: Int): String? = when (code) {
    JobScheduler.PENDING_JOB_REASON_APP_STANDBY ->
        "система приспала застосунок — ним давно не користувались"
    JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION ->
        "фонову роботу застосунку заборонено"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "замало заряду"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING -> "чекає на зарядку"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY -> "немає мережі"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE -> "телефон у режимі сну"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "замало місця у пам'яті"
    JobScheduler.PENDING_JOB_REASON_DEVICE_STATE -> "режим енергозбереження"
    JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION -> "система економить ресурси"
    JobScheduler.PENDING_JOB_REASON_QUOTA -> "вичерпано ліміт фонової роботи"
    JobScheduler.PENDING_JOB_REASON_USER -> "заборонено в налаштуваннях телефона"
    JobScheduler.PENDING_JOB_REASON_APP -> "застосунок сам відклав завдання"
    else -> null
}

/**
 * Everything the platform will say about why the work is waiting.
 *
 * Below [REASONS_API] this is honest about not knowing rather than guessing from
 * the battery state, because a guess that says "енергозбереження" on a phone where
 * that is switched off sends the user to the wrong settings screen.
 */
fun pendingReasonsLine(codes: List<Int>, apiLevel: Int): String {
    if (apiLevel < REASONS_API) return "Ця версія Android не називає причину"
    val named = codes.mapNotNull { pendingReasonLabel(it) }.distinct()
    if (named.isEmpty()) return "Система не називає жодної перешкоди"
    return named.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

// ------------------------------------------------------------ the whole picture

data class WorkHealth(
    val runs: List<WorkRun>,
    /** Reason codes for this app's pending jobs. Empty when the platform will not say. */
    val pendingReasons: List<Int>,
    val apiLevel: Int,
    val nowMillis: Long
)

/** The single line the overview carries, and whether it should look like an alarm. */
data class HealthLine(val title: String, val detail: String, val alarm: Boolean)

/**
 * What the strip says.
 *
 * A pass that has never run is stated plainly but not raised as an alarm: on a
 * phone the app was installed on an hour ago that is simply the truth, and crying
 * about it would teach the user to ignore the strip before it ever mattered.
 */
fun healthLine(health: WorkHealth): HealthLine {
    // Kept as a list rather than a map: two passes could share a timestamp, and
    // equal data classes would collapse into one key and lose a whole pass.
    val states = health.runs.map { it to workState(it, health.nowMillis) }
    val stale = states.filter { it.second == WorkState.STALE }.map { it.first }
    val fresh = states.filter { it.second == WorkState.FRESH }.map { it.first }

    if (stale.isNotEmpty()) {
        val worst = stale.minBy { it.atMillis }
        return HealthLine(
            title = "Фонове оновлення не працює",
            detail = "${workLabel(worst.key)} — ${lastRunLabel(worst.atMillis, health.nowMillis)}",
            alarm = true
        )
    }
    if (fresh.isEmpty()) {
        return HealthLine(
            title = "Фонове оновлення ще не виконувалось",
            detail = "перша перевірка буде за розкладом",
            alarm = false
        )
    }
    val newest = fresh.maxBy { it.atMillis }
    return HealthLine(
        title = "Фонове оновлення працює",
        detail = "${workLabel(newest.key)} — ${lastRunLabel(newest.atMillis, health.nowMillis)}",
        alarm = false
    )
}

// ------------------------------------------------------------ where to send them

/**
 * The screen an OEM hides its autostart list behind, or null for stock Android.
 *
 * Autostart is not an Android concept and has no public intent, so each of these
 * is a component name that only exists on that maker's build. They are tried and
 * allowed to fail; the stock battery screen is always offered as well, because a
 * component that has been renamed must not leave the button doing nothing.
 */
fun autostartComponent(manufacturer: String): Pair<String, String>? =
    when (manufacturer.lowercase()) {
        // The phone this app runs on. HyperOS keeps the same MIUI component name.
        "xiaomi", "redmi", "poco" ->
            "com.miui.securitycenter" to
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
        "huawei", "honor" ->
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        "oppo", "realme" ->
            "com.coloros.safecenter" to
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
        "vivo" ->
            "com.vivo.permissionmanager" to
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        else -> null
    }

/**
 * Opens the best available place to let the app run in the background.
 *
 * The OEM's own autostart list first, since on HyperOS that is the switch that
 * actually decides it; then battery optimisation; then the app's own settings
 * page, which exists on every Android there is.
 */
fun openBackgroundSettings(context: Context) {
    val candidates = buildList {
        autostartComponent(Build.MANUFACTURER)?.let { (pkg, activity) ->
            add(Intent().setComponent(ComponentName(pkg, activity)))
        }
        add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${context.packageName}".toUri())
        )
    }
    for (intent in candidates) {
        val opened = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
}

/**
 * Why this app's jobs are waiting, as the platform sees them.
 *
 * WorkManager does not expose its own job ids, so every pending job belonging to
 * this app is asked and the answers are pooled. Empty on anything below Android 16
 * and empty when nothing is pending, which the caller tells apart by the API level
 * it also passes to [pendingReasonsLine].
 *
 * The history is consulted when the live answer is empty. A job that is not
 * blocked at the exact second the panel is opened can still have spent the whole
 * night blocked, and the night is the part that matters.
 */
fun pendingJobReasons(context: Context): List<Int> {
    if (Build.VERSION.SDK_INT < REASONS_API) return emptyList()
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return emptyList()
    return runCatching {
        val jobs = scheduler.allPendingJobs
        val live = jobs.flatMap { scheduler.getPendingJobReasons(it.id).toList() }.distinct()
        live.ifEmpty {
            jobs.flatMap { job ->
                scheduler.getPendingJobReasonsHistory(job.id)
                    .maxByOrNull { it.timestampMillis }
                    ?.pendingJobReasons?.toList()
                    .orEmpty()
            }.distinct()
        }
    }.getOrDefault(emptyList())
}
