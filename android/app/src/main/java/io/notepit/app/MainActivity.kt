package io.notepit.app

import android.os.Bundle
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val sessions: DocumentSessions by viewModels()
    private val engines = LinkedHashMap<String, DocumentWebView>(4, 0.75f, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        setContent { NotePitApp(sessions, engines) }
    }
    override fun onStop() { engines.values.toList().forEach { it.flush() }; super.onStop() }
    override fun onDestroy() { engines.values.toList().forEach { it.destroy() }; engines.clear(); super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotePitApp(model: DocumentSessions, engines: LinkedHashMap<String, DocumentWebView>) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()
    val states = rememberSaveableStateHolder()
    val lifecycle = LocalLifecycleOwner.current
    val snackbars = remember { SnackbarHostState() }
    var sync by rememberSaveable { mutableStateOf(false) }
    var themeDialog by rememberSaveable { mutableStateOf(false) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    var closing by remember { mutableStateOf<OpenDocument?>(null) }
    var reload by remember { mutableStateOf<OpenDocument?>(null) }
    var copying by remember { mutableStateOf<OpenDocument?>(null) }
    var copyName by rememberSaveable { mutableStateOf("") }
    val active = model.documents.firstOrNull { it.id == model.activeId }
    fun requestClose(document: OpenDocument) {
        scope.launch {
            try {
                engines[document.id]?.flush()?.await()
                if (document.dirty) closing = document
                else { engines.remove(document.id)?.destroy(); model.close(document, false) }
            } catch (error: Exception) { model.message = "The document remains open: ${error.message}" }
        }
    }
    NotePitTheme(model.themeMode) {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        SideEffect {
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            insets.isAppearanceLightStatusBars = !dark
            insets.isAppearanceLightNavigationBars = !dark
            if (immersive && active != null) insets.hide(WindowInsetsCompat.Type.systemBars()) else insets.show(WindowInsetsCompat.Type.systemBars())
            engines.values.forEach { it.theme(dark) }
        }
        LaunchedEffect(model.message) {
            model.message?.let { snackbars.showSnackbar(it); model.message = null }
        }
        DisposableEffect(lifecycle, model) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.checkActive() }
            lifecycle.lifecycle.addObserver(observer)
            onDispose { lifecycle.lifecycle.removeObserver(observer) }
        }
        BackHandler(active != null || sync) {
            if (immersive) { immersive = false; active?.let { engines[it.id]?.command("immersive:off") } }
            else { active?.let { engines[it.id]?.flush() }; model.activate(null); sync = false }
        }
        BackHandler(active == null && !sync) { activity.moveTaskToBack(true) }
        Surface(Modifier.fillMaxSize()) {
            Box {
                Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                    Box(Modifier.weight(1f)) {
                        when {
                            !model.initialized -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            active != null -> key(active.id, active.reload) {
                                val engine = remember(active.id, active.reload) {
                                    try { engines[active.id] ?: DocumentWebView(context, active, model, dark).also { engines[active.id] = it } }
                                    catch (error: Exception) { active.error = error.message; null }
                                }
                                DisposableEffect(engine) {
                                    onDispose { engine?.flush() }
                                }
                                LaunchedEffect(active.id) {
                                    // Retain at most two renderers; tabs and drafts remain independently durable.
                                    while (engines.size > 2) {
                                        val oldest = engines.entries.firstOrNull { it.key != active.id } ?: break
                                        try { oldest.value.flush().await(); oldest.value.destroy(); engines.remove(oldest.key) }
                                        catch (error: Exception) { model.message = "A draft could not be checkpointed: ${error.message}"; break }
                                    }
                                }
                                DocumentPane(active, engine, immersive,
                                    onBack = { engine?.flush(); model.activate(null) },
                                    onClose = { requestClose(active) },
                                    onReload = { reload = active },
                                    onCopy = { copying = active; copyName = active.entry.name.substringBeforeLast('.', active.entry.name) + "-local-copy" + active.entry.name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" } },
                                    onTheme = { themeDialog = true },
                                    onImmersive = { immersive = !immersive; engine?.command(if (immersive) "immersive:on" else "immersive:off") },
                                )
                            }
                            sync -> states.SaveableStateProvider("sync") { SyncScreen(model.repository, onBack = { sync = false }) }
                            else -> states.SaveableStateProvider("files") {
                                FilesScreen(model.repository, onOpen = { entry -> engines.values.forEach { it.flush() }; sync = false; model.open(entry) }, onSync = { sync = true }, onTheme = { themeDialog = true })
                            }
                        }
                    }
                    if (model.documents.isNotEmpty() && !immersive) {
                        Surface(tonalElevation = 3.dp) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { active?.let { engines[it.id]?.flush() }; sync = false; model.activate(null) }) { Icon(Icons.Outlined.FolderOpen, "Files") }
                                model.documents.forEach { document ->
                                    FilterChip(selected = document.id == model.activeId, onClick = { active?.let { engines[it.id]?.flush() }; sync = false; model.activate(document) },
                                        label = { Text(document.entry.name + if (document.dirty) " •" else "", Modifier.widthIn(max = 150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        trailingIcon = { IconButton(onClick = { requestClose(document) }, modifier = Modifier.size(28.dp), enabled = !document.busy) { Icon(Icons.Outlined.Close, "Close ${document.entry.name}", Modifier.size(16.dp)) } },
                                        modifier = Modifier.padding(end = 6.dp))
                                }
                            }
                        }
                    }
                }
                if (model.opening) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).imePadding())
            }
        }
        if (themeDialog) AlertDialog(onDismissRequest = { themeDialog = false }, title = { Text("Appearance") }, text = {
            Column { listOf("system", "light", "dark").forEach { mode ->
                TextButton(onClick = { model.setTheme(mode); themeDialog = false }, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = model.themeMode == mode, onClick = null); Spacer(Modifier.width(12.dp)); Text(mode.replaceFirstChar { it.uppercase() })
                }
            } }
        }, confirmButton = {})
        closing?.let { document -> AlertDialog(onDismissRequest = { closing = null }, title = { Text("Unsaved changes") }, text = { Text("Save the original, keep a recoverable draft, or discard these edits.") },
            confirmButton = { TextButton(onClick = { closing = null; scope.launch {
                try { engines[document.id]?.flush()?.await(); engines.remove(document.id)?.destroy(); model.close(document, false) }
                catch (error: Exception) { model.message = "The draft remains open: ${error.message}" }
            } }) { Text("Keep draft") } },
            dismissButton = { Row { TextButton(onClick = { closing = null; engines[document.id]?.save() }) { Text("Save") }; TextButton(onClick = { closing = null; engines.remove(document.id)?.destroy(); model.close(document, true) }) { Text("Discard") } } }) }
        reload?.let { document -> AlertDialog(onDismissRequest = { reload = null }, title = { Text("Reload original?") }, text = { Text(if (document.dirty) "This discards unsaved changes. Save a separate copy first if you need both versions." else "Load the latest file from its original folder.") }, confirmButton = {
            TextButton(onClick = { reload = null; engines.remove(document.id)?.destroy(); model.reload(document) }) { Text("Reload") }
        }, dismissButton = { TextButton(onClick = { reload = null }) { Text("Cancel") } }) }
        copying?.let { document -> AlertDialog(onDismissRequest = { copying = null }, title = { Text("Save a separate copy") }, text = { OutlinedTextField(copyName, { copyName = it }, singleLine = true, label = { Text("Filename") }) }, confirmButton = {
            TextButton(onClick = { copying = null; engines[document.id]?.save(copyName.trim()) }, enabled = copyName.isNotBlank()) { Text("Save copy") }
        }, dismissButton = { TextButton(onClick = { copying = null }) { Text("Cancel") } }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentPane(session: OpenDocument, engine: DocumentWebView?, immersive: Boolean, onBack: () -> Unit, onClose: () -> Unit, onReload: () -> Unit, onCopy: () -> Unit, onTheme: () -> Unit, onImmersive: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = { if (!immersive) TopAppBar(title = { Column {
            Text(session.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(if (session.busy) "Saving…" else if (!session.entry.writable) "Read only" else if (session.dirty) "Unsaved changes · draft retained" else "Saved", style = MaterialTheme.typography.labelSmall)
        } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Files") } }, actions = {
            IconButton(onClick = { engine?.save() }, enabled = session.dirty && session.entry.writable && !session.busy && engine?.ready == true) { Icon(Icons.Outlined.Save, "Save") }
            Box { IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Document actions") }
                DropdownMenu(menu, { menu = false }) {
                    listOf("Immersive reading" to onImmersive, "Reload original" to onReload, "Save a separate copy" to onCopy, "Appearance" to onTheme, "Close document" to onClose).forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() }, enabled = !session.busy)
                    }
                }
            }
        }) },
        floatingActionButton = { if (immersive) SmallFloatingActionButton(onClick = onImmersive) { Icon(Icons.Outlined.FullscreenExit, "Exit immersive reading") } },
    ) { padding -> Column(Modifier.fillMaxSize().padding(padding)) {
        if (session.externalChanged || session.error != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(session.error ?: "The original changed outside NotePit. Reload it or save a separate copy.", style = MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick = onReload, enabled = !session.busy) { Text("Reload") }; if (session.dirty) TextButton(onClick = onCopy, enabled = !session.busy) { Text("Save copy") } }
            }
        }
        if (session.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (engine != null) AndroidView(factory = { context -> FrameLayout(context) }, modifier = Modifier.weight(1f), update = { container ->
            if (engine.view.parent !== container) {
                (engine.view.parent as? android.view.ViewGroup)?.removeView(engine.view)
                container.removeAllViews()
                container.addView(engine.view, FrameLayout.LayoutParams(-1, -1))
            }
        }, onRelease = { it.removeAllViews() })
    } }
}
