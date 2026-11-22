package com.flowpay.app

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * «Зарплата»: the log of what each month actually brought. The two things that
 * must not go wrong are a payment silently lost on the way through JSON (the
 * backup), and a month's total that is not the sum of its payments.
 */
class SalaryTest {

    private val nov = LocalDate.of(2026, 11, 22)

    private fun entry(month: String, kind: SalaryKind, amount: Double, day: Int, note: String = "") =
        SalaryEntry(id = "$month-$kind-$day", month = month, kind = kind, amount = amount,
            day = LocalDate.of(2026, 11, day).toEpochDay(), note = note)

    @Test
    fun `a fully populated payment survives the round trip whole`() {
        val original = SalaryEntry("id-1", "2026-11", SalaryKind.ADVANCE, 600.5, 20_400L, "премія", rate = 41.37)
        assertEquals(listOf(original), salaryOf(salaryJson(listOf(original))))
        SalaryKind.entries.forEach { kind ->
            val one = original.copy(kind = kind)
            assertEquals(listOf(one), salaryOf(salaryJson(listOf(one))))
        }
    }

    @Test
    fun `a damaged entry is dropped without taking the others with it`() {
        val good = SalaryEntry("ok", "2026-10", SalaryKind.SALARY, 20_000.0, 1L)
        val array = JSONArray(salaryJson(listOf(good)))
            .put(org.json.JSONObject().put("slm", "не місяць").put("sla", 100.0))
            .put(org.json.JSONObject().put("slm", "2026-10").put("sla", 0.0))
        assertEquals(listOf(good), salaryOf(array.toString()))
        assertTrue(salaryOf("це не json").isEmpty())
        assertTrue(salaryOf(null).isEmpty())
    }

    @Test
    fun `a payment typed in dollars counts in hryvnias at its own rate`() {
        val paid = SalaryEntry("a", "2026-11", SalaryKind.SALARY, 900.0, 1L, rate = 41.5)
        assertEquals(37_350.0, paid.uah, 0.001)
        // No rate: no hryvnia figure, rather than a made-up one.
        assertEquals(0.0, paid.copy(rate = 0.0).uah, 0.001)
    }

    @Test
    fun `a month leads with hryvnias, and falls back to dollars while a rate is missing`() {
        val known = salaryMonths(listOf(
            SalaryEntry("a", "2026-11", SalaryKind.ADVANCE, 300.0, 1L, rate = 40.0),
            SalaryEntry("s", "2026-11", SalaryKind.SALARY, 600.0, 2L, rate = 41.0)
        )).single()
        assertEquals(36_600.0, known.uah, 0.001)
        assertEquals(money(36_600.0), salaryMainFigure(known))
        assertEquals("прийшло ${dollars(900.0)}", salaryDollarLine(known))
        assertEquals("аванс ${money(12_000.0)} · зарплата ${money(24_600.0)}", salaryBreakdown(known))

        val partly = salaryMonths(listOf(
            SalaryEntry("a", "2026-11", SalaryKind.ADVANCE, 300.0, 1L, rate = 40.0),
            SalaryEntry("s", "2026-11", SalaryKind.SALARY, 600.0, 2L)
        )).single()
        assertEquals(dollars(900.0), salaryMainFigure(partly))
        assertEquals(null, salaryDollarLine(partly))
        assertEquals("аванс ${money(12_000.0)} · зарплата ${dollars(600.0)}", salaryBreakdown(partly))
    }

    @Test
    fun `a month adds up its advance and salary, newest month first`() {
        val log = listOf(
            entry("2026-10", SalaryKind.SALARY, 23_000.0, 3),
            entry("2026-11", SalaryKind.SALARY, 23_000.0, 25),
            entry("2026-11", SalaryKind.ADVANCE, 15_000.0, 10),
            entry("2026-10", SalaryKind.ADVANCE, 14_000.0, 1)
        )
        val months = salaryMonths(log)
        assertEquals(listOf("2026-11", "2026-10"), months.map { it.month })
        assertEquals(38_000.0, months[0].total, 0.001)
        assertEquals(37_000.0, months[1].total, 0.001)
        // Inside a month, in the order the money came.
        assertEquals(listOf(SalaryKind.ADVANCE, SalaryKind.SALARY), months[0].entries.map { it.kind })
    }

    @Test
    fun `the breakdown names each kind once, in a fixed order`() {
        // No rates here, so the breakdown is in dollars.
        val month = salaryMonths(listOf(
            entry("2026-11", SalaryKind.OTHER, 2_000.0, 5),
            entry("2026-11", SalaryKind.SALARY, 23_000.0, 25),
            entry("2026-11", SalaryKind.ADVANCE, 15_000.0, 10),
            entry("2026-11", SalaryKind.OTHER, 1_000.0, 12)
        )).single()
        assertEquals(
            "аванс ${dollars(15_000.0)} · зарплата ${dollars(23_000.0)} · інше ${dollars(3_000.0)}",
            salaryBreakdown(month)
        )
    }

    @Test
    fun `the year counts only its own months and averages over months with money`() {
        val log = listOf(
            entry("2026-11", SalaryKind.SALARY, 40_000.0, 25),
            entry("2026-10", SalaryKind.SALARY, 30_000.0, 25),
            entry("2025-12", SalaryKind.SALARY, 99_000.0, 25)
        )
        val year = salaryYear(log, 2026)
        assertEquals(70_000.0, year.total, 0.001)
        assertEquals(2, year.months)
        assertEquals(35_000.0, year.average, 0.001)
    }

    @Test
    fun `the month arrows cross the year`() {
        assertEquals("2026-12", shiftMonthKey("2026-11", 1))
        assertEquals("2027-01", shiftMonthKey("2026-12", 1))
        assertEquals("2025-12", shiftMonthKey("2026-01", -1))
    }

    @Test
    fun `the form opens on the advance, then the salary, then anything else`() {
        val withAdvance = Payday(salary = 25, advance = 10)
        assertEquals(SalaryKind.ADVANCE, suggestedKind(emptyList(), "2026-11", withAdvance))
        assertEquals(SalaryKind.SALARY, suggestedKind(emptyList(), "2026-11", Payday(salary = 25)))
        assertEquals(SalaryKind.SALARY, suggestedKind(emptyList(), "2026-11", Payday()))
        val advanceIn = listOf(entry("2026-11", SalaryKind.ADVANCE, 15_000.0, 10))
        assertEquals(SalaryKind.SALARY, suggestedKind(advanceIn, "2026-11", withAdvance))
        val bothIn = advanceIn + entry("2026-11", SalaryKind.SALARY, 23_000.0, 25)
        assertEquals(SalaryKind.OTHER, suggestedKind(bothIn, "2026-11", withAdvance))
        // Another month's payments say nothing about this one.
        assertEquals(SalaryKind.ADVANCE, suggestedKind(bothIn, "2026-12", withAdvance))
    }

    @Test
    fun `last time's amount is the newest of that kind`() {
        val log = listOf(
            entry("2026-10", SalaryKind.ADVANCE, 14_000.0, 1),
            entry("2026-11", SalaryKind.ADVANCE, 15_000.0, 10),
            entry("2026-11", SalaryKind.SALARY, 23_000.0, 25)
        )
        assertEquals(15_000.0, lastAmount(log, SalaryKind.ADVANCE)!!, 0.001)
        assertEquals(23_000.0, lastAmount(log, SalaryKind.SALARY)!!, 0.001)
        assertEquals(null, lastAmount(log, SalaryKind.OTHER))
    }

    @Test
    fun `a pasted history reads line by line, salary filed under the month it is for`() {
        val paste = parseSalaryLines(
            """
            16.09.2026 аванс 500$ курс 41,2
            03.10.2026 зарплата 520$ курс 41.3 за 09.2026

            16.10.2026 аванс 540 $ курс 41,4
            це не виплата
            02.11.2026 зарплата 1 234,50$ за 10.2026
            20.11.2026 премія 100$ курс 45
            """.trimIndent()
        )
        assertEquals(listOf("це не виплата"), paste.unreadable)
        assertEquals(5, paste.entries.size)
        val (sepAdvance, sepSalary, octAdvance, octSalary, bonus) = paste.entries
        assertEquals(SalaryKind.ADVANCE, sepAdvance.kind)
        assertEquals("2026-09", sepAdvance.month)
        assertEquals(500.0, sepAdvance.amount, 0.001)
        assertEquals(41.2, sepAdvance.rate, 0.0001)
        assertEquals(LocalDate.of(2026, 9, 16).toEpochDay(), sepAdvance.day)
        // Paid on 3 October, for September.
        assertEquals(SalaryKind.SALARY, sepSalary.kind)
        assertEquals("2026-09", sepSalary.month)
        assertEquals(41.3, sepSalary.rate, 0.0001)
        assertEquals(540.0, octAdvance.amount, 0.001)
        // No rate given: dollars only, no invented hryvnias.
        assertEquals("2026-10", octSalary.month)
        assertEquals(1_234.5, octSalary.amount, 0.001)
        assertEquals(0.0, octSalary.rate, 0.0)
        assertEquals(SalaryKind.OTHER, bonus.kind)
        assertEquals("премія", bonus.note)
    }

    @Test
    fun `pasting the same list twice adds nothing the second time`() {
        val list = parseSalaryLines("16.09.2026 аванс 500$ курс 41,2\n03.10.2026 зарплата 520$ за 09.2026").entries
        assertEquals(2, newPayments(emptyList(), list).size)
        assertEquals(0, newPayments(list, parseSalaryLines("16.09.2026 аванс 500$ курс 41,2").entries).size)
        // A repeated line inside one paste counts once.
        assertEquals(1, newPayments(emptyList(), list.take(1) + list.take(1).map { it.copy(id = "other") }).size)
    }

    @Test
    fun `the tile says this month, or that nothing is written yet`() {
        assertEquals(SalaryTileText("—", "за листопад ще не записано"), salaryTile(emptyList(), nov))
        val log = listOf(
            entry("2026-11", SalaryKind.ADVANCE, 15_000.0, 10),
            entry("2026-10", SalaryKind.SALARY, 23_000.0, 3)
        )
        assertEquals(SalaryTileText(dollars(15_000.0), "аванс ${dollars(15_000.0)}"), salaryTile(log, nov))
        val withRate = listOf(SalaryEntry("a", "2026-11", SalaryKind.ADVANCE, 300.0, 1L, rate = 41.0))
        assertEquals(
            SalaryTileText(money(12_300.0), "прийшло ${dollars(300.0)} · аванс ${money(12_300.0)}"),
            salaryTile(withRate, nov)
        )
    }
}
