package com.flowpay.app

import android.app.job.JobScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Whether the app can tell that it has stopped working.
 *
 * A background pass that is never allowed to run leaves exactly the same screens
 * as a quiet week: no price moved, no parcel arrived, no digest had anything in
 * it. On the Redmi this runs on that is the expected state rather than the odd
 * one, so the app has to be able to say "I have not run since Tuesday" — and,
 * just as importantly, must not cry wolf on a phone where nothing is wrong yet.
 */
class BackgroundTest {

    private val day = LocalDate.of(2026, 9, 15)

    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val now = at(day, 18, 0)

    private fun hoursAgo(hours: Int): Long = now - hours * 3_600_000L

    // ------------------------------------------------------------ when it last ran

    @Test
    fun `a pass that has never finished says so instead of inventing a time`() {
        assertEquals("ще не виконувалась", lastRunLabel(0L, now))
    }

    @Test
    fun `a clock that jumped backwards does not produce a run in the future`() {
        assertEquals("ще не виконувалась", lastRunLabel(at(day.plusDays(2), 9, 0), now))
    }

    @Test
    fun `a recent run is given as a clock time, an old one as a count of days`() {
        assertEquals("сьогодні о 10:05", lastRunLabel(at(day, 10, 5), now))
        assertEquals("вчора о 10:05", lastRunLabel(at(day.minusDays(1), 10, 5), now))
        // Past two days the exact minute carries nothing, and "4 дні тому" is the
        // fact that matters.
        assertEquals("4 дні тому", lastRunLabel(at(day.minusDays(4), 10, 5), now))
    }

    // ------------------------------------------------------------ is it alive

    @Test
    fun `a pass with no recorded run is neither fresh nor stale`() {
        // A fresh install is in this state and nothing is wrong with it.
        assertEquals(WorkState.NEVER, workState(WorkRun(WORK_PRICES, 0L), now))
    }

    @Test
    fun `a pass allowed to slip a few hours is still working`() {
        // Twelve-hourly work gets batched with other apps' jobs, so a late pass is
        // ordinary. It is a whole missed cycle that means something killed it.
        assertEquals(WorkState.FRESH, workState(WorkRun(WORK_PRICES, hoursAgo(20)), now))
        assertEquals(WorkState.STALE, workState(WorkRun(WORK_PRICES, hoursAgo(31)), now))
    }

    @Test
    fun `the daily digest is given two missed mornings before it counts as dead`() {
        assertEquals(WorkState.FRESH, workState(WorkRun(WORK_DIGEST, hoursAgo(40)), now))
        assertEquals(WorkState.STALE, workState(WorkRun(WORK_DIGEST, hoursAgo(50)), now))
    }

    // ------------------------------------------------------------ why it is waiting

    @Test
    fun `an Android below 16 admits it cannot say why`() {
        // Android 14's single-reason call answers with one code where the
        // interesting cases have several, so nothing below 16 is asked at all.
        assertEquals(
            "Ця версія Android не називає причину",
            pendingReasonsLine(listOf(JobScheduler.PENDING_JOB_REASON_QUOTA), 34)
        )
        assertEquals(
            "Ця версія Android не називає причину",
            pendingReasonsLine(emptyList(), 26)
        )
    }

    @Test
    fun `the reasons the platform gives are read out in Ukrainian`() {
        assertEquals(
            "Система приспала застосунок — ним давно не користувались · немає мережі",
            pendingReasonsLine(
                listOf(
                    JobScheduler.PENDING_JOB_REASON_APP_STANDBY,
                    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY
                ),
                REASONS_API
            )
        )
    }

    @Test
    fun `codes that describe an ordinary wait are not repeated back`() {
        // A job that is running right now, or inside its own latency window, is
        // not blocked. Printing those would bury the ones that mean it is.
        assertNull(pendingReasonLabel(JobScheduler.PENDING_JOB_REASON_EXECUTING))
        assertNull(pendingReasonLabel(JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY))
        assertNull(pendingReasonLabel(JobScheduler.PENDING_JOB_REASON_UNDEFINED))
        assertEquals(
            "Система не називає жодної перешкоди",
            pendingReasonsLine(
                listOf(
                    JobScheduler.PENDING_JOB_REASON_EXECUTING,
                    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY
                ),
                REASONS_API
            )
        )
    }

    @Test
    fun `nothing pending on Android 16 is a different answer from not knowing`() {
        assertEquals(
            "Система не називає жодної перешкоди",
            pendingReasonsLine(emptyList(), REASONS_API)
        )
    }

    // ------------------------------------------------------------ the one line

    private fun health(vararg runs: WorkRun) =
        WorkHealth(runs.toList(), emptyList(), REASONS_API, now)

    @Test
    fun `a phone where nothing has run yet is told so without an alarm`() {
        val line = healthLine(
            health(WorkRun(WORK_PRICES, 0L), WorkRun(WORK_DIGEST, 0L))
        )

        assertEquals("Фонове оновлення ще не виконувалось", line.title)
        assertEquals("перша перевірка буде за розкладом", line.detail)
        // An app installed an hour ago is in exactly this state, and shouting about
        // it would teach the user to ignore the strip before it ever mattered.
        assertFalse(line.alarm)
    }

    @Test
    fun `a pass that has gone silent is named and reads as an alarm`() {
        val line = healthLine(
            health(
                WorkRun(WORK_PRICES, at(day.minusDays(3), 10, 0)),
                WorkRun(WORK_DIGEST, hoursAgo(2))
            )
        )

        assertEquals("Фонове оновлення не працює", line.title)
        assertEquals("Ціни й посилки — 3 дні тому", line.detail)
        assertTrue(line.alarm)
    }

    @Test
    fun `a working phone says when it last checked`() {
        val line = healthLine(
            health(
                WorkRun(WORK_PRICES, at(day, 10, 5)),
                WorkRun(WORK_DIGEST, at(day, 9, 0))
            )
        )

        assertEquals("Фонове оновлення працює", line.title)
        assertEquals("Ціни й посилки — сьогодні о 10:05", line.detail)
        assertFalse(line.alarm)
    }

    @Test
    fun `one pass that has never run does not condemn one that is working`() {
        val line = healthLine(
            health(WorkRun(WORK_PRICES, at(day, 10, 5)), WorkRun(WORK_DIGEST, 0L))
        )

        assertEquals("Фонове оновлення працює", line.title)
    }

    // ------------------------------------------------------------ the way out

    @Test
    fun `the phone this runs on hides autostart behind its own screen`() {
        assertEquals(
            "com.miui.securitycenter" to
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            autostartComponent("Xiaomi")
        )
        // The same build ships under three names.
        assertEquals(autostartComponent("Xiaomi"), autostartComponent("Redmi"))
        assertEquals(autostartComponent("Xiaomi"), autostartComponent("POCO"))
    }

    @Test
    fun `a phone with no autostart list falls through to the stock screens`() {
        assertNull(autostartComponent("Google"))
        assertNull(autostartComponent("samsung"))
        assertNull(autostartComponent(""))
    }

    @Test
    fun `each pass is named in words rather than by its work id`() {
        assertEquals("Ціни й посилки", workLabel(WORK_PRICES))
        assertEquals("Ранкове зведення", workLabel(WORK_DIGEST))
    }
}
