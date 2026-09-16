package com.flowpay.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Writes the weekly copy into the folder the user picked.
 *
 * The judgements — when one is due, what to call it, which of the old ones to
 * delete — are in [Backup.kt] and tested there. What is left here is the part
 * that cannot be tested off a phone: a document tree, a cursor over its children,
 * and a stream.
 */
class BackupWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val folder = store.backupFolder()
        // A folder that was never chosen, or whose grant is gone, is not a failure
        // to retry: no amount of retrying grants a permission back, and only the
        // settings screen can ask for it again.
        if (backupState(folder, holdsBackupPermission(applicationContext, folder)) != BackupState.READY) {
            return Result.success()
        }
        val now = System.currentTimeMillis()
        if (!backupDue(store.lastBackupAt(), now)) return Result.success()
        // A worker's coroutine runs on the default dispatcher, which is sized for
        // computation; a document provider's stream is neither fast nor ours.
        val written = withContext(Dispatchers.IO) {
            writeBackup(applicationContext, store, LocalDate.now(), now)
        }
        // A card that is out, or a provider that is momentarily unavailable.
        return if (written) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(BACKUP_DAYS, TimeUnit.DAYS)
                // Nothing here leaves the phone, so no network is asked for: the
                // chosen folder may well be a card or the phone's own storage.
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("weekly-backup", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

/** The flags that make a folder grant outlive a reboot. */
const val BACKUP_FOLDER_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

/**
 * Whether the persistable grant taken on [folder] is still held.
 *
 * Asked before every write rather than assumed, because the grant survives a
 * reboot but not the folder being deleted or the provider's data being cleared,
 * and a job that fails on a missing permission fails silently for ever.
 */
fun holdsBackupPermission(context: Context, folder: String): Boolean =
    folder.isNotBlank() && context.contentResolver.persistedUriPermissions.any {
        it.uri.toString() == folder && it.isWritePermission
    }

/**
 * Takes the folder the user picked, keeping it across reboots.
 *
 * Returns false when the provider refuses to make the grant persistable, so the
 * settings screen can say that rather than storing a folder that will stop
 * working at the next restart.
 */
fun takeBackupFolder(context: Context, folder: Uri): Boolean = runCatching {
    context.contentResolver.takePersistableUriPermission(folder, BACKUP_FOLDER_FLAGS)
}.isSuccess

/**
 * Writes one dated copy and prunes the older ones. True when the copy landed.
 *
 * Blocking. Both callers move to [Dispatchers.IO] first.
 */
fun writeBackup(context: Context, store: Store, today: LocalDate, nowMillis: Long): Boolean =
    runCatching {
        val tree = store.backupFolder().toUri()
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        val name = backupFileName(today)
        // Running twice in a day replaces that day's copy. Letting the provider
        // name the second one "flowpay-2026-09-12 (1).json" would leave a file
        // the pruning does not recognise and therefore never removes.
        val (sameDay, older) = ourBackups(context, tree).partition { it.second == name }
        sameDay.forEach { DocumentsContract.deleteDocument(context.contentResolver, it.first) }

        val file = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            "application/json",
            name
        ) ?: error("тека не приймає файл")
        context.contentResolver.openOutputStream(file)?.bufferedWriter()?.use {
            it.write(store.exportJson())
        } ?: error("тека недоступна для запису")

        val stale = expiredBackups(older.map { it.second } + name).toSet()
        older.filter { it.second in stale }.forEach {
            DocumentsContract.deleteDocument(context.contentResolver, it.first)
        }
        store.saveLastBackupAt(nowMillis)
    }.isSuccess

/** Runs a copy now, off the main thread. */
suspend fun backupNow(context: Context, store: Store): Boolean = withContext(Dispatchers.IO) {
    writeBackup(context, store, LocalDate.now(), System.currentTimeMillis())
}

/**
 * What came of a spreadsheet export, so the screen can tell the two failures apart.
 *
 * A year that holds nothing yet and a folder that would not take the file are the
 * same thing from the outside — no file appeared — and they need opposite answers
 * from the user, so they are never reported as one.
 */
data class CsvExport(
    /** The file that was written, or null when none was. */
    val name: String?,
    /** Data rows in it, header excluded. Zero means the year is empty. */
    val rows: Int
)

/**
 * Writes the year's spending as a spreadsheet into the folder already granted.
 *
 * Deliberately the same document tree as the weekly copy rather than a second
 * picker. The user has already said where this app may write, and asking again for
 * a folder they have already chosen is how a person ends up with their data in two
 * places and a backup in neither.
 *
 * Blocking. The caller moves to [Dispatchers.IO] first.
 */
fun writeExpenseCsv(context: Context, store: Store, today: LocalDate): CsvExport {
    val pays = store.pays()
    val marks = store.paidMarks(today)
    val orders = store.orders()
    val rate = store.fxRate().first.sell
    val rows = expenseRows(pays, marks, orders, today.year, rate)
    // A file holding nothing but column names is worse than no file: it looks like
    // the export worked and the year was empty, and only one of those is true.
    if (rows.isEmpty()) return CsvExport(null, 0)

    val name = expenseCsvFileName(today.year)
    val written = runCatching {
        val tree = store.backupFolder().toUri()
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        // Replaced rather than added to. Left to name the second one itself, the
        // provider would write "flowpay-витрати-2026 (1).csv" and the folder would
        // hold two versions of one year with nothing saying which is current.
        findDocument(context, tree, name)?.let {
            DocumentsContract.deleteDocument(context.contentResolver, it)
        }
        val file = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            "text/csv",
            name
        ) ?: error("тека не приймає файл")
        context.contentResolver.openOutputStream(file)?.use { stream ->
            // Encoded explicitly. The byte order mark at the head of the text is
            // only worth writing if the bytes after it are actually UTF-8.
            stream.write(
                expenseCsv(pays, marks, orders, today.year, rate).toByteArray(Charsets.UTF_8)
            )
        } ?: error("тека недоступна для запису")
    }.isSuccess
    return CsvExport(name.takeIf { written }, rows.size)
}

/** Writes the spreadsheet now, off the main thread. */
suspend fun exportExpensesNow(
    context: Context,
    store: Store,
    today: LocalDate = LocalDate.now()
): CsvExport = withContext(Dispatchers.IO) { writeExpenseCsv(context, store, today) }

/** The document in [tree] with exactly this name, or null when the folder has none. */
private fun findDocument(context: Context, tree: Uri, name: String): Uri? {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(
        tree,
        DocumentsContract.getTreeDocumentId(tree)
    )
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME
    )
    return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getString(0) ?: continue
            if (cursor.getString(1) == name) {
                return@use DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
        }
        null
    }
}

/**
 * The copies already in the folder, as document uri and name.
 *
 * Only files this app named are returned. The folder belongs to the user and may
 * hold anything, and the pruning below it deletes — so it must never be looking at
 * a file it did not write.
 */
private fun ourBackups(context: Context, tree: Uri): List<Pair<Uri, String>> {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(
        tree,
        DocumentsContract.getTreeDocumentId(tree)
    )
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME
    )
    return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (isBackupFileName(name)) {
                    add(DocumentsContract.buildDocumentUriUsingTree(tree, id) to name)
                }
            }
        }
    }.orEmpty()
}
