@file:OptIn(ExperimentalMaterial3Api::class)

package com.savedcloud.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.IntentCompat
import com.savedcloud.app.td.AuthUi
import com.savedcloud.app.td.CloudFile
import com.savedcloud.app.td.FileKind
import com.savedcloud.app.td.TdRepository
import com.savedcloud.app.td.UiSnapshot
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private var sharedUris by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readShareIntent(intent)
        setContent {
            SavedCloudTheme {
                Root(
                    sharedUris = sharedUris,
                    onSharedHandled = { sharedUris = emptyList() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShareIntent(intent)
    }

    private fun readShareIntent(i: Intent?) {
        if (i == null) return
        val found = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { found.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { found.addAll(it) }
            }
        }
        if (found.isNotEmpty()) sharedUris = found
    }
}

// --------------------------------------------------------------------------- theme

@Composable
fun SavedCloudTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// --------------------------------------------------------------------------- state plumbing

@Composable
fun rememberSnapshot(): State<UiSnapshot> {
    val state = remember { mutableStateOf(TdRepository.snapshot()) }
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val listener: () -> Unit = { handler.post { state.value = TdRepository.snapshot() } }
        TdRepository.addListener(listener)
        listener()
        onDispose { TdRepository.removeListener(listener) }
    }
    return state
}

// --------------------------------------------------------------------------- root

@Composable
fun Root(sharedUris: List<Uri>, onSharedHandled: () -> Unit) {
    val ctx = LocalContext.current
    val snap by rememberSnapshot()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    when (val a = snap.auth) {
        is AuthUi.NeedCredentials -> SetupScreen()

        is AuthUi.Starting -> Centered { CircularProgressIndicator() }

        is AuthUi.WaitPhone -> FormScreen(
            title = "Log in with Telegram",
            hint = "Enter your phone number with country code. Your files are stored in your own Telegram \"Saved Messages\".",
            label = "Phone number (+123456789)",
            keyboard = KeyboardType.Phone,
            password = false,
            button = "Send code",
            error = snap.error,
            onSubmit = { TdRepository.submitPhone(it) }
        )

        is AuthUi.WaitCode -> FormScreen(
            title = "Enter the code",
            hint = "Telegram sent a login code to your Telegram app or by SMS (${a.phone}).",
            label = "Code",
            keyboard = KeyboardType.Number,
            password = false,
            button = "Continue",
            error = snap.error,
            onSubmit = { TdRepository.submitCode(it) }
        )

        is AuthUi.WaitPassword -> FormScreen(
            title = "Two-step verification",
            hint = if (a.hint.isBlank()) "Enter your Telegram cloud password." else "Enter your Telegram cloud password. Hint: ${a.hint}",
            label = "Password",
            keyboard = KeyboardType.Password,
            password = true,
            button = "Log in",
            error = snap.error,
            onSubmit = { TdRepository.submitPassword(it) }
        )

        is AuthUi.Unsupported -> Centered {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(a.reason, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { TdRepository.logout() }) { Text("Start over") }
            }
        }

        is AuthUi.LoggedOut -> {
            LaunchedEffect(Unit) { TdRepository.restart() }
            Centered { CircularProgressIndicator() }
        }

        is AuthUi.Ready -> {
            if (sharedUris.isNotEmpty()) {
                LaunchedEffect(sharedUris) {
                    FileOps.enqueueUploads(ctx, sharedUris, "")
                    onSharedHandled()
                }
            }
            FilesScreen(snap)
        }
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) { content() }
}

// --------------------------------------------------------------------------- setup + login

@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    var idText by rememberSaveable { mutableStateOf("") }
    var hash by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Telegram API setup", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Telegram needs a free API ID and API hash for every app. Get yours once at my.telegram.org " +
                "(log in, open \"API development tools\", create an app) and paste them below.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = idText,
            onValueChange = { idText = it.filter { c -> c.isDigit() } },
            label = { Text("API ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = hash,
            onValueChange = { hash = it.trim() },
            label = { Text("API hash") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val id = idText.toIntOrNull()
                if (id == null || id <= 0 || hash.length < 16) {
                    problem = "Enter a valid API ID and the 32-character API hash."
                } else {
                    Config.save(ctx, id, hash)
                    Config.startIfPossible(ctx)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save and continue") }
    }
}

@Composable
fun FormScreen(
    title: String,
    hint: String,
    label: String,
    keyboard: KeyboardType,
    password: Boolean,
    button: String,
    error: String?,
    onSubmit: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { if (text.isNotBlank()) onSubmit(text) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(button) }
    }
}

// --------------------------------------------------------------------------- files

@Composable
fun FilesScreen(snap: UiSnapshot) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf<String?>(null) }
    var extraFolders by remember { mutableStateOf(listOf<String>()) }
    var confirmDelete by remember { mutableStateOf<CloudFile?>(null) }
    var moveFile by remember { mutableStateOf<CloudFile?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val folders = (snap.files.map { it.folder }.filter { it.isNotBlank() } + extraFolders).distinct().sorted()
    val shown = snap.files.filter { selected == null || it.folder == selected }

    LaunchedEffect(snap.error) {
        val e = snap.error
        if (e != null) {
            snackbar.showSnackbar(e)
            TdRepository.clearError()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) FileOps.enqueueUploads(ctx, uris, selected ?: "")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected ?: "Saved Cloud") },
                actions = {
                    if (snap.loading) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    IconButton(onClick = { TdRepository.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    Box {
                        IconButton(onClick = { overflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                onClick = { overflow = false; showNewFolder = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Log out") },
                                onClick = { overflow = false; TdRepository.logout() }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.Add, contentDescription = "Upload files")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selected == null,
                        onClick = { selected = null },
                        label = { Text("All") }
                    )
                }
                items(folders) { name ->
                    FilterChip(
                        selected = selected == name,
                        onClick = { selected = name },
                        label = { Text("📁 $name") }
                    )
                }
            }
            HorizontalDivider()

            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (snap.loading) "Loading…" else "No files yet. Tap + to upload.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.messageId }) { f ->
                        FileRow(
                            f = f,
                            onClick = {
                                when {
                                    f.localPath != null -> FileOps.open(ctx, f)
                                    f.downloading || f.uploading -> Unit
                                    else -> FileOps.startDownload(ctx, f)
                                }
                            },
                            onDownload = { FileOps.startDownload(ctx, f) },
                            onCancelDownload = { TdRepository.cancelDownload(f.fileId) },
                            onOpen = { FileOps.open(ctx, f) },
                            onShare = { FileOps.share(ctx, f) },
                            onSave = { FileOps.saveToDownloads(ctx, f) },
                            onMove = { moveFile = f },
                            onDelete = { confirmDelete = f }
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete file?") },
            text = { Text("\"${f.name}\" will be deleted from your Telegram Saved Messages.") },
            confirmButton = {
                TextButton(onClick = { TdRepository.delete(f.messageId); confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    moveFile?.let { f ->
        var target by remember(f.messageId) { mutableStateOf(f.folder) }
        AlertDialog(
            onDismissRequest = { moveFile = null },
            title = { Text("Move to folder") },
            text = {
                Column {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text("Folder name (empty = no folder)") },
                        singleLine = true
                    )
                    folders.forEach { name ->
                        TextButton(onClick = { target = name }) { Text("📁 $name") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    TdRepository.moveToFolder(f.messageId, target)
                    moveFile = null
                }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { moveFile = null }) { Text("Cancel") } }
        )
    }

    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty()) {
                        extraFolders = extraFolders + n
                        selected = n
                    }
                    showNewFolder = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun FileRow(
    f: CloudFile,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val busy = f.uploading || f.downloading
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Text(emojiFor(f.kind), fontSize = 26.sp) },
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val state = when {
                    f.failed -> "Upload failed"
                    f.uploading -> "Uploading…"
                    f.downloading -> "Downloading…"
                    f.localPath != null -> "On device"
                    else -> "In the cloud"
                }
                val folderText = if (f.folder.isNotBlank()) " · 📁 ${f.folder}" else ""
                Text(
                    "${formatSize(f.size)} · ${formatDate(f.date)}$folderText · $state",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (f.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) {
                    Spacer(Modifier.height(4.dp))
                    if (f.progress > 0f) {
                        LinearProgressIndicator(progress = { f.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (f.localPath != null) {
                        DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                        DropdownMenuItem(text = { Text("Save to Downloads") }, onClick = { menu = false; onSave() })
                    } else if (f.downloading) {
                        DropdownMenuItem(text = { Text("Cancel download") }, onClick = { menu = false; onCancelDownload() })
                    } else if (!f.uploading) {
                        DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; onDownload() })
                    }
                    if (!f.uploading) {
                        DropdownMenuItem(text = { Text("Move to folder") }, onClick = { menu = false; onMove() })
                    }
                    DropdownMenuItem(
                        text = { Text(if (f.uploading) "Cancel upload" else "Delete") },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    )
}

// --------------------------------------------------------------------------- helpers

private fun emojiFor(k: FileKind): String = when (k) {
    FileKind.PHOTO -> "🖼️"
    FileKind.VIDEO -> "🎬"
    FileKind.AUDIO -> "🎵"
    FileKind.DOCUMENT -> "📄"
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "${bytes} B" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

private fun formatDate(unixSeconds: Int): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unixSeconds * 1000L))
