package com.flowpay.app.screens

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import com.flowpay.app.DIGEST_ID
import com.flowpay.app.DigestAction
import com.flowpay.app.DigestCard
import com.flowpay.app.Pay
import com.flowpay.app.PaidMark
import com.flowpay.app.QuickMark
import com.flowpay.app.QuickMarks
import com.flowpay.app.Store
import com.flowpay.app.TouchPrefs
import com.flowpay.app.isPaid
import com.flowpay.app.postDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The morning message's buttons through Android's own machinery on the JVM: the
 * notification is posted, a button's PendingIntent is sent, the receiver declared
 * in the manifest takes it, the mark lands in the store and the message is drawn
 * again. Not a phone — no shade, no HyperOS — but every hand-off between them is
 * the real one.
 *
 * In the screens package so it runs only with `-Pshots`, like the pictures: CI
 * never starts Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickButtonsCheck {

    private val app = RuntimeEnvironment.getApplication()
    private val today = LocalDate.now()
    private val month = com.flowpay.app.monthKey(today)

    private fun shown(): Notification =
        shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(DIGEST_ID)

    /** Sends a button's PendingIntent and waits for the receiver's work to finish. */
    private fun press(notification: Notification, label: String) {
        val action = notification.actions.first { it.title.toString() == label }
        val before = QuickMarks.version
        action.actionIntent.send()
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (QuickMarks.version != before) break
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a button marks the payment, the message turns into undo, and undo takes it back`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = Store(app)
        store.savePays(listOf(Pay("Інтернет", 300.0, day = today.dayOfMonth), Pay("Оренда", 8_000.0, day = today.dayOfMonth)))
        store.savePaidMarks(listOf(PaidMark("Мобільний", month, 231.0)))
        val card = DigestCard(
            "Зведення за день",
            "Інтернет 300 ₴ — сьогодні\nОренда 8 000 ₴ — сьогодні\nВільно 12 000 ₴",
            listOf(QuickMark("Інтернет", month), QuickMark("Оренда", month)),
            // «Як скасувати» rides along and takes the third slot (QuickActions.kt).
            links = listOf(DigestAction("Як скасувати Netflix", "https://www.netflix.com/cancelplan"))
        )
        TouchPrefs(app).saveDigestCard(card)
        postDigest(app, card)

        val morning = shown()
        assertEquals(
            listOf("Сплачено · Інтернет", "Сплачено · Оренда", "Як скасувати Netflix"),
            morning.actions.map { it.title.toString() }
        )

        press(morning, "Сплачено · Інтернет")

        assertTrue(isPaid(store.paidMarks(), "Інтернет", month))
        // A mark made before is never touched by a button.
        assertTrue(isPaid(store.paidMarks(), "Мобільний", month))
        val marked = shown()
        assertEquals("Позначено: Інтернет", marked.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Скасувати", "Сплачено · Оренда", "Як скасувати Netflix"), marked.actions.map { it.title.toString() })

        press(marked, "Скасувати")

        assertTrue(!isPaid(store.paidMarks(), "Інтернет", month))
        assertTrue(isPaid(store.paidMarks(), "Мобільний", month))
        val back = shown()
        assertEquals("Зведення за день", back.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            listOf("Сплачено · Інтернет", "Сплачено · Оренда", "Як скасувати Netflix"),
            back.actions.map { it.title.toString() }
        )
    }
}
