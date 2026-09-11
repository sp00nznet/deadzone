package net.sp00nz.deadzone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adopting a folder of audio you already have.
 *
 * Deadzone speaks no network filesystem at all, deliberately. Android's storage
 * access framework already exposes anything mounted as a pickable folder — an NFS or
 * SMB client, a USB-OTG drive, an SD card, the Downloads folder — so "mount the share
 * however you like, then point the app at it" costs nothing here and works for every
 * one of those, rather than for one protocol.
 */

data class ImportProgress(val done: Int, val total: Int, val current: String)

data class ImportResult(val found: Int, val matched: Int, val copied: Int, val failed: Int)

private val AUDIO = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav")

/**
 * Walk a picked folder, match the audio in it to episodes we know about, and copy
 * the matches in.
 *
 * Copying rather than playing in place is the point: a file left on the share is a
 * file you cannot hear in a tunnel, and being able to hear it in a tunnel is the
 * entire app.
 */
suspend fun importFolder(
    ctx: Context,
    store: Store,
    tree: Uri,
    onProgress: (ImportProgress) -> Unit = {},
): ImportResult = withContext(Dispatchers.IO) {
    // So the same folder can be re-imported later without picking it again.
    runCatching {
        ctx.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    val files = LinkedHashMap<String, Uri>()   // display name -> document uri
    walk(ctx, tree, DocumentsContract.getTreeDocumentId(tree), files) { n ->
        onProgress(ImportProgress(0, 0, "Scanning… $n files"))
    }
    if (files.isEmpty()) return@withContext ImportResult(0, 0, 0, 0)

    val matches = matchAll(files.keys.toList(), store.unmatchedEpisodes())

    var copied = 0
    var failed = 0
    for ((name, episodeId) in matches) {
        val src = files[name] ?: continue
        val episode = store.episode(episodeId) ?: continue
        onProgress(ImportProgress(copied + failed, matches.size, episode.title))
        val target = store.fileFor(episode)
        val part = File(target.path + ".part")
        try {
            ctx.contentResolver.openInputStream(src).use { input ->
                requireNotNull(input) { "could not open $name" }
                part.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
            // Same rule as a download: it does not exist under its real name until
            // it is whole, so an interrupted import cannot leave a half file that
            // looks playable.
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "could not finalise ${target.name}" }
            store.setFile(episodeId, target.path)
            copied++
        } catch (_: Exception) {
            part.delete()
            failed++
        }
    }
    ImportResult(files.size, matches.size, copied, failed)
}

/**
 * Recursive directory listing over the SAF.
 *
 * ponytail: DocumentsContract and one cursor per directory, not DocumentFile.
 * DocumentFile issues a separate content-provider query per file for its name and
 * type, which over 23k files on a network mount is thousands of round trips.
 */
private fun walk(
    ctx: Context,
    tree: Uri,
    docId: String,
    out: MutableMap<String, Uri>,
    onCount: (Int) -> Unit,
) {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
    val dirs = mutableListOf<String>()
    ctx.contentResolver.query(
        children,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null, null, null,
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0)
            val name = c.getString(1) ?: continue
            val mime = c.getString(2)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                dirs += id
            } else if (name.substringAfterLast('.', "").lowercase() in AUDIO) {
                // Two files with the same name in different folders: keep the first.
                // They are the same episode by any measure the matcher can see.
                out.putIfAbsent(name, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                if (out.size % 200 == 0) onCount(out.size)
            }
        }
    }
    // Recurse after closing the cursor — some providers hold a lock while one is open.
    for (d in dirs) walk(ctx, tree, d, out, onCount)
}
