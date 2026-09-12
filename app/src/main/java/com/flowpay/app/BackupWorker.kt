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
