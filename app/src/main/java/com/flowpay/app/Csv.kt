package com.flowpay.app

import java.time.LocalDate

/**
 * A year of spending as a table, for the one question the app could not answer.
 *
 * [Store.exportJson] writes everything the app needs to read itself back, which is
 * exactly the wrong shape for a person: it is nested, it is keyed "n" and "a" and
 * "cur", and no spreadsheet will total a column of it. The paid record, the
 * standing costs and the closed purchases are already on the phone; what was
 * missing was a way to put them in front of a person who wants to select a column
 * and look at the sum.
 *
 * The conventions below are not stylistic. This file is opened by Excel on a
 * Ukrainian Windows, and each of them is the difference between a table and one
 * mangled column:
 *
 *  - The separator is a semicolon. Excel splits a .csv on the list separator from
 *    Windows' regional settings, which on a Ukrainian install is ";". Handed a
 *    comma-separated file it puts every row in column A.
 *  - The file opens with a UTF-8 byte order mark. Without one Excel reads the
 *    bytes as the ANSI codepage and every Cyrillic name arrives as mojibake.
 *  - Numbers carry a decimal comma and no grouping. A comma is the decimal
 *    separator in this locale, so a comma-separated file could not use it at all;
 *    a semicolon-separated one can, and a full stop here would make Excel read
 *    "1200.50" as text and refuse to add it up. Grouping is left off because a
 *    thousands separator inside a number is the other common way a cell arrives
 *    as text.
 *  - Anything that could hold a separator, a quote or a line break is quoted, with
 *    doubled quotes inside, per RFC 4180. Shop titles are copied off web pages and
 *    routinely contain all three.
 *
 * One flat table rather than three stacked ones. Three header rows in a sheet look
 * tidier and stop a person sorting or filtering the thing, which is the only
 * reason to want a spreadsheet at all; the section column does the same job and
 * survives a sort.
 */

/** Excel's list separator on a Ukrainian Windows. */
const val CSV_SEPARATOR = ';'

/** Without this Excel reads the file as the ANSI codepage and Cyrillic is lost. */
const val CSV_BOM = "\uFEFF"

/** RFC 4180 says CRLF, and it is what Excel writes itself. */
private const val CSV_EOL = "\r\n"

private const val SECTION_PAID = "Сплачено"
private const val SECTION_SUBSCRIPTION = "Підписка"
private const val SECTION_PURCHASE = "Покупка"

/**
 * The column names, and the first row of the file.
 *
 * No title row above them. A sheet whose first row is a heading rather than the
 * column names is one where sorting moves the title into the data.
 */
val CSV_HEADER = listOf("Розділ", "Назва", "Коли", "Сума", "Валюта", "У гривнях", "Примітка")

/**
 * One cell, quoted only where it has to be.
 *
 * Quoting everything unconditionally would also work, and is what most exporters
 * do. It is avoided here because a quoted numeric cell is a cell Excel may decide
 * is text, and a column of text is a column that will not total.
 */
fun csvField(value: String): String =
    if (value.any { it == CSV_SEPARATOR || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

/** One line of the table. */
fun csvRow(fields: List<String>): String =
    fields.joinToString(CSV_SEPARATOR.toString()) { csvField(it) }

/**
 * An amount as a cell: decimal comma, no grouping.
 *
 * [figure] rather than [money], because a currency sign inside the number is the
 * third way a cell stops being a number. The currency has its own column.
 */
private fun csvAmount(value: Double): String = figure(value, 2)

/**
 * The same amount in hryvnia, or an empty cell when it cannot honestly be said.
 *
 * Dollars with no rate loaded leave the cell blank rather than writing a nought.
 * A blank is skipped by a column total and a nought is added to it, and a rent
 * silently totalling as zero is the worst answer this file could give.
 */
private fun csvInHryvnia(amount: Double, currency: String, usdSellRate: Double): String = when {
    currency != USD -> csvAmount(amount)
    usdSellRate > 0.0 -> csvAmount(amount * usdSellRate)
    else -> ""
}

/**
 * Every row of the table for [year], header excluded.
 *
 * Exposed apart from [expenseCsv] so the screen can tell an empty year from a
 * failed write: a January export of a year that holds nothing yet should say so
 * rather than leave a file with a header and no rows in the user's folder.
 */
fun expenseRows(
    pays: List<Pay>,
    marks: List<PaidMark>,
    orders: List<Order>,
    year: Int,
    usdSellRate: Double
): List<List<String>> = buildList {
    // What actually left the account, oldest first, which is the order a person
    // reads a year in.
    marks.filter { monthKeyDate(it.month)?.year == year }
        .sortedWith(compareBy({ it.month }, { it.name }))
        .forEach { mark ->
            add(
                listOf(
                    SECTION_PAID,
                    mark.name,
                    mark.month,
                    csvAmount(mark.amount),
                    mark.currency,
                    csvInHryvnia(mark.amount, mark.currency, usdSellRate),
                    ""
                )
            )
        }

    // The standing costs as they stand today, annualised. Deliberately not filtered
    // by year: nothing on the phone records what the list looked like in March, and
    // the note says what the yearly figure is twelve of so the reader can see that
    // this row is a projection rather than a record.
    pays.forEach { pay ->
        add(
            listOf(
                SECTION_SUBSCRIPTION,
                pay.name,
                "",
                csvAmount(yearlyCost(pay)),
                pay.currency,
                csvInHryvnia(yearlyCost(pay), pay.currency, usdSellRate),
                "${rhythmNote(pay)}, ${csvAmount(pay.amount)} за раз"
            )
        )
    }

    // Purchases that were closed and filed. An open order is not an expense yet.
    orders.filter { it.archivedDay > 0L && LocalDate.ofEpochDay(it.archivedDay).year == year }
        .sortedBy { it.archivedDay }
        .forEach { order ->
            // What was handed over, not what the shop listed. A promo code or a
            // different shop entirely is the difference, and the listed price goes
            // in the note rather than in the column that gets totalled.
            val spent = if (order.paid > 0.0) order.paid else order.price
            val note = if (order.paid > 0.0 && order.price > 0.0 && order.paid != order.price) {
                "у списку було ${csvAmount(order.price)}"
            } else {
                ""
            }
            add(
                listOf(
                    SECTION_PURCHASE,
                    order.name,
                    LocalDate.ofEpochDay(order.archivedDay).toString(),
                    csvAmount(spent),
                    UAH,
                    csvAmount(spent),
                    note
                )
            )
        }
}

/**
 * The whole file, byte order mark and all.
 *
 * No totals row. A spreadsheet adds its own the moment you select a column, and a
 * total sitting inside the data is a row that gets sorted into the middle of it
 * and then counted twice by the sum that replaces it.
 */
fun expenseCsv(
    pays: List<Pay>,
    marks: List<PaidMark>,
    orders: List<Order>,
    year: Int,
    usdSellRate: Double
): String {
    val rows = listOf(CSV_HEADER) + expenseRows(pays, marks, orders, year, usdSellRate)
    return CSV_BOM + rows.joinToString(CSV_EOL) { csvRow(it) } + CSV_EOL
}

/**
 * "flowpay-витрати-2026.csv".
 *
 * Named so that [isBackupFileName] can never match it. The weekly backup prunes
 * the folder by name and deletes what it matches, and a spreadsheet swept up by
 * that would be the export feature quietly eating its own output.
 */
fun expenseCsvFileName(year: Int): String = "flowpay-витрати-$year.csv"
