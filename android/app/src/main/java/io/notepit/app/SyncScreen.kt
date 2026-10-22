package io.notepit.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.DateFormat
import java.util.Date

private const val forkPackage = "com.github.catfriend1.syncthingfork"
private const val forkReleases = "https://github.com/researchxxl/syncthing-android/releases"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(repository: DocumentsRepository, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = repository.context
    val client = remember(context) { SyncthingClient(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var roots by remember { mutableStateOf(emptyList<DocumentEntry>()) }
    var links by remember { mutableStateOf(emptyList<SyncLink>()) }
    var credentials by remember { mutableStateOf<SyncCredentials?>(null) }
    var snapshot by remember { mutableStateOf<SyncSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var apiError by remember { mutableStateOf<String?>(null) }
    var stale by remember { mutableStateOf(true) }
    var showMonitor by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf("https://127.0.0.1:8384") }
    // Never save this field through rememberSaveable or the activity instance-state bundle.
    var apiKey by remember { mutableStateOf("") }
    var allowHttp by remember { mutableStateOf(false) }
    var pendingTrust by remember { mutableStateOf<Pair<SyncEndpoint, String>?>(null) }
    var linkRoot by remember { mutableStateOf<DocumentEntry?>(null) }
    var selectedFolderId by remember { mutableStateOf("") }
    var selectedDeviceId by remember { mutableStateOf("") }
    var sameDirectory by remember { mutableStateOf(false) }

    fun message(failure: Exception): String = if (failure is IOException) failure.message ?: "The operation could not complete."
        else "The operation could not complete. Check the local service and folder access, then retry."

    fun operation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = message(failure) }
            finally { busy = false }
        }
    }

    suspend fun reloadLocal() {
        roots = repository.roots()
        links = client.links()
    }

    suspend fun refreshApi() {
        stale = true
        try {
            credentials = client.credentials()
            if (credentials != null) {
                snapshot = client.refresh()
                stale = false
                apiError = null
                endpoint = credentials!!.endpoint.url
            } else {
                snapshot = null
                apiError = null
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { apiError = message(failure) }
    }

    fun refresh() = operation {
        reloadLocal()
        refreshApi()
    }

    fun openUrl(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { error = "No browser could open this link." }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) operation {
            repository.connect(uri, data.flags)
            reloadLocal()
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
            if (event == Lifecycle.Event.ON_PAUSE) stale = true
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Sync setup") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Offline first", style = MaterialTheme.typography.titleLarge)
            Text("NotePit edits local files. Syncthing runs separately and synchronizes them when devices can connect. Setup is manual; API monitoring below is optional.")
            SyncStep("1 · Install on your computer") {
                Text("On Linux or Windows, install official Syncthing. Open its local Web GUI, keep it running, and choose a normal local notes folder.")
                OutlinedButton(onClick = { openUrl("https://syncthing.net/downloads/") }) { Text("Official computer downloads") }
            }
            SyncStep("2 · Install the Android companion") {
                Text("Use the maintained researchxxl Syncthing-Fork, in the same Android profile as NotePit. Install its release, complete onboarding and grant its requested local-storage access. The discontinued official Android app and other Play forks are different apps.")
                OutlinedButton(onClick = { openUrl(forkReleases) }) { Text("Download Syncthing-Fork") }
                OutlinedButton(onClick = {
                    val launch = context.packageManager.getLaunchIntentForPackage(forkPackage)
                    if (launch == null) error = "Syncthing-Fork's launcher is not available. Install the researchxxl release in this profile, or open it manually."
                    else try { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (_: Exception) { error = "The companion could not open. Open Syncthing-Fork manually." }
                }) { Text("Open companion") }
                Text("Opening or installing the companion does not establish service or synchronization status.", style = MaterialTheme.typography.bodySmall)
            }
            SyncStep("3 · Pair devices and share the folder") {
                Text("In Syncthing on each device, add the other's Device ID and accept pairing on both. Share the computer folder with the phone, accept it in the Fork, and choose the phone's real local directory. Use the same Folder ID and Send & Receive on both devices. Configure folders and devices inside Syncthing, not NotePit.")
                Text("Enable file versioning on each receiving device for recovery; it is not a backup of every local edit. Keep separate backups. Review the Fork's run conditions and Android battery restrictions if syncing stops; choose any battery exemption yourself and expect increased battery use.")
                Text("Concurrent edits can create .sync-conflict-… files. Keep both copies until you choose what to retain. Renaming/case errors need manual attention in Syncthing.")
            }
            SyncStep("4 · Open that same phone folder in NotePit") {
                Text("Select the exact same physical local directory through Android's folder picker. A SAF URI or matching name cannot prove it matches Syncthing's filesystem path. Cloud-provider folders and app-private directories are not a substitute for this local shared folder.")
                Button(enabled = !busy, onClick = {
                    pickFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    ))
                }) { Text("Select local folder") }
                Text("If Android hides a storage root, Download root or Android/data, use an ordinary subfolder instead. Your files stay editable offline without API monitoring or a connected peer.")
            }

            HorizontalDivider()
            Text("Folder associations", style = MaterialTheme.typography.titleMedium)
            Text("Link each granted NotePit folder to its Syncthing Folder ID only after checking both apps. Links are your confirmation, not an automatic path verification.")
            if (roots.isEmpty()) Text("No granted folders yet. Use step 4.")
            roots.forEach { root ->
                val link = links.firstOrNull { it.treeUri == root.treeUri }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(root.name, style = MaterialTheme.typography.titleSmall)
                        if (!root.available) Text("Local folder access unavailable. Reconnect it in Files.")
                        if (link == null) Text("Not associated. Offline editing does not require a link.")
                        else {
                            Text("Folder ID: ${link.folderId}")
                            Text("Directory equality: confirmed by you, not verified by NotePit.", style = MaterialTheme.typography.bodySmall)
                            val bound = credentials?.deviceId == link.deviceId && link.deviceId.isNotBlank()
                            if (!bound) Text("Manual association retained; confirm this link again after connecting the intended API device to enable monitoring.")
                            val current = snapshot
                            if (bound && current?.deviceId == link.deviceId) {
                                val folder = current.folders.firstOrNull { it.id == link.folderId }
                                if (folder == null) Text("Folder ID absent from the last configuration snapshot.")
                                else {
                                    Text("${folder.label.ifBlank { folder.id }} · ${folder.type}${if (folder.paused) " · paused" else ""}")
                                    SelectionContainer { Text("Syncthing path: ${folder.path}", style = MaterialTheme.typography.bodySmall) }
                                    val status = current.statuses[link.folderId]
                                    if (status == null) Text("No folder status snapshot. Refresh to retrieve it.")
                                    else if (status.unavailable != null) Text(status.unavailable)
                                    else {
                                        Text(if (folder.paused) "Folder paused; the index may not reflect new changes."
                                            else if (status.localIndexCurrent) "Local index up to date at snapshot time."
                                            else "Local state: ${status.state} · needs ${status.needs} items / ${status.needBytes} bytes · ${status.pullErrors} pull errors")
                                        status.errors.take(5).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                        if (status.errors.size > 5) Text("More errors are available in the companion.")
                                    }
                                    val peers = folder.devices.filterNot { it == current.deviceId }
                                    val connected = peers.count { it in current.connectedDevices }
                                    Text("Peers connected at snapshot: $connected/${peers.size}. Connectivity is not proof of receipt.")
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy, onClick = {
                                linkRoot = root
                                selectedFolderId = link?.folderId ?: ""
                                selectedDeviceId = credentials?.deviceId ?: ""
                                sameDirectory = false
                            }) { Text(if (link == null) "Associate" else "Review link") }
                            if (link != null) TextButton(enabled = !busy, onClick = { operation { client.unlink(root.treeUri); links = client.links() } }) { Text("Remove link") }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("Optional local API monitor", style = MaterialTheme.typography.titleMedium)
            Text("This uses the Fork's administrative API key. It can control the entire Syncthing instance, not just one folder. NotePit only reads status and configuration; the encrypted key stays native and never enters document WebViews.")
            snapshot?.let { current ->
                Text("${if (stale) "Stale / last successful" else "Authenticated"} snapshot: ${DateFormat.getDateTimeInstance().format(Date(current.at))}")
                SelectionContainer { Text("Local Device ID: ${current.deviceId}", style = MaterialTheme.typography.bodySmall) }
                Text("Snapshots are not live. Local index up to date does not mean a remote device received your edits, or that an offline peer has no unseen changes. Refresh after changes.", style = MaterialTheme.typography.bodySmall)
            }
            if (snapshot == null) Text("No authenticated status available. Offline folder access and manual associations still work.")
            apiError?.let { Text("API unavailable · $it", color = MaterialTheme.colorScheme.error) }
            if (credentials != null) {
                Text("Bound to ${credentials!!.endpoint.url}")
                OutlinedButton(enabled = !busy, onClick = { operation {
                    client.disconnect()
                    credentials = null
                    snapshot = null
                    apiKey = ""
                    pendingTrust = null
                    apiError = null
                    stale = true
                } }) { Text("Disconnect API and forget key / pin") }
                Text("Disconnecting does not remove files, SAF grants, folder links, or the Fork's configuration.", style = MaterialTheme.typography.bodySmall)
            }
            // Also offered if AndroidKeyStore became unavailable, so recovery never needs app-data deletion.
            if (credentials == null && apiError != null) TextButton(enabled = !busy, onClick = { operation {
                client.disconnect(); apiError = null; snapshot = null; apiKey = ""; pendingTrust = null
            } }) { Text("Forget unreadable API credentials") }
            TextButton(onClick = { showMonitor = !showMonitor }) { Text(if (showMonitor) "Hide API setup" else "Set up / replace API connection") }
            if (showMonitor) {
                Text("In Fork: Settings → Syncthing Options → API key; tap to copy it, then paste below. Confirm its GUI port and keep both apps in the same profile. No automatic key access or folder provisioning is used.")
                OutlinedTextField(endpoint, onValueChange = { endpoint = it }, label = { Text("Loopback GUI URL") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row {
                    Checkbox(checked = allowHttp, onCheckedChange = { allowHttp = it }, enabled = !busy)
                    Text("Allow HTTP loopback: no TLS or certificate identity. Other local apps may impersonate the service and steal the admin key. Use HTTPS unless you knowingly configured HTTP.", modifier = Modifier.padding(top = 8.dp))
                }
                OutlinedTextField(apiKey, onValueChange = { apiKey = it }, label = { Text("Admin API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && apiKey.isNotBlank(), onClick = { operation {
                    val target = SyncEndpoint.parse(endpoint, allowHttp)
                    SyncthingClient.validateKey(apiKey)
                    if (target.scheme == "https") pendingTrust = target to client.inspectCertificate(target)
                    else {
                        credentials = client.connect(target, apiKey, null)
                        apiKey = ""
                        snapshot = null
                        refreshApi()
                    }
                } }) { Text("Verify and connect") }
            }
            OutlinedButton(enabled = !busy, onClick = ::refresh) { Text("Refresh folders and status") }
            Text("Refresh runs on demand and when this screen resumes; no background polling. A failed refresh leaves only a clearly marked old snapshot.", style = MaterialTheme.typography.bodySmall)
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    pendingTrust?.let { (target, pin) ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingTrust = null },
            title = { Text("Trust this local certificate?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${target.url}\nSHA-256 certificate fingerprint:")
                SelectionContainer { Text(pin) }
                Text("This first connection is not independently verified. Compare this fingerprint with the certificate of your intended Syncthing service if possible. A malicious local app could answer this port. Trust only when you are sure the Fork is running here. Certificate inspection did not send an API key.")
                Text("Every later TLS connection must present exactly this certificate. A certificate change requires explicitly trusting again.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = { operation {
                credentials = client.connect(target, apiKey, pin)
                apiKey = ""
                pendingTrust = null
                snapshot = null
                refreshApi()
            } }) { Text("Trust fingerprint and connect") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingTrust = null }) { Text("Cancel") } },
        )
    }

    linkRoot?.let { root ->
        AlertDialog(
            onDismissRequest = { if (!busy) linkRoot = null },
            title = { Text("Associate ${root.name}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select a Folder ID from the last API snapshot, or enter it manually. This does not configure Syncthing or translate a SAF URI into a filesystem path.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                snapshot?.takeIf { it.deviceId == credentials?.deviceId }?.folders?.forEach { folder ->
                    OutlinedButton(onClick = { selectedFolderId = folder.id; selectedDeviceId = snapshot!!.deviceId }, modifier = Modifier.fillMaxWidth()) {
                        Text("${folder.label.ifBlank { folder.id }}\nID: ${folder.id}\n${folder.path}")
                    }
                }
                OutlinedTextField(selectedFolderId, onValueChange = { selectedFolderId = it }, label = { Text("Syncthing Folder ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(if (selectedDeviceId.isBlank()) "Manual offline link: API identity not bound. Review this association after connecting API monitoring."
                    else "API device binding: $selectedDeviceId", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text("NotePit tree: ${root.treeUri}", style = MaterialTheme.typography.bodySmall) }
                Row {
                    Checkbox(sameDirectory, onCheckedChange = { sameDirectory = it })
                    Text("I checked both apps and confirm this grant opens the same physical local directory configured for this Folder ID.", Modifier.padding(top = 8.dp))
                }
            } },
            confirmButton = { TextButton(enabled = !busy && sameDirectory && selectedFolderId.isNotBlank(), onClick = { operation {
                client.associate(root.treeUri, selectedFolderId, selectedDeviceId)
                links = client.links()
                linkRoot = null
                refreshApi()
            } }) { Text("Confirm association") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { linkRoot = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SyncStep(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
