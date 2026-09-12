package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The file is judged by one thing: whether it opens as a table in Excel on a
 * Ukrainian Windows, or as a single column of mojibake.
 *
 * Every assertion here is about a convention that decides that — the separator,
 * the byte order mark, the decimal comma, the quoting — rather than about wording.
 * The rows are read back through a reader written to RFC 4180 rather than by
 * splitting on the separator, because splitting is exactly the thing the quoting
 * exists to survive and a test that split would pass on a file no spreadsheet
 * could open. The last test here is about the backup pruner, which deletes files
 * by name in the same folder and must never recognise this one.
 */
class CsvTest {

    private val year = 2026
    private val rate = 42.0

    private fun mark(name: String, month: String, amount: Double, currency: String = UAH) =
        PaidMark(name, month, amount, currency)

    private fun order(name: String, day: LocalDate, price: Double, paid: Double = 0.0) = Order(
        id = name,
        name = name,
        url = "",
        status = "Отримано",
        price = price,
        paid = paid,
        archivedDay = day.toEpochDay()
    )

    private fun csv(
        pays: List<Pay> = emptyList(),
        marks: List<PaidMark> = emptyList(),
        orders: List<Order> = emptyList(),
        usdSellRate: Double = rate
    ) = expenseCsv(pays, marks, orders, year, usdSellRate)

    /**
     * The file as a spreadsheet would see it: byte order mark stripped, records
     * split on unquoted CRLF, cells unquoted and doubled quotes collapsed.
     */
    private fun parse(text: String): List<List<String>> {
        val body = text.removePrefix(CSV_BOM)
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < body.length) {
            val char = body[index]
            when {
                quoted && char == '"' && body.getOrNull(index + 1) == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                !quoted && char == CSV_SEPARATOR -> {
                    row.add(cell.toString())
                    cell.setLength(0)
                }
                !quoted && char == '\r' && body.getOrNull(index + 1) == '\n' -> {
                    row.add(cell.toString())
                    cell.setLength(0)
                    records.add(row)
                    row = mutableListOf()
                    index++
                }
                else -> cell.append(char)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            records.add(row)
        }
        return records
    }

    private fun rowsOf(text: String) = parse(text).drop(1)

    @Test
    fun `the file opens with a byte order mark or Excel loses every Cyrillic name`() {
        val text = csv(marks = listOf(mark("Інтернет", "2026-08", 250.0)))

        assertTrue(text.startsWith(CSV_BOM))
        // The mark belongs at the head of the file and nowhere else inside it.
        assertEquals(1, text.count { it == '\uFEFF' })
    }

    @Test
    fun `the separator is a semicolon and records end with CRLF`() {
        val text = csv(marks = listOf(mark("Інтернет", "2026-08", 250.0)))

        assertEquals(CSV_HEADER, parse(text).first())
        assertEquals("Розділ;Назва;Коли;Сума;Валюта;У гривнях;Примітка", text.removePrefix(CSV_BOM).substringBefore("\r\n"))
        // Every newline is part of a CRLF pair: a bare one would split a record
        // early in a reader that honours the standard.
        assertEquals(text.count { it == '\n' }, text.windowed(2).count { it == "\r\n" })
    }

    @Test
    fun `amounts carry a decimal comma and no grouping at all`() {
        val cells = rowsOf(csv(marks = listOf(mark("Оренда", "2026-08", 16800.5)))).single()

        assertEquals("16800,50", cells[3])
        // A full stop makes Excel read the cell as text, and so does a thousands
        // separator — an ordinary space or the non-breaking one alike.
        assertFalse(cells[3].contains("."))
        assertFalse(cells[3].contains(" "))
        assertFalse(cells[3].contains(" "))
    }

    @Test
    fun `a separator inside a shop title does not become a new column`() {
        val text = csv(orders = listOf(order("Навушники; чорні", LocalDate.of(2026, 7, 14), 2100.0)))
        val cells = rowsOf(text).single()

        // The name survives whole, and the row still has the columns it should.
        assertEquals("Навушники; чорні", cells[1])
        assertEquals(CSV_HEADER.size, cells.size)
        // Quoted in the bytes, which is what makes the above true.
        assertTrue(text.contains("\"Навушники; чорні\""))
    }

    @Test
    fun `a quote inside a name is doubled and a line break survives`() {
        assertEquals("\"Кабель 2\"\"\"", csvField("Кабель 2\""))
        assertEquals("\"два\nрядки\"", csvField("два\nрядки"))
        assertEquals("\"крапка; з комою\"", csvField("крапка; з комою"))
        // A comma needs no quoting in a semicolon-separated file, and quoting a
        // plain number is what turns a column Excel could total into text.
        assertEquals("2100,00", csvField("2100,00"))
        assertEquals("Навушники, чорні", csvField("Навушники, чорні"))
    }

    @Test
    fun `a name holding a quote reads back exactly as it was written`() {
        val cells = rowsOf(csv(orders = listOf(order("Кабель 2\" USB", LocalDate.of(2026, 5, 1), 300.0)))).single()

        assertEquals("Кабель 2\" USB", cells[1])
    }

    @Test
    fun `what was paid, the subscriptions and the closed purchases all land in one table`() {
        val rows = rowsOf(
            csv(
                pays = listOf(Pay("Netflix", 299.0, day = 15)),
                marks = listOf(mark("Інтернет", "2026-08", 250.0)),
                orders = listOf(order("Навушники", LocalDate.of(2026, 7, 14), 2100.0))
            )
        )

        assertEquals(3, rows.size)
        assertEquals(listOf("Сплачено", "Інтернет", "2026-08", "250,00", UAH, "250,00", ""), rows[0])
        // Twelve months of it, which is the figure that changes minds.
        assertEquals(
            listOf("Підписка", "Netflix", "", "3588,00", UAH, "3588,00", "щомісяця 299,00, 15 числа"),
            rows[1]
        )
        // An ISO date, which sorts as text in the order it sorts as time.
        assertEquals(
            listOf("Покупка", "Навушники", "2026-07-14", "2100,00", UAH, "2100,00", ""),
            rows[2]
        )
    }

    @Test
    fun `dollars are converted in their own column and left blank without a rate`() {
        val converted = rowsOf(csv(marks = listOf(mark("Оренда", "2026-08", 400.0, USD)))).single()
        assertEquals("400,00", converted[3])
        assertEquals(USD, converted[4])
        assertEquals("16800,00", converted[5])

        // No rate loaded. A nought here would be added to a column total and the
        // rent would silently leave the year at nothing.
        val unconverted =
            rowsOf(csv(marks = listOf(mark("Оренда", "2026-08", 400.0, USD)), usdSellRate = 0.0)).single()
        assertEquals("400,00", unconverted[3])
        assertEquals("", unconverted[5])
    }

    @Test
    fun `the column that gets totalled holds what was handed over, not what was listed`() {
        val cells =
            rowsOf(csv(orders = listOf(order("Навушники", LocalDate.of(2026, 7, 14), 2400.0, paid = 2100.0)))).single()

        assertEquals("2100,00", cells[3])
        // The shop's price is worth keeping, but not in a column someone sums.
        assertEquals("у списку було 2400,00", cells[6])
    }

    @Test
    fun `only the year asked for is in the file`() {
        val rows = expenseRows(
            pays = emptyList(),
            marks = listOf(mark("Інтернет", "2025-12", 250.0), mark("Інтернет", "2026-01", 250.0)),
            orders = listOf(
                order("Торішнє", LocalDate.of(2025, 11, 3), 500.0),
                order("Цьогорічне", LocalDate.of(2026, 3, 9), 700.0)
            ),
            year = year,
            usdSellRate = rate
        )

        assertEquals(2, rows.size)
        assertTrue(rows.any { it[2] == "2026-01" })
        assertTrue(rows.any { it[1] == "Цьогорічне" })
    }

    @Test
    fun `an order still in flight is not an expense yet`() {
        val open = Order(id = "1", name = "В дорозі", url = "", status = AT_BRANCH, price = 900.0)

        assertTrue(expenseRows(emptyList(), emptyList(), listOf(open), year, rate).isEmpty())
    }

    @Test
    fun `an empty year produces no rows, so the screen can say so instead of writing a file`() {
        assertTrue(expenseRows(emptyList(), emptyList(), emptyList(), year, rate).isEmpty())
        // The header alone is still a well-formed file; it is simply never written.
        assertEquals(1, parse(csv()).size)
    }

    @Test
    fun `every row has exactly as many cells as the header has names`() {
        val rows = expenseRows(
            pays = listOf(Pay("Netflix", 299.0, day = 15), Pay("Оренда", 400.0, day = 1, currency = USD)),
            marks = listOf(mark("Інтернет", "2026-08", 250.0), mark("Оренда", "2026-08", 400.0, USD)),
            orders = listOf(order("Навушники", LocalDate.of(2026, 7, 14), 2100.0)),
            year = year,
            usdSellRate = rate
        )

        assertEquals(5, rows.size)
        rows.forEach { assertEquals(CSV_HEADER.size, it.size) }
    }

    @Test
    fun `the spreadsheet is never mistaken for a backup and pruned away`() {
        val name = expenseCsvFileName(year)

        assertEquals("flowpay-витрати-2026.csv", name)
        // The weekly backup deletes what it matches in this very folder.
        assertFalse(isBackupFileName(name))
        assertTrue(expiredBackups(List(10) { name } + "flowpay-2026-09-12.json").isEmpty())
    }

    @Test
    fun `the file reads the same whatever language the phone is set to`() {
        // CI runs as en-US and the phone is uk-UA. A figure formatted in the
        // platform default would differ between the two, and the file would then
        // open correctly on exactly one of them.
        val cells = rowsOf(csv(marks = listOf(mark("Оренда", "2026-08", 1234.5)))).single()

        assertEquals("1234,50", cells[3])
    }
}
