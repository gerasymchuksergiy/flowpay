package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The automatic backup writes into a folder that belongs to the user, on a
 * schedule nobody watches. Two failures are silent and therefore expensive: a
 * permission that has quietly gone, leaving a job that fails every week while the
 * screen says everything is fine; and pruning that matches a file the app did not
 * write, which turns a backup feature into a delete feature.
 */
class BackupTest {

    private val day = LocalDate.of(2026, 9, 12)
    private val hour = 60L * 60 * 1000
    private val week = 7 * 24 * hour

    @Test
    fun `a copy is named for its day and sorts by name`() {
        assertEquals("flowpay-2026-09-12.json", backupFileName(day))
        // Zero padding, so the folder's alphabetical order is its chronological one.
        assertTrue(backupFileName(LocalDate.of(2026, 10, 1)) > backupFileName(day))
        assertTrue(backupFileName(day) > backupFileName(LocalDate.of(2026, 9, 9)))
    }

    @Test
    fun `only files this app named are ever candidates for deletion`() {
        assertTrue(isBackupFileName("flowpay-2026-09-12.json"))

        // The folder is the user's and may hold anything at all.
        assertFalse(isBackupFileName("flowpay-backup.json"))
        assertFalse(isBackupFileName("таблиця.json"))
        assertFalse(isBackupFileName("flowpay-2026-09-12.json.txt"))
        assertFalse(isBackupFileName("flowpay-2026-09-12 (1).json"))
        assertFalse(isBackupFileName("flowpay-xxxx-xx-xx.json"))
        assertFalse(isBackupFileName("my-flowpay-2026-09-12.json"))
    }

    @Test
    fun `the newest few are kept and the rest are listed for deletion`() {
        val names = listOf(
            "flowpay-2026-09-12.json",
            "flowpay-2026-09-05.json",
            "flowpay-2026-08-29.json",
            "flowpay-2026-08-22.json",
            "flowpay-2026-08-15.json",
            "flowpay-2026-08-08.json"
        )

        assertEquals(
            listOf("flowpay-2026-08-15.json", "flowpay-2026-08-08.json"),
            expiredBackups(names)
        )
        assertEquals(BACKUP_KEEP, names.size - expiredBackups(names).size)
    }

    @Test
    fun `nothing in the folder that is not ours is ever returned for deletion`() {
        val names = listOf(
            "flowpay-2026-09-12.json",
            "податкова.pdf",
            "flowpay-backup.json",
            "flowpay-2026-01-01.json",
            "flowpay-2025-01-01.json",
            "flowpay-2024-01-01.json",
            "flowpay-2023-01-01.json"
        )

        // Five of these are ours, so exactly one is over the limit — and the two
        // files that merely look related are left alone however old they are.
        val expired = expiredBackups(names)
        assertTrue(expired.all { isBackupFileName(it) })
        assertEquals(listOf("flowpay-2023-01-01.json"), expired)
    }

    @Test
    fun `fewer copies than the limit means nothing to delete`() {
        assertTrue(expiredBackups(listOf("flowpay-2026-09-12.json")).isEmpty())
        assertTrue(expiredBackups(emptyList()).isEmpty())
    }

    @Test
    fun `a copy is due after a week, and never before`() {
        val now = 1_800_000_000_000L

        assertTrue(backupDue(lastMillis = 0L, nowMillis = now))
        assertFalse(backupDue(now - week + hour, now))
        assertTrue(backupDue(now - week, now))
        assertTrue(backupDue(now - 2 * week, now))
    }

    @Test
    fun `a clock that went backwards does not park the next copy in the future`() {
        val now = 1_800_000_000_000L

        assertTrue(backupDue(lastMillis = now + week, nowMillis = now))
    }

    @Test
    fun `a folder whose permission was revoked is called out, not reported as fine`() {
        val folder = "content://com.android.externalstorage.documents/tree/primary%3ABackups"

        // The grant survives a reboot but not the folder being deleted or the
        // provider's data being cleared. Without this state the weekly job fails
        // silently for ever while the row still reads "остання 5 вересня".
        assertEquals(BackupState.FOLDER_LOST, backupState(folder, permissionHeld = false))
        assertEquals(
            "Доступ до теки втрачено. Виберіть її ще раз",
            backupStatusLine(BackupState.FOLDER_LOST, lastMillis = 1_800_000_000_000L)
        )
        assertEquals(BackupState.READY, backupState(folder, permissionHeld = true))
    }

    @Test
    fun `no folder chosen is off rather than broken`() {
        assertEquals(BackupState.OFF, backupState("", permissionHeld = false))
        // A blank folder cannot be permitted, but the answer must not depend on it.
        assertEquals(BackupState.OFF, backupState("", permissionHeld = true))
        assertEquals(
            "Виберіть теку — далі копія раз на тиждень сама",
            backupStatusLine(BackupState.OFF, lastMillis = 0L)
        )
    }

    @Test
    fun `a folder that has never been written to says so`() {
        assertEquals("ще не створювалась", lastBackupLabel(0L))
        assertEquals("Раз на тиждень · копію ще не створено", backupStatusLine(BackupState.READY, 0L))
    }

    @Test
    fun `a written copy is dated in readable ukrainian`() {
        val zone = java.time.ZoneId.of("Europe/Kyiv")
        val at = day.atTime(9, 5).atZone(zone).toInstant().toEpochMilli()

        assertTrue(lastBackupLabel(at, zone).startsWith("12 вересня 2026"))
    }

    @Test
    fun `the row says how many copies the folder keeps`() {
        assertEquals("Зберігаються останні 4 записи, старіші видаляються", backupKeepLine())
    }
}
