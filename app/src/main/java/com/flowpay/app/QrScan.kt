package com.flowpay.app

import android.content.Context
import android.content.Intent
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * «Сканувати QR»: a link on a computer screen, a poster or a parcel's sticker,
 * read with the phone and handed to FlowPay as if it had been shared.
 *
 * The scanner is Google Play services' own (the ML Kit code scanner). It brings
 * its own camera screen, so FlowPay asks for no camera permission and only ever
 * sees the text that was read. Asked for on 5 October 2026 («треба qr код сканер
 * зробити» — «посилання»): the owner works at a PC and picking the phone up to
 * retype a link was the annoyance.
 */
fun scanCode(context: Context, onText: (String) -> Unit, onProblem: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        // A QR for links; Code 128 and Data Matrix because a parcel's sticker
        // carries its waybill that way, and the router knows a waybill.
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX)
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { code ->
            scannedText(code.rawValue, code.displayValue)?.let(onText)
                ?: onProblem("Код прочитано, але в ньому нічого немає")
        }
        .addOnFailureListener { problem -> onProblem(scanProblem(problem)) }
}

/** What a scanned code says, trimmed; null when there is nothing in it. */
fun scannedText(raw: String?, display: String?): String? =
    (raw?.takeIf { it.isNotBlank() } ?: display)?.trim()?.takeIf { it.isNotEmpty() }

/** A failure in words: the module still downloading is the one worth naming. */
private fun scanProblem(problem: Exception): String = when {
    problem is MlKitException && problem.errorCode == MlKitException.UNAVAILABLE ->
        "Сканер ще завантажується в сервісах Google — спробуйте за хвилину"
    problem is MlKitException && problem.errorCode == CommonStatusCodes.CANCELED -> "Сканування скасовано"
    else -> "Не вдалося відкрити сканер. Потрібні сервіси Google Play"
}

/**
 * Hands what was read to the app exactly as «Поділитися» would: an explicit
 * ACTION_SEND to MainActivity, so the same router decides — a shop's link becomes
 * a wish, a waybill a parcel, a letter about a subscription a payment.
 */
fun shareIntoApp(context: Context, text: String) {
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )
}
