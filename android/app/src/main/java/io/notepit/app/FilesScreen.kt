package io.notepit.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import org.json.JSONObject

private fun encode(entry: DocumentEntry) = entry.toJson().toString()
private fun decode(value: String) = DocumentEntry.fromJson(JSONObject(value))
private val textExtensions = setOf("md", "txt", "json", "csv", "xml", "html", "css", "js", "ts", "yaml", "yml", "log")
private fun errorText(error: Throwable): String = when (error) {
    is FileConflictException -> "Conflict: ${error.message}. Nothing is overwritten. Choose a different name or destination."
    is SecurityException -> "Folder access was lost. Reconnect the folder from Files to grant access again."
    else -> "${error.message ?: "The file provider could not complete this action"}. Check folder access and available space, then try again."
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    repository: DocumentsRepository,
    onOpen: (DocumentEntry) -> Unit,
    onSync: () -> Unit,
    onTheme: () -> Unit,
    onContentSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val operations: FilesViewModel = viewModel()
    val haptic = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current
    var path by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var mode by rememberSaveable { mutableStateOf("Files") }
    var search by rememberSaveable { mutableStateOf("") }
    var searchScope by rememberSaveable { mutableStateOf("Folder") }
    var sort by rememberSaveable { mutableStateOf("Name") }
    var descending by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("All types") }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var dialog by rememberSaveable { mutableStateOf("") }
    var actionEntries by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var input by rememberSaveable { mutableStateOf("") }
    var roots by remember { mutableStateOf(emptyList<DocumentEntry>()) }
    var favorites by remember { mutableStateOf(emptySet<String>()) }
    var loading by remember { mutableStateOf(false) }
    val busy = operations.busy
    val progress = operations.progress
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var rootMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    val current = remember(path, mode) { if (mode == "Files") path.lastOrNull()?.let(::decode) else null }
    val displayError = error ?: operations.error?.let(::errorText)
    val targets = remember(actionEntries) { actionEntries.map(::decode) }
    val rootView = mode == "Files" && current == null && (search.isBlank() || searchScope == "Folder")
    val remoteQuery = if (searchScope == "Folder") "" else search.trim()
    var entries by remember(current?.uri, mode, remoteQuery, searchScope) { mutableStateOf(emptyList<DocumentEntry>()) }

    fun navigate(entry: DocumentEntry) {
        if (busy) return
        selected = arrayListOf()
        search = ""
        if (!entry.directory) { onOpen(entry); return }
        val prefix = if (mode == "Files" && current != null && searchScope != "All roots") path
        else ArrayList(listOfNotNull(roots.firstOrNull { it.treeUri == entry.treeUri && it.uri != entry.uri }?.let(::encode)))
        path = ArrayList(prefix + encode(entry))
        searchScope = "Folder"
        mode = "Files"
    }
    fun back() {
        when {
            selected.isNotEmpty() -> selected = arrayListOf()
            search.isNotEmpty() -> search = ""
            mode != "Files" -> mode = "Files"
            path.isNotEmpty() -> path = ArrayList(path.dropLast(1))
        }
    }
    fun operation(label: String, block: suspend () -> Unit) {
        error = null
        operations.execute(label, block)
    }
    fun action(name: String, items: List<DocumentEntry>) {
        actionEntries = ArrayList(items.map(::encode))
        input = if (name == "Rename") items.first().name else ""
        dialog = name
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            operation("Connecting folder…") { repository.connect(uri, data?.flags ?: Intent.FLAG_GRANT_READ_URI_PERMISSION); Unit }
        }
    }
    fun connect(entry: DocumentEntry? = null) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        if (entry != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(entry.treeUri))
        picker.launch(intent)
    }

    LaunchedEffect(operations.completed) {
        if (operations.completed > 0) { selected = arrayListOf(); refresh++ }
    }
    BackHandler(!busy && (selected.isNotEmpty() || search.isNotEmpty() || path.isNotEmpty() || mode != "Files")) { back() }
    BackHandler(busy) { }
    DisposableEffect(repository, lifecycle) {
        val observer = repository.observe { refresh++ }
        val resume = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(resume)
        onDispose { observer.close(); lifecycle.lifecycle.removeObserver(resume) }
    }
    LaunchedEffect(repository, refresh, current?.uri, mode, remoteQuery, searchScope) {
        loading = true
        error = null
        try {
            roots = repository.roots()
            favorites = repository.favorites()
            val base = when (if (remoteQuery.isNotEmpty()) "" else mode) {
                "Recent" -> repository.recents().map { entry ->
                    try { repository.query(entry) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { entry.copy(available = false) }
                }
                "Favorites" -> favorites.map { uri ->
                    val root = roots.firstOrNull { uri.startsWith(it.treeUri) }
                    try { repository.query(DocumentEntry(uri, "", treeUri = root?.treeUri.orEmpty())) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { DocumentEntry(uri, Uri.parse(uri).lastPathSegment ?: "Unavailable file", treeUri = root?.treeUri.orEmpty(), available = false) }
                }
                else -> null
            }
            when {
                remoteQuery.isNotEmpty() -> {
                    delay(250)
                    val searchRoots = if (searchScope == "All roots" || current == null) roots.filter { it.available } else listOf(current)
                    entries = repository.search(remoteQuery, searchRoots)
                }
                base != null -> entries = base
                current == null -> entries = roots
                else -> {
                    val fresh = repository.query(current)
                    if (!fresh.available) throw SecurityException("Folder permission is unavailable")
                    if (fresh != current) path = ArrayList(path.dropLast(1) + encode(fresh))
                    val listing = repository.list(fresh)
                    if (entries.isEmpty()) listing.collectLatest { entries = it; loading = false }
                    else entries = listing.last()
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = errorText(failure) }
        finally { loading = false }
    }
    val visible = remember(entries, search, sort, descending, filter, rootView) {
        val matching = entries.filter { entry ->
            (search.isBlank() || entry.name.contains(search.trim(), true)) && (rootView || when (filter) {
                "PDF" -> entry.pdf && !entry.directory
                "Text" -> !entry.directory && (entry.mime.startsWith("text/") || entry.name.substringAfterLast('.').lowercase() in textExtensions)
                "Folders" -> entry.directory
                "Conflicts" -> entry.conflict
                else -> true
            })
        }
        val opened = if (sort == "Opened") entries.withIndex().associate { it.value.uri to it.index } else emptyMap()
        val comparator = when (sort) {
            "Date" -> compareBy<DocumentEntry> { it.modified }
            "Size" -> compareBy<DocumentEntry> { it.size }
            "Opened" -> compareBy<DocumentEntry> { opened[it.uri] ?: Int.MAX_VALUE }
            else -> compareBy<DocumentEntry> { it.name.lowercase() }
        }.thenBy { it.uri }
        matching.sortedWith(compareByDescending<DocumentEntry> { it.directory }.then(if (descending) comparator.reversed() else comparator))
    }
    val chosen = entries.filter { it.uri in selected }
    val canChange = chosen.isNotEmpty() && chosen.all { it.available && it.writable && it.parentUri != null }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Column {
                    Text(if (selected.isNotEmpty()) "${selected.size} selected" else current?.name ?: mode, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (current != null && selected.isEmpty()) Text(path.dropLast(1).joinToString(" / ") { decode(it).name }.ifEmpty { "Connected folder" }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } },
                navigationIcon = { if (path.isNotEmpty() || selected.isNotEmpty() || mode != "Files" || search.isNotEmpty()) IconButton(onClick = ::back, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { selected = ArrayList(visible.map { it.uri }) }, enabled = !busy) { Icon(Icons.Outlined.SelectAll, "Select all visible files") }
                        IconButton(onClick = { selected = arrayListOf() }, enabled = !busy) { Icon(Icons.Outlined.Close, "Clear selection") }
                    } else {
                        IconButton(onClick = onContentSearch) { Icon(Icons.AutoMirrored.Outlined.ManageSearch, "Search contents in all folders") }
                        IconButton(onClick = onTheme) { Icon(Icons.Outlined.Palette, "Appearance") }
                        IconButton(onClick = { refresh++ }, enabled = !busy) { Icon(Icons.Outlined.Refresh, "Refresh") }
                        Box {
                            IconButton(onClick = { rootMenu = true }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, "Folder actions") }
                            DropdownMenu(rootMenu, { rootMenu = false }) {
                                DropdownMenuItem(text = { Text("Connect folder") }, onClick = { rootMenu = false; connect() }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) })
                                if (current != null) DropdownMenuItem(text = { Text("Connected folders") }, onClick = { path = arrayListOf(); search = ""; mode = "Files"; rootMenu = false })
                                DropdownMenuItem(text = { Text("Sync") }, onClick = { rootMenu = false; onSync() }, leadingIcon = { Icon(Icons.Outlined.Sync, null) })
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(progress, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    } else if (selected.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BottomAction("Copy", Icons.Outlined.ContentCopy, chosen.isNotEmpty() && chosen.all { it.available && !rootView }) { action("Copy", chosen) }
                            BottomAction("Move", Icons.AutoMirrored.Outlined.DriveFileMove, canChange) { action("Move", chosen) }
                            BottomAction("Favorite", Icons.Outlined.StarOutline, chosen.isNotEmpty()) { operation("Updating favorites…") { chosen.forEach { repository.toggleFavorite(it) } } }
                            BottomAction("Delete", Icons.Outlined.DeleteOutline, canChange) { action("Delete", chosen) }
                        }
                    } else if (mode == "Files") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (current == null) Button(onClick = { connect() }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Connect folder") }
                            else {
                                OutlinedButton(onClick = { action("New folder", emptyList()) }, enabled = current.writable && current.available, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("Folder") }
                                Button(onClick = { action("New file", emptyList()) }, enabled = current.writable && current.available, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("File") }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                listOf("Files", "Favorites", "Recent").forEachIndexed { index, label ->
                    SegmentedButton(selected = mode == label, enabled = !busy, onClick = { mode = label; selected = arrayListOf(); search = ""; searchScope = "Folder"; if (label == "Recent") { sort = "Opened"; descending = false } else if (sort == "Opened") sort = "Name" }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                }
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it; selected = arrayListOf() }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
                placeholder = { Text("Search filenames") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, "Clear search") } },
            )
            if (search.isNotBlank()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Folder", "Subfolders", "All roots").forEach { label -> FilterChip(selected = searchScope == label, onClick = { searchScope = label }, enabled = !busy, label = { Text(if (label == "Folder" && mode != "Files") "This view" else label) }) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    AssistChip(onClick = { sortMenu = true }, enabled = !busy, label = { Text(sort) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp)) })
                    DropdownMenu(sortMenu, { sortMenu = false }) { (if (mode == "Recent") listOf("Opened", "Name", "Date", "Size") else listOf("Name", "Date", "Size")).forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { sort = item; descending = item == "Date" || item == "Size"; sortMenu = false }) } }
                }
                IconButton(onClick = { descending = !descending }, enabled = !busy) { Icon(if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, if (descending) "Descending; change to ascending" else "Ascending; change to descending") }
                Box {
                    AssistChip(onClick = { filterMenu = true }, enabled = !busy && !rootView, label = { Text(filter) }, leadingIcon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(18.dp)) })
                    DropdownMenu(filterMenu, { filterMenu = false }) { listOf("All types", "PDF", "Text", "Folders", "Conflicts").forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { filter = item; selected = arrayListOf(); filterMenu = false }) } }
                }
                Spacer(Modifier.weight(1f))
                Text("${visible.size}", style = MaterialTheme.typography.labelMedium)
            }
            if (displayError != null) Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(displayError, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    Row { TextButton(onClick = { refresh++ }, enabled = !busy) { Text("Refresh") }; TextButton(onClick = { connect(current) }, enabled = !busy) { Text("Reconnect") }; TextButton(onClick = { error = null; operations.dismissError() }) { Text("Dismiss") } }
                }
            }
            if (visible.any { it.conflict }) Text("Sync conflicts are separate files. Compare both versions before deleting either one.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(when { search.isNotBlank() -> "No matching filenames"; filter != "All types" && !rootView -> "No files match this filter"; mode == "Favorites" -> "Star files or folders to find them here"; mode == "Recent" -> "Opened documents appear here"; current == null -> "Connect a folder to browse your documents"; else -> "This folder is empty" }, Modifier.padding(32.dp), style = MaterialTheme.typography.bodyLarge)
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(visible, key = { it.uri }) { entry ->
                    FileRow(
                        entry = entry, selected = entry.uri in selected, favorite = entry.uri in favorites, enabled = !busy,
                        onClick = {
                            if (selected.isNotEmpty()) selected = ArrayList(if (entry.uri in selected) selected - entry.uri else selected + entry.uri)
                            else if (entry.available) navigate(entry) else connect(roots.firstOrNull { it.treeUri == entry.treeUri } ?: entry)
                        },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); selected = ArrayList(if (entry.uri in selected) selected - entry.uri else selected + entry.uri) },
                        menu = { dismiss ->
                            if (!entry.available) DropdownMenuItem(text = { Text("Reconnect folder") }, onClick = { dismiss(); connect(roots.firstOrNull { it.treeUri == entry.treeUri } ?: entry) })
                            if (rootView) {
                                if (entry.available) DropdownMenuItem(text = { Text("Reconnect access") }, onClick = { dismiss(); connect(entry) })
                                DropdownMenuItem(text = { Text("Disconnect folder") }, onClick = { dismiss(); action("Disconnect", listOf(entry)) })
                            } else {
                                DropdownMenuItem(text = { Text(if (entry.uri in favorites) "Remove favorite" else "Add favorite") }, onClick = { dismiss(); operation("Updating favorites…") { repository.toggleFavorite(entry) } })
                                if (entry.available) DropdownMenuItem(text = { Text("Copy to…") }, onClick = { dismiss(); action("Copy", listOf(entry)) })
                                if (entry.available && entry.writable && entry.parentUri != null) {
                                    DropdownMenuItem(text = { Text("Move to…") }, onClick = { dismiss(); action("Move", listOf(entry)) })
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { dismiss(); action("Rename", listOf(entry)) })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { dismiss(); action("Delete", listOf(entry)) })
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (dialog in listOf("New file", "New folder", "Rename")) {
        val name = input.trim()
        val valid = name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name
        AlertDialog(
            onDismissRequest = { dialog = "" }, title = { Text(dialog) },
            text = { Column {
                OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("Name") }, supportingText = { Text(if (dialog == "New file") "Include an extension, for example notes.md" else "Use a single name, without slashes") })
            } },
            confirmButton = { TextButton(enabled = valid, onClick = {
                val kind = dialog; val name = input.trim(); val parent = current; val entry = targets.firstOrNull(); dialog = ""
                operation(if (kind == "Rename") "Renaming…" else "Creating…") {
                    when { kind == "Rename" && entry != null -> repository.rename(entry, name); parent != null -> repository.create(parent, name, kind == "New folder"); else -> Unit }
                }
            }) { Text(if (dialog == "Rename") "Rename" else "Create") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
    }
    if (dialog == "Delete" || dialog == "Disconnect") AlertDialog(
        onDismissRequest = { dialog = "" }, title = { Text(if (dialog == "Delete") "Delete ${targets.size} ${if (targets.size == 1) "item" else "items"}?" else "Disconnect folder?") },
        text = { Text(if (dialog == "Delete") "${targets.take(4).joinToString("\n") { it.name }}${if (targets.size > 4) "\n…and ${targets.size - 4} more" else ""}\n\nFolders and their contents will be deleted. This cannot be undone." else "${targets.firstOrNull()?.name}\n\nFiles stay on your device. NotePit will forget this folder and release its access.") },
        confirmButton = { TextButton(onClick = { val deleting = dialog == "Delete"; val files = targets; dialog = ""; operation(if (deleting) "Deleting…" else "Disconnecting…") { if (deleting) repository.delete(files) else files.forEach { repository.disconnect(it) } } }) { Text(if (dialog == "Delete") "Delete" else "Disconnect", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
    )
    if (dialog == "Copy" || dialog == "Move") DestinationDialog(
        repository, roots.filter { it.available }, dialog, targets,
        onDismiss = { dialog = "" },
        onChoose = { destination ->
            val moving = dialog == "Move"; val files = targets; dialog = ""
            operation(if (moving) "Moving…" else "Copying…") { repository.transfer(files, destination, moving, operations::reportProgress); Unit }
        },
    )
}

@Composable
private fun BottomAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null); Text(label, style = MaterialTheme.typography.labelSmall) } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: DocumentEntry, selected: Boolean, favorite: Boolean, enabled: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val details = buildList {
        if (!entry.available) add("Access missing · tap to reconnect")
        else {
            if (entry.conflict) add("Sync conflict")
            if (entry.directory) add("Folder") else add(Formatter.formatShortFileSize(context, entry.size))
            if (entry.modified > 0) add(DateUtils.getRelativeTimeSpanString(entry.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
            if (!entry.writable) add("Read only")
        }
    }.joinToString(" · ")
    ListItem(
        modifier = Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface).combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (!entry.available || entry.conflict) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = { Icon(when { selected -> Icons.Outlined.CheckCircle; !entry.available -> Icons.Outlined.FolderOff; entry.directory -> Icons.Outlined.Folder; entry.pdf -> Icons.Outlined.PictureAsPdf; else -> Icons.Outlined.Description }, null, tint = if (entry.conflict) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary) },
        trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
            if (favorite) Icon(Icons.Outlined.Star, "Favorite", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
            Box {
                IconButton(onClick = { expanded = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, "Actions for ${entry.name}") }
                DropdownMenu(expanded, { expanded = false }) {
                    menu { expanded = false }
                }
            }
        } },
    )
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DestinationDialog(
    repository: DocumentsRepository, roots: List<DocumentEntry>, operation: String, sources: List<DocumentEntry>,
    onDismiss: () -> Unit, onChoose: (DocumentEntry) -> Unit,
) {
    var path by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var entries by remember { mutableStateOf(emptyList<DocumentEntry>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val current = path.lastOrNull()?.let(::decode)
    val validDestination = current != null && current.writable && sources.none { it.uri == current.uri }
    LaunchedEffect(path, refresh, roots) {
        loading = true; error = null; entries = emptyList()
        try {
            if (current == null) entries = roots
            else repository.list(current).collectLatest { entries = it.filter { item -> item.directory }; loading = false }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = errorText(failure) }
        finally { loading = false }
    }
    Dialog(
        onDismissRequest = { if (!busy) { if (path.isNotEmpty()) path = ArrayList(path.dropLast(1)) else onDismiss() } },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current != null) IconButton(onClick = { path = ArrayList(path.dropLast(1)) }, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Parent folder") }
                    Column(Modifier.weight(1f)) { Text("$operation to…", style = MaterialTheme.typography.titleLarge); Text(current?.name ?: "Connected folders", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
                    IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Outlined.Close, "Cancel") }
                }
                Text("Choose a destination. Existing files will never be overwritten.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f)) {
                    items(entries.sortedBy { it.name.lowercase() }, key = { it.uri }) { folder ->
                        val source = sources.any { it.uri == folder.uri }
                        ListItem(
                            headlineContent = { Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { if (source) Text("Source folder") else if (!folder.writable) Text("Read only") },
                            leadingContent = { Icon(Icons.Outlined.Folder, null) },
                            modifier = Modifier.combinedClickable(enabled = !busy && !source, onClick = { path = ArrayList(path + encode(folder)) }),
                        )
                    }
                }
                if (!loading && entries.isEmpty()) Text(if (current == null) "Connect a folder before copying files." else "No subfolders", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                if (creating) {
                    OutlinedTextField(folderName, { folderName = it }, label = { Text("New folder name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = { creating = false }, enabled = !busy) { Text("Cancel") }
                        TextButton(enabled = !busy && current != null && folderName.isNotBlank() && folderName.trim() !in listOf(".", "..") && '/' !in folderName && '\\' !in folderName && '\u0000' !in folderName, onClick = {
                            val parent = current ?: return@TextButton
                            scope.launch {
                                busy = true; error = null
                                try { val folder = repository.create(parent, folderName.trim(), true); path = ArrayList(path + encode(folder)); creating = false; folderName = ""; refresh++ }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = errorText(failure) }
                                finally { busy = false }
                            }
                        }) { Text("Create & open") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { creating = true }, enabled = current?.writable == true && !busy, modifier = Modifier.weight(1f)) { Text("New folder") }
                    Button(onClick = { current?.let(onChoose) }, enabled = validDestination && !busy && !loading, modifier = Modifier.weight(1f)) { Text("$operation here") }
                }
            }
        }
    }
}
