package io.notepit.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(model: DocumentSessions, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val index by model.searchIndex.status.collectAsState()
    val installation by model.semanticEngine.status.collectAsState()
    var consent by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    val grouped = remember(model.searchHits) { model.searchHits.withIndex().groupBy { it.value.entry.uri }.values.toList() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Search contents") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Files") }
        }, actions = {
            IconButton(onClick = { model.refreshSearch(force = true) }, enabled = !index.busy) { Icon(Icons.Outlined.Refresh, "Refresh original files and rebuild search index") }
            Box {
                IconButton(onClick = { options = true }) { Icon(Icons.Outlined.MoreVert, "Search settings") }
                DropdownMenu(options, { options = false }) {
                    if (installation.installed) DropdownMenuItem(text = { Text("Remove offline model") }, onClick = { options = false; removing = true })
                    else DropdownMenuItem(text = { Text("Install offline model…") }, onClick = { options = false; consent = true }, enabled = !installation.busy)
                }
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(value = model.searchQuery, onValueChange = { model.updateSearch(query = it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
                label = { Text("Contents in all connected folders") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (model.searchQuery.isNotEmpty()) IconButton(onClick = { model.updateSearch(query = "") }) { Icon(Icons.Outlined.Close, "Clear query") } })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !model.semanticSearch, onClick = { model.updateSearch(semantic = false) }, label = { Text("Keyword") })
                FilterChip(selected = model.semanticSearch, onClick = { model.updateSearch(semantic = true) }, enabled = installation.installed, label = { Text("Hybrid semantic") })
                if (!installation.installed && !installation.busy) TextButton(onClick = { consent = true }) { Text("Install model") }
            }
            if (installation.busy && !installation.installed) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(installation.message.ifBlank { "Installing offline model…" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (model.downloadingSearchModel) TextButton(onClick = model::cancelSearchModelDownload) { Text("Cancel") }
                }
            } else if (installation.message.isNotBlank() && !installation.installed) {
                Text(installation.message, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            Text(if (model.semanticSearch) "Approximate meaning matches · English · fully offline. Verify the source; similar meaning is not proof."
                else if (!installation.installed) "Keyword search works offline. Semantic search needs an optional model; nothing downloads automatically."
                else "Literal, case-insensitive text matches · fully offline.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            if (index.busy || model.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${index.documents} documents · ${index.passages} passages" + (if (index.failures > 0) " · ${index.failures} skipped" else "") + (if (index.warnings > 0) " · ${index.warnings} warnings" else ""),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            if (index.message.isNotBlank()) Text(index.message, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            model.searchError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (model.searchHits.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onOpen(if (model.selectedHit <= 0) model.searchHits.lastIndex else model.selectedHit - 1) }, enabled = !model.opening) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous result") }
                    Text("${model.searchHits.size} passages in ${grouped.size} documents", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { onOpen((model.selectedHit + 1).mod(model.searchHits.size)) }, enabled = !model.opening) { Icon(Icons.Outlined.KeyboardArrowDown, "Next result") }
                }
                Text("Rank and score are relative relevance, not probability.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (model.searchQuery.isBlank()) item { Text("Search the text of notes, documents and PDF text layers across every connected folder. Filename search remains in Files.", style = MaterialTheme.typography.bodyMedium) }
                else if (!model.searching && model.searchHits.isEmpty() && model.searchError == null) item {
                    Text(if (model.semanticSearch && !installation.installed) "Install the optional offline model or choose Keyword."
                        else if (index.busy) "The local index is updating. Results appear as documents become available."
                        else "No matching passages. Refresh to include external changes. Image-only PDFs need an existing text layer.")
                }
                grouped.forEach { hits ->
                    item(key = "document:${hits.first().value.entry.uri}") {
                        Column {
                            Text(hits.first().value.entry.name, style = MaterialTheme.typography.titleSmall)
                            Text(hits.first().value.path, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    items(hits, key = { "hit:${it.index}:${it.value.entry.uri}" }) { ranked ->
                        val hit = ranked.value
                        val snippet = remember(hit) { searchSnippet(hit) }
                        OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !model.opening) { onOpen(ranked.index) }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(searchReference(hit) + " · rank ${ranked.index + 1} · score " + String.format(Locale.ROOT, "%.4f", hit.score), style = MaterialTheme.typography.labelSmall)
                                Text(if (hit.semantic) "Approximate semantic match — verify source" else "Keyword match in indexed original", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Install offline semantic search?") }, text = {
        Text("Download ${SemanticEngine.MODEL_NAME} from Hugging Face over the internet. Download: ${SemanticEngine.DOWNLOAD_BYTES} bytes. Installed model: ${SemanticEngine.STORAGE_BYTES} bytes. Keep at least ${SemanticEngine.REQUIRED_FREE_BYTES} bytes free for installation. The pinned model files are SHA-256 verified. After installation, English queries and document passages are processed only on this device; they are never uploaded. Keyword search needs no download.", Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { consent = false; model.downloadSearchModel() }) { Text("Download model") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("Cancel") } })
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("Remove offline model?") }, text = { Text("Delete the installed model (${SemanticEngine.STORAGE_BYTES} bytes). Keyword search and your original files remain available. Semantic search will require another explicit download.") }, confirmButton = { TextButton(onClick = { removing = false; model.removeSearchModel() }) { Text("Remove model") } }, dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } })
}

internal fun searchReference(hit: SearchHit): String = if (hit.page > 0) "Page ${hit.page}" else if (hit.endLine > hit.line) "Lines ${hit.line}–${hit.endLine}" else "Line ${hit.line}"

private val snippetWhitespace = Regex("\\s+")

private fun searchSnippet(hit: SearchHit): String {
    val match = if (hit.query.isBlank()) -1 else hit.text.indexOf(hit.query, ignoreCase = true)
    val start = if (match < 0) 0 else (match - 100).coerceAtLeast(0)
    val end = (start + 460).coerceAtMost(hit.text.length)
    return (if (start > 0) "…" else "") + hit.text.substring(start, end).replace(snippetWhitespace, " ") + if (end < hit.text.length) "…" else ""
}
