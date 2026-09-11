package net.sp00nz.deadzone

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adopting audio you already have.
 *
 * Deadzone speaks no network filesystem at all, deliberately. Android's storage
 * access framework already exposes anything mounted as a pickable folder — an NFS or
 * SMB client, a USB-OTG drive, an SD card, Music, Downloads — so "point the app at a
 * folder" costs nothing here and works for every one of those rather than for one
 * protocol.
 */

data class ImportProgress(val done: Int, val total: Int, val current: String)

data class ImportResult(
    val found: Int,
    val matched: Int,   // recognised as an episode of a feed you subscribe to
    val adopted: Int,   // everything else, kept as its own local show
    val failed: Int,
)

private val AUDIO = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav")

/** One audio file found under the picked folder. */
private class LocalFile(
    val name: String,
    val uri: Uri,
    val folder: String,
    val modified: Long,
    val size: Long,
)

/**
 * Walk a picked folder and bring everything audio in it into the library.
 *
 * Files whose names match an episode of a feed you already subscribe to are attached
 * to that episode. Everything else is kept anyway, as a local show named after its
 * folder (or its album tag). Dropping the remainder is what made pointing this at a
 * plain Music folder look like it did nothing at all.
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

    val found = ArrayList<LocalFile>()
    val rootName = DocumentsContract.getTreeDocumentId(tree).substringAfterLast('/')
        .substringAfterLast(':').ifBlank { "Local files" }
    walk(ctx, tree, DocumentsContract.getTreeDocumentId(tree), rootName, found) { n ->
        onProgress(ImportProgress(0, 0, "Scanning… $n files"))
    }
    if (found.isEmpty()) return@withContext ImportResult(0, 0, 0, 0)

    // Audio already sitting on this phone's own storage is not copied — linking to it
    // costs nothing and copying a music folder would silently double its size on disk.
    // Anything reached through some other provider (a network share, a cloud mount)
    // *is* copied, because a file you cannot reach in a tunnel is no use here.
    val onThisDevice = tree.authority == "com.android.externalstorage.documents"

    val byName = found.associateBy { it.name }
    val matches = matchAll(found.map { it.name }, store.unmatchedEpisodes())

    var matched = 0
    var adopted = 0
    var failed = 0
    var done = 0
    val total = found.size

    // --- files we recognise as episodes of a subscribed feed ---
    for ((name, episodeId) in matches) {
        val src = byName[name] ?: continue
        val episode = store.episode(episodeId) ?: continue
        onProgress(ImportProgress(done++, total, episode.title))
        try {
            store.setFile(episodeId, bring(ctx, src.uri, store.fileFor(episode), onThisDevice))
            matched++
        } catch (_: Exception) {
            failed++
        }
    }

    // --- everything else, kept as a local show ---
    val leftovers = found.filter { it.name !in matches }
    val feedIds = HashMap<String, Long>()
    for (f in leftovers) {
        onProgress(ImportProgress(done++, total, f.name))
        try {
            val tags = tagsOf(ctx, f.uri)
            val show = tags.album?.takeIf { it.isNotBlank() } ?: f.folder
            val feedId = feedIds.getOrPut(show) { store.localFeed(show) }
            val title = tags.title?.takeIf { it.isNotBlank() } ?: f.name.substringBeforeLast('.')
            val target = File(store.localDir(show), safeName(f.name))
            val stored = bring(ctx, f.uri, target, onThisDevice)
            store.addLocalEpisode(
                feedId = feedId,
                file = stored,
                title = title,
                published = f.modified,
                duration = tags.durationSec,
                size = f.size,
            )
            adopted++
        } catch (_: Exception) {
            failed++
        }
    }

    ImportResult(found.size, matched, adopted, failed)
}

/**
 * Put the audio where the library can reach it, and return what to store in `file`.
 *
 * Linked files keep their content:// URI; copied ones land under our own directory
 * via a .part rename, so an interrupted import can never leave a truncated file that
 * looks complete.
 */
private fun bring(ctx: Context, src: Uri, target: File, linkInPlace: Boolean): String {
    if (linkInPlace) return src.toString()
    target.parentFile?.mkdirs()
    val part = File(target.path + ".part")
    try {
        ctx.contentResolver.openInputStream(src).use { input ->
            requireNotNull(input) { "could not open ${target.name}" }
            part.outputStream().use { input.copyTo(it, 64 * 1024) }
        }
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "could not finalise ${target.name}" }
        return target.path
    } catch (e: Exception) {
        part.delete()
        throw e
    }
}

private class Tags(val title: String?, val album: String?, val durationSec: Int)

/**
 * ponytail: tags are read only for files we could not match to a feed. The matched
 * ones already have a real title and duration from their publisher, and this costs a
 * media open per file — fine for a Music folder, slow across tens of thousands.
 */
private fun tagsOf(ctx: Context, uri: Uri): Tags {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(ctx, uri)
        Tags(
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            durationSec = ((mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) / 1000).toInt(),
        )
    } catch (_: Exception) {
        // An unreadable or tagless file is still worth keeping; it just gets its
        // name from the filename and its duration from the player on first play.
        Tags(null, null, 0)
    } finally {
        runCatching { mmr.release() }
    }
}

private fun safeName(s: String) =
    s.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "audio" }.take(80)

/**
 * Recursive directory listing over the SAF.
 *
 * ponytail: DocumentsContract and one cursor per directory, not DocumentFile.
 * DocumentFile issues a separate content-provider query per file for its name and
 * type, which over tens of thousands of files on a network mount is thousands of
 * round trips against tens.
 */
private fun walk(
    ctx: Context,
    tree: Uri,
    docId: String,
    folder: String,
    out: MutableList<LocalFile>,
    onCount: (Int) -> Unit,
) {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
    val dirs = mutableListOf<Pair<String, String>>()   // docId to folder name
    ctx.contentResolver.query(
        children,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        ),
        null, null, null,
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0)
            val name = c.getString(1) ?: continue
            val mime = c.getString(2)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                dirs += id to name
            } else if (name.substringAfterLast('.', "").lowercase() in AUDIO) {
                out += LocalFile(
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                    folder = folder,
                    modified = if (c.isNull(3)) 0L else c.getLong(3),
                    size = if (c.isNull(4)) 0L else c.getLong(4),
                )
                if (out.size % 200 == 0) onCount(out.size)
            }
        }
    }
    // Recurse after closing the cursor — some providers hold a lock while one is open.
    for ((id, name) in dirs) walk(ctx, tree, id, name, out, onCount)
}
