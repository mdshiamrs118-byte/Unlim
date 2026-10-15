package com.savedcloud.app.td

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class FileKind { DOCUMENT, PHOTO, VIDEO, AUDIO }

/** One file stored in the user's Telegram "Saved Messages". */
data class CloudFile(
    val messageId: Long,
    val fileId: Int,
    val name: String,
    val size: Long,
    val date: Int,
    val folder: String,
    val kind: FileKind,
    val mime: String,
    val localPath: String?,
    val downloading: Boolean,
    val uploading: Boolean,
    val failed: Boolean,
    val progress: Float
)

sealed class AuthUi {
    object NeedCredentials : AuthUi()
    object Starting : AuthUi()
    object WaitPhone : AuthUi()
    data class WaitCode(val phone: String) : AuthUi()
    data class WaitPassword(val hint: String) : AuthUi()
    object Ready : AuthUi()
    data class Unsupported(val reason: String) : AuthUi()
    object LoggedOut : AuthUi()
}

data class UiSnapshot(
    val auth: AuthUi,
    val files: List<CloudFile>,
    val loading: Boolean,
    val error: String?
)

data class TdParams(
    val rootDir: String,
    val apiId: Int,
    val apiHash: String,
    val language: String,
    val deviceModel: String,
    val systemVersion: String,
    val appVersion: String
)

/**
 * Talks to Telegram through TDLib and treats the user's own "Saved Messages" chat as cloud storage.
 * Every file is a document message. Folders are stored as a caption: "📁 <folder name>".
 */
object TdRepository {

    private const val FOLDER_PREFIX = "📁 "

    private data class Msg(
        val id: Long,
        val fileId: Int,
        val name: String,
        val size: Long,
        val date: Int,
        val folder: String,
        val kind: FileKind,
        val mime: String,
        val pending: Boolean,
        val failed: Boolean
    )

    private val lock = Any()
    private val messages = LinkedHashMap<Long, Msg>()
    private val fileStates = HashMap<Int, TdApi.File>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "savedcloud-notify").apply { isDaemon = true }
    }
    private val notifyPending = AtomicBoolean(false)

    @Volatile private var client: Client? = null
    @Volatile private var params: TdParams? = null
    @Volatile private var chatId: Long = 0L
    @Volatile private var auth: AuthUi = AuthUi.NeedCredentials
    @Volatile private var loading: Boolean = false
    @Volatile private var error: String? = null

    // ---------------------------------------------------------------- public API

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun snapshot(): UiSnapshot = synchronized(lock) {
        val list = messages.values
            .sortedWith(compareByDescending<Msg> { it.date }.thenByDescending { it.id })
            .map { toCloudFile(it) }
        UiSnapshot(auth, list, loading, error)
    }

    fun clearError() { error = null; changed() }

    fun reportError(message: String) { postError(message) }

    fun activeTransfers(): Int = synchronized(lock) {
        var n = 0
        for (m in messages.values) {
            if (m.pending && !m.failed) { n++; continue }
            val f = fileStates[m.fileId]
            if (f != null && f.local != null && f.local.isDownloadingActive) n++
        }
        n
    }

    /** Starts TDLib. Safe to call more than once. */
    @Synchronized
    fun start(p: TdParams) {
        params = p
        if (client != null) return
        auth = AuthUi.Starting
        synchronized(lock) {
            messages.clear()
            fileStates.clear()
        }
        chatId = 0L
        error = null
        loading = false
        File(p.rootDir).mkdirs()
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        client = Client.create(
            Client.ResultHandler { onUpdate(it) },
            Client.ExceptionHandler { fail(it) },
            Client.ExceptionHandler { fail(it) }
        )
        changed()
    }

    /** Re-creates the client after logging out. */
    fun restart() {
        val p = params ?: return
        start(p)
    }

    fun submitPhone(phone: String) {
        error = null
        send(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null))
    }

    fun submitCode(code: String) {
        error = null
        send(TdApi.CheckAuthenticationCode(code.trim()))
    }

    fun submitPassword(password: String) {
        error = null
        send(TdApi.CheckAuthenticationPassword(password))
    }

    fun logout() {
        send(TdApi.LogOut())
    }

    fun refresh() {
        if (chatId == 0L) return
        synchronized(lock) { messages.entries.removeAll { !it.value.pending } }
        loadHistory(0L)
    }

    fun upload(path: String, folder: String) {
        val chat = chatId
        if (chat == 0L) { postError("Not connected to Telegram yet"); return }
        val caption = TdApi.FormattedText(
            if (folder.isBlank()) "" else FOLDER_PREFIX + folder.trim(),
            arrayOf<TdApi.TextEntity>()
        )
        val content = TdApi.InputMessageDocument(TdApi.InputFileLocal(path), null, true, caption)
        send(TdApi.SendMessage(chat, null, null, null, null, content)) { r ->
            when (r) {
                is TdApi.Message -> {
                    synchronized(lock) {
                        parse(r)?.let { messages[it.id] = it }
                    }
                    changed()
                }
                is TdApi.Error -> postError(r.message)
            }
        }
    }

    fun download(fileId: Int) {
        send(TdApi.DownloadFile(fileId, 32, 0L, 0L, false)) { r ->
            if (r is TdApi.File) {
                synchronized(lock) { fileStates[r.id] = r }
                changed()
            } else if (r is TdApi.Error) {
                postError(r.message)
            }
        }
    }

    fun cancelDownload(fileId: Int) {
        send(TdApi.CancelDownloadFile(fileId, false))
    }

    fun delete(messageId: Long) {
        val chat = chatId
        if (chat == 0L) return
        synchronized(lock) { messages.remove(messageId) }
        changed()
        send(TdApi.DeleteMessages(chat, longArrayOf(messageId), true)) { r ->
            if (r is TdApi.Error) postError(r.message)
        }
    }

    fun moveToFolder(messageId: Long, folder: String) {
        val chat = chatId
        if (chat == 0L) return
        val text = if (folder.isBlank()) "" else FOLDER_PREFIX + folder.trim()
        val caption = TdApi.FormattedText(text, arrayOf<TdApi.TextEntity>())
        send(TdApi.EditMessageCaption(chat, messageId, null, caption, false)) { r ->
            when (r) {
                is TdApi.Message -> {
                    synchronized(lock) {
                        val old = messages[messageId]
                        val updated = parse(r)
                        if (old != null && updated != null) messages[messageId] = updated
                    }
                    changed()
                }
                is TdApi.Error -> postError(r.message)
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private fun send(query: TdApi.Function<*>, handler: ((TdApi.Object) -> Unit)? = null) {
        val c = client ?: return
        if (handler == null) {
            c.send(query, Client.ResultHandler { r ->
                if (r is TdApi.Error) postError(r.message)
            })
        } else {
            c.send(query, Client.ResultHandler { r -> handler(r) })
        }
    }

    private fun fail(t: Throwable) {
        postError(t.message ?: t.javaClass.simpleName)
    }

    private fun postError(message: String?) {
        error = message ?: "Unknown error"
        changed()
    }

    private fun changed() {
        if (notifyPending.compareAndSet(false, true)) {
            scheduler.schedule({
                notifyPending.set(false)
                for (l in listeners) {
                    try { l() } catch (_: Throwable) { }
                }
            }, 120, TimeUnit.MILLISECONDS)
        }
    }

    private fun setAuth(a: AuthUi) {
        auth = a
        changed()
    }

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> onAuth(obj.authorizationState)

            is TdApi.UpdateFile -> {
                synchronized(lock) { fileStates[obj.file.id] = obj.file }
                changed()
            }

            is TdApi.UpdateNewMessage -> {
                val m = obj.message
                if (m.chatId == chatId && chatId != 0L) {
                    synchronized(lock) { parse(m)?.let { messages[it.id] = it } }
                    changed()
                }
            }

            is TdApi.UpdateMessageSendSucceeded -> {
                val m = obj.message
                if (m.chatId == chatId && chatId != 0L) {
                    synchronized(lock) {
                        messages.remove(obj.oldMessageId)
                        parse(m)?.let { messages[it.id] = it }
                    }
                    cleanOutbox(m)
                    changed()
                }
            }

            is TdApi.UpdateMessageSendFailed -> {
                val m = obj.message
                if (m.chatId == chatId && chatId != 0L) {
                    synchronized(lock) {
                        val old = messages[obj.oldMessageId]
                        if (old != null) messages[obj.oldMessageId] = old.copy(failed = true, pending = false)
                    }
                    postError(obj.error.message)
                }
            }

            is TdApi.UpdateDeleteMessages -> {
                if (obj.chatId == chatId && obj.isPermanent) {
                    synchronized(lock) { for (id in obj.messageIds) messages.remove(id) }
                    changed()
                }
            }

            else -> Unit
        }
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val p = params ?: return
                val dbDir = File(p.rootDir, "db").apply { mkdirs() }.absolutePath
                val filesDir = File(p.rootDir, "files").apply { mkdirs() }.absolutePath
                send(
                    TdApi.SetTdlibParameters(
                        false, dbDir, filesDir, ByteArray(0),
                        true, true, true, false,
                        p.apiId, p.apiHash,
                        p.language, p.deviceModel, p.systemVersion, p.appVersion
                    )
                )
            }

            is TdApi.AuthorizationStateWaitPhoneNumber -> setAuth(AuthUi.WaitPhone)

            is TdApi.AuthorizationStateWaitCode ->
                setAuth(AuthUi.WaitCode(s.codeInfo?.phoneNumber ?: ""))

            is TdApi.AuthorizationStateWaitPassword ->
                setAuth(AuthUi.WaitPassword(s.passwordHint ?: ""))

            is TdApi.AuthorizationStateWaitRegistration ->
                setAuth(AuthUi.Unsupported("This phone number has no Telegram account yet. Sign up in the official Telegram app first, then log in here."))

            is TdApi.AuthorizationStateWaitEmailAddress, is TdApi.AuthorizationStateWaitEmailCode ->
                setAuth(AuthUi.Unsupported("Telegram asked for e-mail verification, which this app does not support. Log in once in the official Telegram app, then try again."))

            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation, is TdApi.AuthorizationStateWaitPremiumPurchase ->
                setAuth(AuthUi.Unsupported("This login method is not supported. Use the phone number and code method."))

            is TdApi.AuthorizationStateReady -> {
                setAuth(AuthUi.Ready)
                onReady()
            }

            is TdApi.AuthorizationStateClosed -> {
                client = null
                chatId = 0L
                synchronized(lock) {
                    messages.clear()
                    fileStates.clear()
                }
                loading = false
                setAuth(AuthUi.LoggedOut)
            }

            else -> Unit
        }
    }

    private fun onReady() {
        send(TdApi.GetMe()) { r ->
            if (r is TdApi.User) {
                // The "Saved Messages" chat has the same id as the user's own id.
                val me = r.id
                send(TdApi.CreatePrivateChat(me, true)) { c ->
                    if (c is TdApi.Chat) {
                        chatId = c.id
                        loadHistory(0L)
                    } else if (c is TdApi.Error) {
                        postError(c.message)
                    }
                }
            } else if (r is TdApi.Error) {
                postError(r.message)
            }
        }
    }

    private fun loadHistory(from: Long) {
        val chat = chatId
        if (chat == 0L) return
        loading = true
        changed()
        send(TdApi.GetChatHistory(chat, from, 0, 100, false)) { r ->
            when (r) {
                is TdApi.Messages -> {
                    val list = r.messages
                    if (list == null || list.isEmpty()) {
                        loading = false
                        changed()
                    } else {
                        synchronized(lock) {
                            for (m in list) {
                                val existing = messages[m.id]
                                if (existing == null) parse(m)?.let { messages[it.id] = it }
                            }
                        }
                        val next = list[list.size - 1].id
                        if (next == from) {
                            loading = false
                            changed()
                        } else {
                            changed()
                            loadHistory(next)
                        }
                    }
                }
                is TdApi.Error -> {
                    loading = false
                    postError(r.message)
                }
                else -> {
                    loading = false
                    changed()
                }
            }
        }
    }

    /** Deletes the temporary copy that was made for an upload once Telegram has it. */
    private fun cleanOutbox(m: TdApi.Message) {
        try {
            val root = params?.rootDir ?: return
            val outbox = File(root, "outbox").absolutePath
            val c = m.content as? TdApi.MessageDocument ?: return
            val path = c.document.document.local?.path
            if (path.isNullOrEmpty()) return
            val f = File(path)
            if (f.absolutePath.startsWith(outbox)) {
                f.delete()
                val parent = f.parentFile
                if (parent != null && parent.absolutePath.startsWith(outbox) && parent.absolutePath != outbox) {
                    parent.delete()
                }
            }
        } catch (_: Throwable) { }
    }

    private fun parseFolder(caption: String?): String {
        if (caption == null) return ""
        val first = caption.lineSequence().firstOrNull() ?: return ""
        return if (first.startsWith(FOLDER_PREFIX)) first.substring(FOLDER_PREFIX.length).trim() else ""
    }

    private fun sizeOf(f: TdApi.File): Long = if (f.size > 0) f.size else f.expectedSize

    /** Must be called while holding [lock] (it records file state). */
    private fun parse(m: TdApi.Message): Msg? {
        val pending = m.sendingState is TdApi.MessageSendingStatePending
        val failed = m.sendingState is TdApi.MessageSendingStateFailed
        val c = m.content
        val file: TdApi.File
        val name: String
        val mime: String
        val kind: FileKind
        val folder: String
        when (c) {
            is TdApi.MessageDocument -> {
                file = c.document.document
                name = c.document.fileName ?: ""
                mime = c.document.mimeType ?: ""
                kind = FileKind.DOCUMENT
                folder = parseFolder(c.caption?.text)
            }
            is TdApi.MessageVideo -> {
                file = c.video.video
                name = c.video.fileName ?: ""
                mime = c.video.mimeType ?: "video/mp4"
                kind = FileKind.VIDEO
                folder = parseFolder(c.caption?.text)
            }
            is TdApi.MessageAudio -> {
                file = c.audio.audio
                name = c.audio.fileName ?: ""
                mime = c.audio.mimeType ?: "audio/mpeg"
                kind = FileKind.AUDIO
                folder = parseFolder(c.caption?.text)
            }
            is TdApi.MessagePhoto -> {
                val sizes = c.photo.sizes
                if (sizes == null || sizes.isEmpty()) return null
                file = sizes[sizes.size - 1].photo
                name = ""
                mime = "image/jpeg"
                kind = FileKind.PHOTO
                folder = parseFolder(c.caption?.text)
            }
            else -> return null
        }
        if (!fileStates.containsKey(file.id)) fileStates[file.id] = file
        val finalName = when {
            name.isNotBlank() -> name
            kind == FileKind.PHOTO -> "photo_${m.id shr 20}.jpg"
            else -> "file_${m.id shr 20}"
        }
        return Msg(m.id, file.id, finalName, sizeOf(file), m.date, folder, kind, mime, pending, failed)
    }

    /** Must be called while holding [lock]. */
    private fun toCloudFile(m: Msg): CloudFile {
        val f = fileStates[m.fileId]
        val local = f?.local
        val remote = f?.remote
        val size = if (f != null && sizeOf(f) > 0) sizeOf(f) else m.size
        val done = !m.pending && local != null && local.isDownloadingCompleted && !local.path.isNullOrEmpty()
        val downloading = !m.pending && local != null && local.isDownloadingActive
        val progress = when {
            m.pending && remote != null && size > 0 -> (remote.uploadedSize.toFloat() / size).coerceIn(0f, 1f)
            downloading && size > 0 -> (local!!.downloadedSize.toFloat() / size).coerceIn(0f, 1f)
            else -> 0f
        }
        return CloudFile(
            messageId = m.id,
            fileId = m.fileId,
            name = m.name,
            size = size,
            date = m.date,
            folder = m.folder,
            kind = m.kind,
            mime = m.mime,
            localPath = if (done) local!!.path else null,
            downloading = downloading,
            uploading = m.pending,
            failed = m.failed,
            progress = progress
        )
    }
}
