package dev.kosha.feature.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kosha.core.database.settings.SettingsRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * One folder, chosen once, that every backup lands in.
 *
 * The old flow put a file-save dialog in front of the user on every backup,
 * which meant backups scattered across whatever directory the picker last
 * remembered and there was no way to see what you had. A backup you cannot
 * find is not a backup.
 *
 * The folder is a SAF tree the user grants persistably, so this needs no
 * storage permission, works on every supported Android version, and — unlike
 * app-private storage — survives uninstalling Kosha, which is precisely the
 * case a backup exists for. Inside it Kosha keeps its own [FOLDER_NAME]
 * subdirectory so the user's chosen folder does not fill up with our files.
 */
@Singleton
class BackupFolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    data class Entry(val uri: Uri, val name: String, val sizeBytes: Long, val modifiedAtMillis: Long)

    /** Take the grant the picker returned so it still works after a restart. */
    suspend fun remember(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        settingsRepository.setBackupFolderUri(treeUri.toString())
    }

    suspend fun chosenFolderName(): String? = withContext(Dispatchers.IO) {
        val tree = tree() ?: return@withContext null
        tree.name
    }

    /** True once a folder is picked AND still readable — a revoked grant is not a folder. */
    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) { tree()?.canWrite() == true }

    /**
     * Creates the destination for a new backup. Named by timestamp so backups
     * sort chronologically in any file manager and never overwrite each other:
     * a backup that silently replaced yesterday's would turn one bad restore
     * into two lost ones.
     */
    suspend fun newBackupFile(): Uri? = withContext(Dispatchers.IO) {
        val dir = koshaDir() ?: return@withContext null
        val stamp = FILE_STAMP.format(Instant.now().atZone(ZoneId.systemDefault()))
        dir.createFile(MIME_TYPE, "kosha-$stamp.${BackupManager.FILE_EXTENSION}")?.uri
    }

    /** Existing backups, newest first, so restore can be a list instead of a file hunt. */
    suspend fun list(): List<Entry> = withContext(Dispatchers.IO) {
        val dir = koshaDir() ?: return@withContext emptyList()
        dir.listFiles()
            .filter { it.isFile && it.name?.endsWith(".${BackupManager.FILE_EXTENSION}") == true }
            .map { Entry(it.uri, it.name.orEmpty(), it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedAtMillis }
    }

    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() == true }.getOrDefault(false)
    }

    private suspend fun tree(): DocumentFile? {
        val stored = settingsRepository.settings.first().backupFolderUri ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(stored)) }
            .getOrNull()
            ?.takeIf { it.exists() }
    }

    private suspend fun koshaDir(): DocumentFile? {
        val tree = tree() ?: return null
        // A folder the user picked may already be the one we made last time.
        if (tree.name == FOLDER_NAME) return tree
        return tree.findFile(FOLDER_NAME)?.takeIf { it.isDirectory }
            ?: tree.createDirectory(FOLDER_NAME)
    }

    companion object {
        const val FOLDER_NAME = "Kosha Backups"
        private const val MIME_TYPE = "application/octet-stream"
        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    }
}
