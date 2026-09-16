package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A payment day stored as a bare number is wrong four or five times a year.
 *
 * What is pinned here is mostly what happens when the calendar is missing or
 * wrong, because that is the normal case: the feed is fetched once a year, a
 * martial-law year moves official holidays by decree, and any public feed lags
 * that. The weekend rule has to stand entirely on its own, and the holidays have
 * to be an improvement on top of it that nothing depends on.
 */
class HolidaysTest {

    // International Women's Day 2026 falls on a Sunday, which is the case where a
    // naive implementation counts the day off twice.
    private val womensDay = LocalDate.of(2026, 3, 8)

    // Independence Day 2026 falls on a Monday: a day off inside the working week.
    private val independence = LocalDate.of(2026, 8, 24)

    private val calendar = setOf(womensDay.toEpochDay(), independence.toEpochDay())

    @Test
    fun `a weekend is not a day money moves, with or without the feed`() {
        assertFalse(isWorkingDay(LocalDate.of(2026, 9, 12), emptySet()))
        assertFalse(isWorkingDay(LocalDate.of(2026, 9, 13), emptySet()))
        assertTrue(isWorkingDay(LocalDate.of(2026, 9, 11), emptySet()))
        assertTrue(isWorkingDay(LocalDate.of(2026, 9, 14), emptySet()))
    }

    @Test
    fun `a holiday that lands on a Sunday is one day off, not two`() {
        val day = paymentDay(womensDay, calendar)

        // Friday the 6th: the Sunday and the Saturday before it, and nothing more.
        assertEquals(LocalDate.of(2026, 3, 6), day.payOn)
        assertTrue(day.moved)
        // Named as the holiday rather than as the Sunday: that a Sunday is a day
        // off is already obvious, and the holiday is the half a person may not know.
        assertEquals("святковий день", day.reason)
    }

    @Test
    fun `without the calendar the same Sunday still moves, just by its weekday`() {
        val day = paymentDay(womensDay, emptySet())

        assertEquals(LocalDate.of(2026, 3, 6), day.payOn)
        assertEquals("неділя", day.reason)
    }

    @Test
    fun `a holiday inside the working week moves the payment to the Friday before`() {
        val day = paymentDay(independence, calendar)

        assertEquals(LocalDate.of(2026, 8, 21), day.payOn)
        assertEquals("святковий день", day.reason)
    }

    @Test
    fun `a Saturday is named as a Saturday`() {
        assertEquals("субота", paymentDay(LocalDate.of(2026, 9, 12), emptySet()).reason)
    }

    @Test
    fun `an ordinary working day is left exactly alone`() {
        val day = paymentDay(LocalDate.of(2026, 9, 14), calendar)

        assertEquals(LocalDate.of(2026, 9, 14), day.payOn)
        assertFalse(day.moved)
        assertNull(day.reason)
        assertNull(paymentDayNote(day))
    }

    @Test
    fun `the note says the day the money has to be there by`() {
        assertEquals(
            "Святковий день — платіж до 6 березня",
            paymentDayNote(paymentDay(womensDay, calendar))
        )
    }

    @Test
    fun `a calendar claiming a whole month cannot send the search backwards forever`() {
        val nonsense = ((1..28).map { LocalDate.of(2026, 2, it) } +
            (1..31).map { LocalDate.of(2026, 3, it) }).map { it.toEpochDay() }.toSet()

        // Nothing usable was found inside the cap, so the original date comes back
        // rather than a date from some arbitrary distance in the past.
        assertEquals(womensDay, paymentDay(womensDay, nonsense).payOn)
    }

    @Test
    fun `the feed is read into epoch days`() {
        val feed = """
            [
              {"date":"2026-01-01","name":"New Year's Day","countryCode":"UA",
               "nationalHoliday":true,"subdivisionCodes":null,"holidayTypes":["Public"]},
              {"date":"2026-03-08","name":"International Women's Day","countryCode":"UA",
               "nationalHoliday":true,"subdivisionCodes":null,"holidayTypes":["Public"]}
            ]
        """.trimIndent()

        assertEquals(
            setOf(LocalDate.of(2026, 1, 1).toEpochDay(), womensDay.toEpochDay()),
            parseHolidays(feed)
        )
    }

    @Test
    fun `entries that are not national public holidays are ignored`() {
        val feed = """
            [
              {"date":"2026-05-01","nationalHoliday":true,"holidayTypes":["Public"]},
              {"date":"2026-05-02","nationalHoliday":false,"holidayTypes":["Public"]},
              {"date":"2026-05-03","nationalHoliday":true,"holidayTypes":["Observance"]}
            ]
        """.trimIndent()

        assertEquals(setOf(LocalDate.of(2026, 5, 1).toEpochDay()), parseHolidays(feed))
    }

    @Test
    fun `a feed that is not the feed is simply no holidays`() {
        // The fallback for "the fetch failed" and for "the answer changed shape"
        // has to be the same fallback, or a feed change becomes a crash.
        assertTrue(parseHolidays("").isEmpty())
        assertTrue(parseHolidays("<html>502 Bad Gateway</html>").isEmpty())
        assertTrue(parseHolidays("{}").isEmpty())
        assertTrue(parseHolidays("""[{"date":"not a date"}]""").isEmpty())
    }

    // ------------------------------------------------------ what it does to reminders

    @Test
    fun `a charge on a Sunday is reminded about before the weekend`() {
        val rent = listOf(Pay("Оренда", 8000.0, day = 13, warnDays = 3))

        // The 13th of September 2026 is a Sunday, so the money has to be there by
        // Friday the 11th — and the reminder counts to the Friday, not the Sunday.
        val due = remindersDue(rent, LocalDate.of(2026, 9, 9), emptySet()).single()

        assertEquals(2, due.daysAway)
        assertEquals(LocalDate.of(2026, 9, 13), due.movedFrom)
    }

    @Test
    fun `a charge on a working day carries no note about having moved`() {
        val internet = listOf(Pay("Інтернет", 300.0, day = 14, warnDays = 3))

        assertNull(remindersDue(internet, LocalDate.of(2026, 9, 12), emptySet()).single().movedFrom)
    }

    @Test
    fun `a working day already behind us reads as today rather than as a negative`() {
        val rent = listOf(Pay("Оренда", 8000.0, day = 13, warnDays = 1))

        // Saturday the 12th: the pay-by day was yesterday. There is no useful way
        // to say "через -1 день", and the honest advice is to do it now.
        assertEquals(0, remindersDue(rent, LocalDate.of(2026, 9, 12), emptySet()).single().daysAway)
    }
}
