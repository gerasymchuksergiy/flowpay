package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A number the user named, said once.
 *
 * Two failures matter here and neither of them is wording. One is a threshold that
 * fires every morning for the rest of the year, which is the behaviour that gets a
 * digest muted and takes the parcel and payment lines down with it. The other is a
 * direction worked out afresh each morning instead of at the moment the number was
 * set: a rate wandering back over the threshold would then answer the opposite of
 * the question that was asked, and answer it immediately.
 */
class RateTargetTest {

    private val today = LocalDate.of(2026, 9, 15).toEpochDay()

    // Figures reach the screen with a non-breaking space inside them, which no
    // test should have to type.
    private fun shown(text: String) = text.replace(' ', ' ')

    /** A Monobank reading: the sell rate, and a buy rate a little under it. */
    private fun bank(sell: Double) = FxRate(sell - 0.40, sell, SOURCE_MONOBANK)

    /** The NBU's one official figure, as [parseNbuRate] reads it. */
    private fun official(rate: Double) = FxRate(rate, rate, SOURCE_NBU, "15.09.2026")

    @Test
    fun `the direction is read off the rate when the number is set`() {
        // 42 named while the dollar sits below it is a question about a rise.
        val rise = armRateTarget(42.0, bank(41.30))
        assertNotNull(rise)
        assertTrue(rise!!.above)

        // The same 42 named while the dollar sits above it is the opposite question.
        val fall = armRateTarget(42.0, bank(42.60))
        assertNotNull(fall)
        assertFalse(fall!!.above)
    }

    @Test
    fun `a threshold cannot be armed without a rate to read the direction from`() {
        assertNull(armRateTarget(42.0, FxRate()))
        assertNull(armRateTarget(0.0, bank(41.30)))
        assertNull(armRateTarget(-1.0, bank(41.30)))
    }

    @Test
    fun `a rise is reached from below and is not reached by a fall`() {
        val target = armRateTarget(42.0, bank(41.30))!!

        assertFalse(rateTargetReached(target, 41.99))
        assertTrue(rateTargetReached(target, 42.0))
        assertTrue(rateTargetReached(target, 42.35))
        // The rate dropping away from a threshold set above it is not the event.
        assertFalse(rateTargetReached(target, 40.10))
    }

    @Test
    fun `a fall is reached from above and is not reached by a rise`() {
        val target = armRateTarget(40.0, bank(41.30))!!

        assertFalse(rateTargetReached(target, 40.01))
        assertTrue(rateTargetReached(target, 40.0))
        assertTrue(rateTargetReached(target, 39.50))
        assertFalse(rateTargetReached(target, 43.00))
    }

    @Test
    fun `a rate that crosses back does not reverse what was asked`() {
        // The whole reason the direction is stored rather than recomputed. Asked
        // while the dollar was at 41,30, this is a question about reaching 42.
        val target = armRateTarget(42.0, bank(41.30))!!

        // A week later the rate has run past the threshold and come back under it.
        // Recomputing the direction here would make it a question about falling to
        // 42 — already true — and it would fire on a fall nobody asked about.
        assertFalse(rateTargetReached(target, 41.10))
    }

    @Test
    fun `nothing is reached without a target or without a rate`() {
        assertFalse(rateTargetReached(null, 42.0))
        assertFalse(rateTargetReached(armRateTarget(42.0, bank(41.30)), 0.0))
    }

    @Test
    fun `once told it never speaks again`() {
        val armed = armRateTarget(42.0, bank(41.30))!!
        assertTrue(rateTargetReached(armed, 42.10))

        val told = disarmRateTarget(armed, today)
        // Still the same threshold, still crossed, and now permanently silent.
        assertEquals(42.0, told.rate, 0.001)
        assertEquals(today, told.hitDay)
        assertFalse(rateTargetReached(told, 42.10))
        assertFalse(rateTargetReached(told, 99.0))
        assertNull(rateTargetLine(told, bank(42.10), today, today))
    }

    @Test
    fun `the line names both the rate now and the number that was asked for`() {
        val target = armRateTarget(42.0, bank(41.30))!!

        assertNull(rateTargetLine(target, bank(41.80), today, today))
        assertEquals(
            "Долар 42,10 ₴ — курс перетнув 42,00",
            shown(rateTargetLine(target, bank(42.10), today, today)!!)
        )
        assertNull(rateTargetLine(null, bank(42.10), today, today))
    }

    @Test
    fun `figures in the line are Ukrainian whatever the phone is set to`() {
        val target = armRateTarget(42.0, bank(41.30))!!
        val line = rateTargetLine(target, bank(42.10), today, today)!!

        // A decimal point here would mean the line was formatted in the phone's
        // locale rather than the app's, which CI runs as en-US on purpose.
        assertTrue(line.contains("42,10"))
        assertFalse(line.contains("42.10"))
    }

    @Test
    fun `the screen says which way it is waiting and stops waiting once met`() {
        assertEquals(
            "Скажу один раз, коли курс дійде до вашого числа",
            rateTargetNote(null, bank(41.30))
        )
        assertEquals(
            "Скажу, коли курс підніметься до 42,00",
            shown(rateTargetNote(armRateTarget(42.0, bank(41.30)), bank(41.30)))
        )
        assertEquals(
            "Скажу, коли курс опуститься до 40,00",
            shown(rateTargetNote(armRateTarget(40.0, bank(41.30)), bank(41.30)))
        )
        // Crossed but not yet nine o'clock: the screen must not look broken for
        // showing a rate past a threshold the phone has said nothing about.
        assertEquals(
            "42,00 — уже досягнуто, скажу вранці",
            shown(rateTargetNote(armRateTarget(42.0, bank(41.30)), bank(42.10)))
        )
        assertEquals(
            "42,00 — досягнуто 15 вересня 2026",
            shown(rateTargetNote(disarmRateTarget(armRateTarget(42.0, bank(41.30))!!, today), bank(42.10)))
        )
    }

    // ------------------------------------------------------ inside the digest

    private val day = LocalDate.of(2026, 9, 15)

    /** A morning whose rate was Monobank's, fetched today, unless the caller says otherwise. */
    private fun morning(
        target: RateTarget?,
        rate: Double,
        rateDay: Long = today,
        source: String = SOURCE_MONOBANK
    ) = digest(
        wishes = emptyList(),
        pays = emptyList(),
        orders = emptyList(),
        today = day,
        usdSellRate = rate,
        income = 0.0,
        rateTarget = target,
        rateDay = rateDay,
        rateSource = source
    )

    @Test
    fun `a crossing is worth waking the digest for on its own`() {
        // Nothing else happened at all, so this proves the line is the news and
        // not a rider on somebody else's.
        val quiet = morning(armRateTarget(42.0, bank(41.30)), 41.80)
        assertTrue(quiet.empty)

        val crossed = morning(armRateTarget(42.0, bank(41.30)), 42.10)
        assertFalse(crossed.empty)
        assertEquals("Долар 42,10 ₴ — курс перетнув 42,00", shown(crossed.title))
    }

    @Test
    fun `a threshold already told is not repeated the next morning`() {
        val told = disarmRateTarget(armRateTarget(42.0, bank(41.30))!!, today)
        // The rate is still above 42 and will be for weeks. This is the difference
        // between a threshold and a daily bulletin about the dollar.
        assertTrue(morning(told, 42.10).empty)
        assertTrue(morning(told, 43.50).empty)
    }

    @Test
    fun `the crossing leads the message it shares`() {
        val pay = Pay("Оренда", 400.0, day = day.dayOfMonth, currency = USD, warnDays = 1)
        val full = digest(
            wishes = emptyList(),
            pays = listOf(pay),
            orders = emptyList(),
            today = day,
            usdSellRate = 42.10,
            income = 30000.0,
            rateTarget = armRateTarget(42.0, bank(41.30)),
            rateDay = today,
            rateSource = SOURCE_MONOBANK
        )

        assertFalse(full.empty)
        // The one line asked for by name sits above the lines the app chose.
        assertEquals("Долар 42,10 ₴ — курс перетнув 42,00", shown(full.lines.first()))
        assertTrue(full.lines.any { it.contains("Оренда") })
    }

    @Test
    fun `no threshold changes nothing about the digest`() {
        assertTrue(morning(null, 42.10).empty)
    }

    // ------------------------------------------------ a rate old enough to lie

    @Test
    fun `a stale rate says nothing rather than announcing last week's crossing`() {
        val target = armRateTarget(42.0, bank(41.30))

        // The digest needs no network and runs every morning regardless, so an
        // offline phone keeps converting at whatever it last fetched.
        assertTrue(morning(target, 42.10, rateDay = today - STALE_RATE_DAYS).empty)
        assertTrue(morning(target, 42.10, rateDay = today - 30).empty)
        // A rate never loaded at all is the same answer for the same reason.
        assertTrue(morning(target, 42.10, rateDay = 0L).empty)
    }

    @Test
    fun `a rate inside the window still speaks`() {
        val target = armRateTarget(42.0, bank(41.30))

        assertFalse(morning(target, 42.10, rateDay = today).empty)
        // The last day that still counts as current, so the boundary is pinned
        // rather than left to drift with the constant.
        assertFalse(morning(target, 42.10, rateDay = today - (STALE_RATE_DAYS - 1)).empty)
    }

    @Test
    fun `freshness here means what it means on the item page`() {
        // One rule, one constant. The alternative is the item page calling a rate
        // old while the digest quotes the same figure as this morning's.
        assertTrue(rateIsFresh(today, today))
        assertTrue(rateIsFresh(today - (STALE_RATE_DAYS - 1), today))
        assertFalse(rateIsFresh(today - STALE_RATE_DAYS, today))
        assertFalse(rateIsFresh(0L, today))

        val converted = toHryvnia(59.99, USD, FxRate(41.0, 42.0))
        // The same boundary, read off the other side of the app.
        assertNull(staleRateNote(converted, today - (STALE_RATE_DAYS - 1), today))
        assertNotNull(staleRateNote(converted, today - STALE_RATE_DAYS, today))
    }

    @Test
    fun `a threshold silenced by a stale rate is still armed, not spent`() {
        val target = armRateTarget(42.0, bank(41.30))!!

        // Nothing was said, so nothing may be marked as said: the disarm in the
        // worker hangs off the message carrying this exact line.
        assertNull(rateTargetLine(target, bank(42.10), today - 30, today))
        assertFalse(morning(target, 42.10, rateDay = today - 30).rateTargetSaid)
        // And the moment a real rate arrives, it speaks.
        assertEquals(
            "Долар 42,10 ₴ — курс перетнув 42,00",
            shown(rateTargetLine(target, bank(42.10), today, today)!!)
        )
    }

    // ------------------------------------- the bank's rate, never the official one
    //
    // When Monobank does not answer — and it refuses a second request inside a
    // minute — the phone holds the NBU's official rate instead. That figure usually
    // sits below the bank's sell rate, so it must never cross, arm or promise a
    // threshold that is watched on the bank's.

    @Test
    fun `the official rate never crosses a threshold`() {
        // Asked at the bank's 41,70 about a fall to 41,30. The official 41,25 is
        // under the number; the dollar at the bank has not moved.
        val fall = armRateTarget(41.30, bank(41.70))!!
        assertFalse(fall.above)

        assertNull(rateTargetLine(fall, official(41.25), today, today))
        // The bank's own figure under the number is the crossing.
        assertEquals(
            "Долар 41,28 ₴ — курс перетнув 41,30",
            shown(rateTargetLine(fall, bank(41.28), today, today)!!)
        )
    }

    @Test
    fun `a morning on the official rate says nothing and leaves the threshold armed`() {
        val fall = armRateTarget(41.30, bank(41.70))

        val onOfficial = morning(fall, 41.25, source = SOURCE_NBU)
        assertTrue(onOfficial.empty)
        // Not said, so the worker must not mark it as said: the real crossing
        // would then never be told.
        assertFalse(onOfficial.rateTargetSaid)

        val onBank = morning(fall, 41.28)
        assertEquals("Долар 41,28 ₴ — курс перетнув 41,30", shown(onBank.title))
        assertTrue(onBank.rateTargetSaid)
    }

    @Test
    fun `a digest not told where its rate came from leaves the threshold alone`() {
        // The safe way round for a default, as with the day: a caller that forgets
        // the source goes quiet rather than announcing on a figure it cannot vouch for.
        val summary = digest(
            wishes = emptyList(),
            pays = emptyList(),
            orders = emptyList(),
            today = day,
            usdSellRate = 42.10,
            income = 0.0,
            rateTarget = armRateTarget(42.0, bank(41.30)),
            rateDay = today
        )

        assertTrue(summary.empty)
        assertFalse(summary.rateTargetSaid)
    }

    @Test
    fun `a message is marked as carrying the threshold only when it does`() {
        // Beside other news, so the message is not empty either way and the flag
        // has to come from the line itself.
        val pay = Pay("Оренда", 400.0, day = day.dayOfMonth, currency = USD, warnDays = 1)
        fun withRent(rate: Double, source: String) = digest(
            wishes = emptyList(),
            pays = listOf(pay),
            orders = emptyList(),
            today = day,
            usdSellRate = rate,
            income = 30000.0,
            rateTarget = armRateTarget(42.0, bank(41.30)),
            rateDay = today,
            rateSource = source
        )

        val waiting = withRent(41.80, SOURCE_MONOBANK)
        assertFalse(waiting.empty)
        assertFalse(waiting.rateTargetSaid)

        // Past the number, but on the official figure: the rent is still news.
        val onOfficial = withRent(42.10, SOURCE_NBU)
        assertFalse(onOfficial.empty)
        assertFalse(onOfficial.rateTargetSaid)
        assertFalse((onOfficial.lines + onOfficial.title).any { it.contains("перетнув") })

        val crossed = withRent(42.10, SOURCE_MONOBANK)
        assertTrue(crossed.rateTargetSaid)
        assertEquals("Долар 42,10 ₴ — курс перетнув 42,00", shown(crossed.lines.first()))
    }

    @Test
    fun `a threshold cannot be set against the official rate`() {
        // Set while the official 41,25 was showing, 41,50 would read as a rise —
        // and the bank's 41,70 would announce it as crossed the first morning.
        assertNull(armRateTarget(41.50, official(41.25)))
        assertNotNull(armRateTarget(41.50, bank(41.70)))
    }

    @Test
    fun `the dialog says why the button will not press`() {
        assertEquals(
            "Спершу має завантажитись курс — без нього не видно, з якого боку чекати",
            rateTargetBlocked(FxRate())
        )
        assertEquals(
            "Поріг стежить за курсом Monobank, а зараз є лише курс НБУ — оновіть курс трохи пізніше",
            rateTargetBlocked(official(41.25))
        )
        assertNull(rateTargetBlocked(bank(41.70)))
        // The button and the arming agree: whatever the dialog explains away
        // cannot be armed, and whatever it lets through can.
        listOf(FxRate(), official(41.25), bank(41.70)).forEach { rate ->
            assertEquals(rateTargetBlocked(rate) == null, armRateTarget(41.50, rate) != null)
        }
    }

    @Test
    fun `under the official figure the screen promises nothing the morning cannot send`() {
        val fall = armRateTarget(41.30, bank(41.70))

        // The official 41,25 is past the number, and «уже досягнуто, скажу вранці»
        // would be a promise the morning message then breaks. The bank is named,
        // so the waiting line under a lower official figure does not look wrong.
        assertEquals(
            "Скажу, коли курс Monobank опуститься до 41,30",
            shown(rateTargetNote(fall, official(41.25)))
        )
        assertEquals(
            "Скажу, коли курс Monobank підніметься до 42,00",
            shown(rateTargetNote(armRateTarget(42.0, bank(41.30)), official(42.10)))
        )
        // On the bank's own figure the screen reads as it always did.
        assertEquals("Скажу, коли курс опуститься до 41,30", shown(rateTargetNote(fall, bank(41.70))))
        assertEquals("41,30 — уже досягнуто, скажу вранці", shown(rateTargetNote(fall, bank(41.28))))
    }
}
