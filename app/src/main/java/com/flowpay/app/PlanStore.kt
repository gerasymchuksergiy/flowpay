package com.flowpay.app

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/**
 * Where MoneyPlan.kt keeps what it is told, in the app's own preferences file.
 * Every key starts with «mp_», so nothing another part of the app writes can
 * collide with it.
 *
 * Only the funds are app data — money the owner says he put aside — and they go
 * into the backup with the wishes (see [Store.exportJson]). «На життя», the
 * payday, the typed balance and the ritual's record are preferences about this
 * phone, like the income beside them, and stay out of it.
 */
class PlanStore(context: Context) {
    private val prefs = context.getSharedPreferences("flowpay", Context.MODE_PRIVATE)

    fun life(): LifeCost = LifeCost(prefs.getBoolean("mp_life_on", false), prefs.getFloat("mp_life", 0f).toDouble())

    fun saveLife(life: LifeCost) = prefs.edit {
        putBoolean("mp_life_on", life.on)
        putFloat("mp_life", life.monthly.coerceAtLeast(0.0).toFloat())
    }

    fun payday(): Payday = Payday(prefs.getInt("mp_payday", 0), prefs.getInt("mp_advance", 0))

    fun savePayday(payday: Payday) = prefs.edit {
        putInt("mp_payday", payday.salary)
        putInt("mp_advance", payday.advance)
    }

    /** «Зараз на картці»: the last figure typed, and the epoch day it was typed. */
    fun cash(): Pair<Double, Long> = prefs.getFloat("mp_cash", 0f).toDouble() to prefs.getLong("mp_cash_day", 0L)

    fun saveCash(amount: Double, day: Long) = prefs.edit {
        putFloat("mp_cash", amount.coerceAtLeast(0.0).toFloat())
        putLong("mp_cash_day", day)
    }

    fun settings(): PlanSettings {
        val (cash, day) = cash()
        return PlanSettings(life(), payday(), cash, day)
    }

    fun saveSettings(settings: PlanSettings) {
        saveLife(settings.life)
        savePayday(settings.payday)
        saveCash(settings.cash, settings.cashDay)
    }

    fun funds(): List<Fund> = fundsOf(prefs.getString(FUNDS_KEY, "[]"))

    fun saveFunds(funds: List<Fund>) = prefs.edit { putString(FUNDS_KEY, fundsJson(funds)) }

    fun ritual(): RitualRecord? = ritualOf(prefs.getString("mp_ritual", null))

    fun saveRitual(record: RitualRecord?) = prefs.edit {
        if (record == null) remove("mp_ritual") else putString("mp_ritual", ritualJson(record))
    }

    /** Annual payments whose fund offer got «Не треба», by name. */
    fun declinedFunds(): Set<String> = prefs.getStringSet("mp_fund_no", emptySet())?.toSet() ?: emptySet()

    fun declineFund(name: String) = prefs.edit { putStringSet("mp_fund_no", declinedFunds() + name) }

    companion object {
        /** The funds' key, which the backup and the bin also read. */
        const val FUNDS_KEY = "mp_funds"
    }
}

/** What the owner told the plan: life, the payday, and the last balance typed. */
data class PlanSettings(
    val life: LifeCost = LifeCost(),
    val payday: Payday = Payday(),
    val cash: Double = 0.0,
    val cashDay: Long = 0L
)

/** Everything MoneyPlan needs, read off the phone — for the widget, the tile and the morning message. */
fun moneyInputs(
    context: Context,
    store: Store,
    today: LocalDate,
    usdSell: Double,
    marks: List<PaidMark> = store.paidMarks(today)
): MoneyInputs {
    val plan = PlanStore(context)
    return MoneyInputs(
        today = today,
        income = store.income(),
        pays = store.pays(),
        marks = marks,
        wishes = store.wishes(),
        funds = plan.funds(),
        usdSell = usdSell,
        payday = plan.payday(),
        holidays = store.holidaysAround(today),
        ritual = plan.ritual()
    )
}

/** The month's «Вільно» as Огляд shows it, for the surfaces outside the app. */
fun honestBudget(context: Context, store: Store, usdSell: Double, today: LocalDate): Budget =
    moneyPlan(moneyInputs(context, store, today, usdSell)).month.asBudget()
