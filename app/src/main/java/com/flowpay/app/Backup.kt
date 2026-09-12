package com.flowpay.app

import java.time.LocalDate
import java.time.ZoneId

/**
 * The decisions a weekly backup makes, kept away from the file writing.
 *
 * `android:allowBackup="false"` is deliberate — the wishlist and the household
 * finances have no business on Google's servers — but the consequence was that a
 * lost phone lost everything, and the only defence was remembering to press
 * export. The realistic threat here is a phone left in a taxi, not an attacker:
 * a copy in a folder the user chose, written without being asked, is the answer
 * to that and the manual export is not.
 */

/** How many dated copies to keep in the folder. */
const val BACKUP_KEEP = 4

/** How often one is written. */
const val BACKUP_DAYS = 7L

private const val BACKUP_PREFIX = "flowpay-"
private const val BACKUP_SUFFIX = ".json"

/** "flowpay-2026-09-12.json" — dated, so the folder sorts itself by name. */
fun backupFileName(date: LocalDate): String =
    "%s%04d-%02d-%02d%s".format(BACKUP_PREFIX, date.year, date.monthValue, date.dayOfMonth, BACKUP_SUFFIX)

/**
 * Whether a file in the folder is one of ours.
 *
 * The folder is the user's — Downloads, a Drive mount, anywhere — so the pruning
 * has to be sure before it deletes. Only names this function wrote are candidates.
 */
fun isBackupFileName(name: String): Boolean =
    name.startsWith(BACKUP_PREFIX) &&
        name.endsWith(BACKUP_SUFFIX) &&
        name.removePrefix(BACKUP_PREFIX).removeSuffix(BACKUP_SUFFIX).let { stamp ->
            stamp.length == 10 && stamp[4] == '-' && stamp[7] == '-' &&
                stamp.filterIndexed { index, _ -> index != 4 && index != 7 }.all { it.isDigit() }
        }

/**
 * Which files to delete, keeping the [keep] newest.
 *
 * Names are dated and zero-padded, so sorting them as text sorts them as time and
 * no date needs parsing to decide what is oldest. Anything in the folder that is
 * not one of ours is never returned.
 */
fun expiredBackups(names: List<String>, keep: Int = BACKUP_KEEP): List<String> =
    names.filter { isBackupFileName(it) }
        .sortedDescending()
        .drop(keep.coerceAtLeast(1))

/** Whether a week has passed since the last copy. Never written counts as due. */
fun backupDue(lastMillis: Long, nowMillis: Long): Boolean {
    if (lastMillis <= 0L) return true
    // A clock moved backwards would otherwise park the next backup in the future.
    if (nowMillis < lastMillis) return true
    return nowMillis - lastMillis >= BACKUP_DAYS * 24 * 60 * 60 * 1000
}

/** Where the automatic backup stands. */
enum class BackupState {
    /** No folder has been chosen, so nothing is being written. */
    OFF,

    /**
     * A folder was chosen and the phone no longer holds permission on it.
     *
     * The persisted grant survives a reboot but not the folder being deleted, the
     * card being removed, or the provider's app being cleared — and when it goes,
     * the job fails silently every week. That is the worst of the three states and
     * the only one worth interrupting about.
     */
    FOLDER_LOST,

    READY
}

fun backupState(folderUri: String, permissionHeld: Boolean): BackupState = when {
    folderUri.isBlank() -> BackupState.OFF
    !permissionHeld -> BackupState.FOLDER_LOST
    else -> BackupState.READY
}

/** When the last copy was written, said plainly. */
fun lastBackupLabel(lastMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (lastMillis <= 0L) {
        "ще не створювалась"
    } else {
        val date = java.time.Instant.ofEpochMilli(lastMillis).atZone(zone).toLocalDate()
        "${formatDate(date)}, ${timeLabel(lastMillis)}"
    }

/**
 * The one line the settings row shows.
 *
 * A lost folder says what to do about it rather than what happened, because
 * "немає доступу" on its own leaves the copy that is not being written sounding
 * like someone else's problem.
 */
fun backupStatusLine(state: BackupState, lastMillis: Long): String = when (state) {
    BackupState.OFF -> "Виберіть теку — далі копія раз на тиждень сама"
    BackupState.FOLDER_LOST -> "Доступ до теки втрачено. Виберіть її ще раз"
    BackupState.READY -> if (lastMillis <= 0L) {
        "Раз на тиждень · копію ще не створено"
    } else {
        "Раз на тиждень · остання ${lastBackupLabel(lastMillis)}"
    }
}

/** How many copies the folder keeps, for the row under the status line. */
fun backupKeepLine(): String = "Зберігаються останні ${entriesLabel(BACKUP_KEEP)}, старіші видаляються"
