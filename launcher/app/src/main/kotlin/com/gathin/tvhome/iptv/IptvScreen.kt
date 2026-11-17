package com.gathin.tvhome.iptv

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun IptvScreen(
    repository: IptvRepository,
    onExit: () -> Unit,
    onPlayerChanged: (IptvMedia3PlayerView?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(emptyList<IptvSource>()) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var showM3uDialog by rememberSaveable { mutableStateOf(false) }
    var showXtreamDialog by rememberSaveable { mutableStateOf(false) }
    var showLicensesDialog by rememberSaveable { mutableStateOf(false) }
    var sourceToDelete by remember { mutableStateOf<IptvSource?>(null) }
    var activeChannel by remember { mutableStateOf<IptvChannel?>(null) }
    var selectedGroup by rememberSaveable { mutableStateOf(ALL_CHANNELS) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(repository) {
        loading = true
        try {
            sources = withContext(Dispatchers.IO) { repository.loadSources() }
        } catch (failure: Exception) {
            errorText = readableError(failure)
        } finally {
            loading = false
        }
    }

    val allChannels = remember(sources) {
        sources.filter(IptvSource::enabled)
            .flatMap(IptvSource::cachedChannels)
            .distinctBy(IptvChannel::id)
    }
    val groups = remember(allChannels) {
        listOf(ALL_CHANNELS) + allChannels.map(IptvChannel::group)
            .filter(String::isNotBlank)
            .distinct()
            .sortedBy { it.lowercase(Locale.getDefault()) }
    }
    val visibleChannels = remember(allChannels, selectedGroup, query) {
        allChannels.filter { channel ->
            (selectedGroup == ALL_CHANNELS || channel.group == selectedGroup) &&
                (query.isBlank() || channel.name.contains(query, ignoreCase = true))
        }
    }

    fun runTask(task: suspend () -> IptvTaskResult) {
        scope.launch {
            loading = true
            errorText = null
            statusText = null
            try {
                val result = withContext(Dispatchers.IO) { task() }
                statusText = result.message
                errorText = result.warning
            } catch (failure: Exception) {
                errorText = readableError(failure)
            } finally {
                try {
                    sources = withContext(Dispatchers.IO) { repository.loadSources() }
                } catch (failure: Exception) {
                    errorText = readableError(failure)
                }
                loading = false
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runTask {
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "local.m3u"
                val content = context.contentResolver.openInputStream(uri)?.use { input ->
                    InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                        val text = StringBuilder()
                        val buffer = CharArray(8192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            if (text.length + count > IptvRepository.MAX_PLAYLIST_CHARS) {
                                throw IptvRepositoryException("Playlist file is too large.")
                            }
                            text.append(buffer, 0, count)
                        }
                        text.toString()
                    }
                } ?: throw IptvRepositoryException("Could not read the selected playlist file.")
                repository.addLocalM3u("", fileName, content)
                IptvTaskResult(message = "Playlist saved on this TV.")
            }
        }
    }

    if (activeChannel == null) {
        BackHandler(onBack = onExit)
        IptvHome(
            sources = sources,
            channels = visibleChannels,
            groups = groups,
            selectedGroup = selectedGroup,
            query = query,
            loading = loading,
            errorText = errorText,
            statusText = statusText,
            onExit = onExit,
            onQueryChanged = { query = it },
            onGroupSelected = { selectedGroup = it },
            onPlay = { activeChannel = it },
            onAddM3uUrl = { showM3uDialog = true },
            onImportFile = {
                filePicker.launch(
                    arrayOf(
                        "application/x-mpegurl",
                        "application/vnd.apple.mpegurl",
                        "audio/mpegurl",
                        "audio/x-mpegurl",
                        "text/plain",
                        "application/octet-stream",
                    ),
                )
            },
            onAddXtream = { showXtreamDialog = true },
            onSyncAll = {
                runTask {
                    val result = repository.syncAll()
                    if (result.errors.isNotEmpty()) {
                        IptvTaskResult(
                            warning = result.errors.joinToString("\n"),
                        )
                    } else {
                        IptvTaskResult(message = "${result.channelCount} channels synchronized.")
                    }
                }
            },
            onSyncSource = { source ->
                runTask {
                    val updated = repository.syncSource(source.id)
                    IptvTaskResult(message = "${updated.cachedChannels.size} channels synchronized.")
                }
            },
            onDeleteSource = { sourceToDelete = it },
            onShowLicenses = { showLicensesDialog = true },
        )
    } else {
        val channel = activeChannel!!
        IptvPlayerScreen(
            channel = channel,
            channelIndex = visibleChannels.indexOfFirst { it.id == channel.id },
            channels = visibleChannels,
            onExitPlayer = { activeChannel = null },
            onChannelSelected = { activeChannel = it },
            onPlayerChanged = onPlayerChanged,
        )
    }

    if (showM3uDialog) {
        AddM3uDialog(
            onDismiss = { showM3uDialog = false },
            onSave = { name, url ->
                showM3uDialog = false
                runTask {
                    val source = repository.addM3uUrl(name, url)
                    repository.syncSource(source.id)
                    IptvTaskResult(message = "M3U playlist synchronized.")
                }
            },
        )
    }

    if (showXtreamDialog) {
        AddXtreamDialog(
            onDismiss = { showXtreamDialog = false },
            onConnect = { name, server, username, password ->
                showXtreamDialog = false
                runTask {
                    val source = repository.addXtream(name, server, username, password)
                    repository.syncSource(source.id)
                    IptvTaskResult(message = "Xtream channels synchronized.")
                }
            },
        )
    }

    sourceToDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { sourceToDelete = null },
            title = { Text("Remove ${source.name}?") },
            text = { Text("This removes the saved source, cached channels, and its locally encrypted login data.") },
            confirmButton = {
                TvActionButton(
                    text = "Remove source",
                    onClick = {
                        sourceToDelete = null
                        runTask {
                            repository.deleteSource(source.id)
                            IptvTaskResult(message = "Source removed.")
                        }
                    },
                    modifier = Modifier.padding(8.dp),
                )
            },
            dismissButton = {
                TvActionButton(
                    "Cancel",
                    onClick = { sourceToDelete = null },
                    modifier = Modifier.padding(8.dp),
                )
            },
        )
    }

    if (showLicensesDialog) {
        LicensesDialog(onDismiss = { showLicensesDialog = false })
    }
}

@Composable
private fun IptvHome(
    sources: List<IptvSource>,
    channels: List<IptvChannel>,
    groups: List<String>,
    selectedGroup: String,
    query: String,
    loading: Boolean,
    errorText: String?,
    statusText: String?,
    onExit: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onGroupSelected: (String) -> Unit,
    onPlay: (IptvChannel) -> Unit,
    onAddM3uUrl: () -> Unit,
    onImportFile: () -> Unit,
    onAddXtream: () -> Unit,
    onSyncAll: () -> Unit,
    onSyncSource: (IptvSource) -> Unit,
    onDeleteSource: (IptvSource) -> Unit,
    onShowLicenses: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("IPTV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Sources saved securely on this TV", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvActionButton("Back", onExit)
                TvActionButton("Licenses", onShowLicenses)
            }
            HorizontalDivider(color = Color(0xFF283342))
            TvActionButton("Add M3U URL", onAddM3uUrl, enabled = !loading, modifier = Modifier.fillMaxWidth())
            TvActionButton("Import M3U file", onImportFile, enabled = !loading, modifier = Modifier.fillMaxWidth())
            TvActionButton("Connect Xtream", onAddXtream, enabled = !loading, modifier = Modifier.fillMaxWidth())
            TvActionButton(
                text = if (loading) "Syncing…" else "Sync all sources",
                onClick = onSyncAll,
                enabled = !loading && sources.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                prominent = true,
            )
            Text(
                "Sync refreshes remote M3U and Xtream channels. Local playlists stay on this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (errorText != null) {
                Text(errorText, color = Color(0xFFFFA8A8), style = MaterialTheme.typography.bodySmall)
            } else if (statusText != null) {
                Text(statusText, color = Color(0xFF8AE6D5), style = MaterialTheme.typography.bodySmall)
            }

            Text("PLAYLISTS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sources, key = IptvSource::id) { source ->
                    SourceCard(
                        source = source,
                        onSync = { onSyncSource(source) },
                        onDelete = { onDeleteSource(source) },
                        busy = loading,
                    )
                }
                if (sources.isEmpty()) {
                    item {
                        Text(
                            "Add a playlist or Xtream account to get started.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }

        VerticalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp),
            color = Color(0xFF283342),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Live channels", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("${channels.size} channels", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (loading) CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search channels") },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { group ->
                    TvActionButton(
                        text = group,
                        onClick = { onGroupSelected(group) },
                        selected = selectedGroup == group,
                    )
                }
            }
            HorizontalDivider(color = Color(0xFF283342))
            if (channels.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (sources.isEmpty()) "No playlists yet" else "No channels loaded",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (sources.isEmpty()) {
                            "Add an M3U file, an M3U URL, or Xtream credentials."
                        } else {
                            "Choose Sync all sources to refresh your channel list."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(channels, key = IptvChannel::id) { channel ->
                        ChannelCard(channel = channel, onClick = { onPlay(channel) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: IptvSource,
    onSync: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF151C27))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(source.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${sourceTypeName(source.type)} · ${source.cachedChannels.size} channels",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (source.lastSyncedAt > 0L) {
            Text(
                "Updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(source.lastSyncedAt))}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvActionButton(
                text = if (source.type == IptvSourceType.M3U_FILE) "Reload" else "Sync",
                onClick = onSync,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            TvActionButton(
                text = "Remove",
                onClick = onDelete,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                destructive = true,
            )
        }
    }
}

@Composable
private fun ChannelCard(channel: IptvChannel, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color(0xFF1C3540) else Color(0xFF151C27))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color(0xFF8AE6D5) else Color(0xFF283342),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0xFF263342)),
            contentAlignment = Alignment.Center,
        ) {
            Text(channel.name.take(2).uppercase(), color = Color(0xFF8AE6D5), fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.group.isNotBlank()) {
                Text(channel.group, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Play", color = if (focused) Color.White else Color(0xFF8AE6D5), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun IptvPlayerScreen(
    channel: IptvChannel,
    channelIndex: Int,
    channels: List<IptvChannel>,
    onExitPlayer: () -> Unit,
    onChannelSelected: (IptvChannel) -> Unit,
    onPlayerChanged: (IptvMedia3PlayerView?) -> Unit,
) {
    var playerError by remember(channel.id) { mutableStateOf<String?>(null) }
    BackHandler(onBack = onExitPlayer)
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                IptvMedia3PlayerView(context) { message ->
                    playerError = message
                }.also { player ->
                    onPlayerChanged(player)
                    player.play(channel)
                }
            },
            update = { it.play(channel) },
            onRelease = {
                it.release()
                onPlayerChanged(null)
            },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xE6090D14))
                .padding(horizontal = 28.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(channel.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(channel.group.takeIf(String::isNotBlank), "Media3 · HLS / DASH / TS")
                            .joinToString("  •  "),
                        color = Color(0xFFB9C4D2),
                    )
                    playerError?.let {
                        Text(it, color = Color(0xFFFFA8A8), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvActionButton("Previous", {
                        if (channelIndex > 0) onChannelSelected(channels[channelIndex - 1])
                    }, enabled = channelIndex > 0)
                    TvActionButton("Next", {
                        if (channelIndex >= 0 && channelIndex < channels.lastIndex) {
                            onChannelSelected(channels[channelIndex + 1])
                        }
                    }, enabled = channelIndex >= 0 && channelIndex < channels.lastIndex)
                    TvActionButton("Back to channels", onExitPlayer, prominent = true)
                }
            }
            Text(
                "Press Play/Pause on the remote to control playback. Back returns to channels.",
                color = Color(0xFFB9C4D2),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AddM3uDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, url: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add M3U URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    url,
                    { url = it },
                    label = { Text("Playlist URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (url.startsWith("http://", ignoreCase = true)) {
                    Text(
                        "HTTP is not encrypted. Prefer HTTPS when your provider supports it.",
                        color = Color(0xFFFFC37A),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TvActionButton(
                "Save and sync",
                onClick = { onSave(name, url) },
                enabled = url.isNotBlank(),
                modifier = Modifier.padding(8.dp),
                prominent = true,
            )
        },
        dismissButton = {
            TvActionButton("Cancel", onClick = onDismiss, modifier = Modifier.padding(8.dp))
        },
    )
}

@Composable
private fun AddXtreamDialog(
    onDismiss: () -> Unit,
    onConnect: (name: String, server: String, username: String, password: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("Xtream") }
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Xtream") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    server,
                    { server = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (server.startsWith("http://", ignoreCase = true)) {
                    Text(
                        "HTTP sends your Xtream credentials without encryption. Use HTTPS if available.",
                        color = Color(0xFFFFC37A),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TvActionButton(
                "Connect and sync",
                onClick = { onConnect(name, server, username, password) },
                enabled = server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.padding(8.dp),
                prominent = true,
            )
        },
        dismissButton = {
            TvActionButton("Cancel", onClick = onDismiss, modifier = Modifier.padding(8.dp))
        },
    )
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licenses") },
        text = {
            Text(
                "The IPTV feature is adapted from Nuvio Enhanced contributors and distributed under GNU GPL-3.0-only. " +
                    "It is provided without warranty. You may modify and redistribute it under the license terms. " +
                    "The license notice and official text link are in COPYING; upstream notices are in " +
                    "THIRD_PARTY_NOTICES.md.",
            )
        },
        confirmButton = {
            TvActionButton(
                "View GPL-3.0",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.gnu.org/licenses/gpl-3.0.txt"),
                            ),
                        )
                    }
                    onDismiss()
                },
                modifier = Modifier.padding(8.dp),
            )
        },
        dismissButton = {
            TvActionButton("Close", onClick = onDismiss, modifier = Modifier.padding(8.dp))
        },
    )
}

@Composable
private fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    prominent: Boolean = false,
    destructive: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> Color(0xFF8AE6D5)
        selected -> Color(0xFF5CBDAE)
        else -> Color(0xFF354253)
    }
    val fill = when {
        prominent && enabled -> Color(0xFF2B8579)
        selected -> Color(0xFF203A42)
        else -> Color(0xFF18212D)
    }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .border(
                BorderStroke(if (focused) 3.dp else 1.dp, borderColor),
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> Color(0xFF738092)
                destructive -> Color(0xFFFFB7B7)
                else -> Color.White
            },
            fontWeight = if (focused || prominent || selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class IptvTaskResult(
    val message: String? = null,
    val warning: String? = null,
)

private fun sourceTypeName(type: IptvSourceType): String = when (type) {
    IptvSourceType.M3U_URL -> "M3U URL"
    IptvSourceType.M3U_FILE -> "Local M3U"
    IptvSourceType.XTREAM -> "Xtream"
}

private fun readableError(error: Exception): String = when (error) {
    is IptvRepositoryException -> error.message ?: "IPTV operation failed."
    is IllegalArgumentException -> error.message ?: "Check the entered values."
    else -> "IPTV operation failed. Check the source and network, then try again."
}

private const val ALL_CHANNELS = "All channels"
