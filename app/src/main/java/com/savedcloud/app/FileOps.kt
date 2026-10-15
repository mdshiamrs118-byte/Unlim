package com.savedcloud.app

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.savedcloud.app.td.CloudFile
import com.savedcloud.app.td.TdRepository
import java.io.File
import java.util.UUID

object FileOps {

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace('/', '_').replace('\\', '_').trim()
        return if (cleaned.isEmpty()) "file" else cleaned
    }

    private fun displayName(ctx: Context, uri: Uri): String? {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i)
                }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment
    }

    private fun mimeOf(f: CloudFile): String {
        if (f.mime.isNotBlank()) return f.mime
        val ext = f.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Copies the picked files into app storage (TDLib needs real paths) and queues them for upload. */
    fun enqueueUploads(ctx: Context, uris: List<Uri>, folder: String) {
        val app = ctx.applicationContext
        TransferService.busy.addAndGet(uris.size)
        TransferService.start(app)
        Thread {
            for (uri in uris) {
                try {
                    val name = sanitize(displayName(app, uri) ?: "file")
                    val dir = File(app.filesDir, "td/outbox/${UUID.randomUUID()}").apply { mkdirs() }
                    val out = File(dir, name)
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Cannot read $name")
                    input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    TdRepository.upload(out.absolutePath, folder)
                } catch (e: Exception) {
                    TdRepository.reportError("Could not upload: ${e.message}")
                } finally {
                    TransferService.busy.decrementAndGet()
                }
            }
        }.start()
    }

    fun startDownload(ctx: Context, f: CloudFile) {
        TransferService.start(ctx.applicationContext)
        TdRepository.download(f.fileId)
    }

    private fun uriFor(ctx: Context, path: String): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(path))

    fun open(ctx: Context, f: CloudFile) {
        val path = f.localPath ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriFor(ctx, path), mimeOf(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(intent, f.name))
        } catch (e: ActivityNotFoundException) {
            toast(ctx, "No app can open this file")
        } catch (e: Exception) {
            toast(ctx, "Cannot open file: ${e.message}")
        }
    }

    fun share(ctx: Context, f: CloudFile) {
        val path = f.localPath ?: return
        try {
            val intent = Intent(Intent.ACTION_SEND)
                .setType(mimeOf(f))
                .putExtra(Intent.EXTRA_STREAM, uriFor(ctx, path))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(intent, f.name))
        } catch (e: Exception) {
            toast(ctx, "Cannot share file: ${e.message}")
        }
    }

    fun saveToDownloads(ctx: Context, f: CloudFile) {
        val path = f.localPath ?: return
        val app = ctx.applicationContext
        Thread {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sanitize(f.name))
                    put(MediaStore.Downloads.MIME_TYPE, mimeOf(f))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SavedCloud")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = app.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Cannot create file in Downloads")
                val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot write file")
                out.use { o -> File(path).inputStream().use { i -> i.copyTo(o) } }
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                toast(app, "Saved to Downloads/SavedCloud")
            } catch (e: Exception) {
                toast(app, "Save failed: ${e.message}")
            }
        }.start()
    }
}
