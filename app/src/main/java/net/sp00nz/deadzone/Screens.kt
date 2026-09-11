package net.sp00nz.deadzone

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A deadzone is dark. Amber is the only thing lit.
private val Amber = Color(0xFFF2B441)
private val Ink = Color(0xFF101215)
private val Panel = Color(0xFF181B20)
private val Muted = Color(0xFF8A9199)

private val Scheme = darkColorScheme(
    primary = Amber, onPrimary = Ink,
    background = Ink, onBackground = Color(0xFFE6E9EC),
    surface = Panel, onSurface = Color(0xFFE6E9EC),
    surfaceVariant = Panel, onSurfaceVariant = Muted,
    secondary = Amber, error = Color(0xFFE06C5A),
)

@Composable
fun App(vm: Vm) = MaterialTheme(colorScheme = Scheme) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.status) {
        vm.status?.let { snackbar.showSnackbar(it); vm.status = null }
    }

    // One back key, one place that decides what it closes.
    BackHandler(enabled = vm.showSettings || vm.showPlayer || vm.openFeed != null) {
        when {
            vm.showSettings -> vm.showSettings = false
            vm.showPlayer -> vm.showPlayer = false
            else -> { vm.openFeed = null; vm.reload() }
        }
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                vm.nowPlaying?.let { MiniPlayer(vm, it) }
                NavBar(vm)
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                vm.openFeed != null -> FeedScreen(vm, vm.openFeed!!)
                vm.tab == Tab.LIBRARY -> LibraryScreen(vm)
                vm.tab == Tab.LATEST -> LatestScreen(vm)
                vm.tab == Tab.SEARCH -> SearchScreen(vm)
                else -> HistoryScreen(vm)
            }
        }
    }

    if (vm.showPlayer) vm.nowPlaying?.let { PlayerSheet(vm, it) }
    if (vm.showSettings) SettingsSheet(vm)
}

@Composable
private fun NavBar(vm: Vm) = NavigationBar(containerColor = Panel) {
    val tabs = listOf(
        Tab.LIBRARY to Icons.Default.GridView,
        Tab.LATEST to Icons.Default.NewReleases,
        Tab.SEARCH to Icons.Default.Search,
        Tab.HISTORY to Icons.Default.History,
    )
    for ((t, icon) in tabs) {
        NavigationBarItem(
            selected = vm.openFeed == null && vm.tab == t,
            onClick = { vm.tab = t; vm.openFeed = null; vm.reload() },
            icon = { Icon(icon, t.name) },
            label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Ink, indicatorColor = Amber, unselectedIconColor = Muted,
                selectedTextColor = Amber, unselectedTextColor = Muted,
            ),
        )
    }
}

// ---- library ----

@Composable
private fun LibraryScreen(vm: Vm) {
    var adding by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importOpml) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml")
    ) { uri -> uri?.let(vm::exportOpml) }

    Column {
        TopBar(
            title = "Library",
            subtitle = "${vm.feeds.size} feeds · ${vm.feeds.sumOf { it.unplayed }} unplayed",
        ) {
            IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add feed") }
            IconButton(onClick = vm::sync, enabled = !vm.syncing) {
                if (vm.syncing) CircularProgressIndicator(Modifier.size(20.dp), Amber, strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, "Refresh")
            }
            IconButton(onClick = { vm.showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
        }

        if (vm.feeds.isEmpty()) {
            Empty(
                "Nothing in here yet.",
                "Add an RSS URL, or import an OPML file and get your whole list at once.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ adding = true }) { Text("Add a feed") }
                    OutlinedButton({ importer.launch(arrayOf("*/*")) }) { Text("Import OPML") }
                }
            }
        } else {
            // Grouped by OPML folder / itunes category, so a long list has landmarks.
            val groups = vm.feeds.groupBy { it.category ?: "Uncategorised" }.toSortedMap()
            LazyColumn(Modifier.fillMaxSize()) {
                for ((category, feeds) in groups) {
                    if (groups.size > 1) item(key = "h$category") {
                        Text(
                            category.uppercase(), Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                            color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    items(feeds, key = { it.id }) { FeedRow(vm, it) }
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton({ importer.launch(arrayOf("*/*")) }) { Text("Import OPML") }
                        OutlinedButton({ exporter.launch("deadzone.opml") }) { Text("Export") }
                    }
                }
            }
        }
    }

    if (adding) AddFeedDialog(onDismiss = { adding = false }) { vm.addFeed(it); adding = false }
}

@Composable
private fun FeedRow(vm: Vm, feed: Feed) = Row(
    Modifier.fillMaxWidth().clickable { vm.openFeed = feed; vm.reload() }.padding(16.dp, 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Art(feed.image, 52.dp)
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
        Text(feed.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            buildString {
                append("${feed.total} episodes")
                if (feed.downloaded > 0) append(" · ${feed.downloaded} on device")
                if (feed.keep > 0) append(" · auto ${feed.keep}")
            },
            color = Muted, fontSize = 12.sp,
        )
    }
    if (feed.unplayed > 0) Badge(containerColor = Amber, contentColor = Ink) {
        Text("${feed.unplayed}")
    }
}

// ---- episodes ----

@Composable
private fun FeedScreen(vm: Vm, feed: Feed) {
    var menu by remember { mutableStateOf(false) }
    Column {
        TopBar(feed.title, "${feed.total} episodes · ${feed.unplayed} unplayed", onBack = {
            vm.openFeed = null; vm.reload()
        }) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                Text("Auto-download", Modifier.padding(16.dp, 8.dp), color = Muted, fontSize = 12.sp)
                for (n in listOf(0, 1, 3, 5, 10)) {
                    DropdownMenuItem(
                        text = { Text(if (n == 0) "Off" else "Newest $n") },
                        trailingIcon = { if (feed.keep == n) Icon(Icons.Default.Check, null, tint = Amber) },
                        onClick = { vm.setKeep(feed, n); menu = false },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Unsubscribe", color = MaterialTheme.colorScheme.error) },
                    onClick = { vm.removeFeed(feed); menu = false },
                )
            }
        }
        FilterChips(vm)
        EpisodeList(vm, vm.episodes, showFeed = false)
    }
}

@Composable
private fun LatestScreen(vm: Vm) = Column {
    TopBar("Latest", "across ${vm.feeds.size} feeds") {
        IconButton(onClick = vm::sync, enabled = !vm.syncing) {
            if (vm.syncing) CircularProgressIndicator(Modifier.size(20.dp), Amber, strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, "Refresh")
        }
    }
    FilterChips(vm)
    EpisodeList(vm, vm.episodes, showFeed = true)
}

@Composable
private fun SearchScreen(vm: Vm) = Column {
    OutlinedTextField(
        value = vm.query,
        onValueChange = vm::search,
        placeholder = { Text("Search every episode", color = Muted) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
        trailingIcon = {
            if (vm.query.isNotEmpty()) IconButton({ vm.search("") }) {
                Icon(Icons.Default.Close, "Clear", tint = Muted)
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    )
    if (vm.query.isBlank()) {
        Empty("Titles and show notes.", "Full-text, on the phone, with no network.") {}
    } else if (vm.results.isEmpty()) {
        Empty("No matches.", "Nothing in ${vm.feeds.size} feeds mentions that.") {}
    } else {
        Text(
            "${vm.results.size} matches", Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp),
            color = Muted, fontSize = 12.sp,
        )
        EpisodeList(vm, vm.results, showFeed = true)
    }
}

@Composable
private fun HistoryScreen(vm: Vm) = Column {
    TopBar("History", "${vm.history.size} finished")
    if (vm.history.isEmpty()) Empty("Nothing finished yet.", "Episodes land here once you reach the end.") {}
    else EpisodeList(vm, vm.history, showFeed = true, showPlayedDate = true)
}

@Composable
private fun FilterChips(vm: Vm) = Row(
    Modifier.horizontalScroll(rememberScrollState()).padding(12.dp, 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    for (f in Filter.entries) {
        FilterChip(
            selected = vm.filter == f,
            onClick = { vm.filter = f; vm.reload() },
            label = { Text(f.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Amber, selectedLabelColor = Ink, labelColor = Muted,
            ),
        )
    }
}

@Composable
private fun EpisodeList(
    vm: Vm,
    list: List<Episode>,
    showFeed: Boolean,
    showPlayedDate: Boolean = false,
) {
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { EpisodeRow(vm, it, showFeed, showPlayedDate) }
            item { Spacer(Modifier.height(80.dp)) }
        }
        // Flicking through 23k rows with a finger is not a plan, so the rail drags
        // the whole range and shows you the month you're passing through.
        if (list.size > 40) FastScroller(state, list.size) { i ->
            list.getOrNull(i)?.published?.takeIf { it > 0 }?.let { month.format(Date(it)) }.orEmpty()
        }
    }
}

@Composable
private fun EpisodeRow(vm: Vm, e: Episode, showFeed: Boolean, showPlayedDate: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val progress = vm.downloading[e.id]
    val isCurrent = vm.nowPlaying?.id == e.id

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (isCurrent) Panel else Color.Transparent)
            .clickable { vm.play(e) }
            .padding(16.dp, 10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (showFeed) Text(
                    e.feedTitle, color = Amber, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    e.title,
                    fontWeight = if (e.playedAt > 0) FontWeight.Normal else FontWeight.Medium,
                    color = if (e.playedAt > 0) Muted else MaterialTheme.colorScheme.onBackground,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(if (showPlayedDate && e.playedAt > 0) "Played ${day.format(Date(e.playedAt))}"
                        else if (e.published > 0) day.format(Date(e.published)) else "—")
                        if (e.duration > 0) append(" · ${hms(e.duration.toLong() * 1000)}")
                        if (e.downloaded) append(" · on device")
                        if (e.position > 0 && e.playedAt == 0L) append(" · ${hms(e.position)} in")
                    },
                    color = Muted, fontSize = 11.sp,
                )
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Muted) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Play") }, { vm.play(e); menu = false })
                    DropdownMenuItem({ Text("Add to queue") }, { vm.enqueue(e); menu = false })
                    DropdownMenuItem(
                        { Text(if (e.downloaded) "Delete download" else "Download") },
                        { if (e.downloaded) vm.deleteDownload(e) else vm.download(e); menu = false },
                    )
                    DropdownMenuItem(
                        { Text(if (e.playedAt > 0) "Mark unplayed" else "Mark played") },
                        { vm.markPlayed(e, e.playedAt == 0L); menu = false },
                    )
                }
            }
        }
        if (progress != null) LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp),
            color = Amber, trackColor = Panel,
        ) else if (e.position > 0 && e.playedAt == 0L && e.duration > 0) LinearProgressIndicator(
            progress = { (e.position / (e.duration * 1000f)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp),
            color = Amber.copy(alpha = .6f), trackColor = Panel,
        )
    }
    HorizontalDivider(color = Panel)
}

/** A drag rail for lists that are far too long to flick. */
@Composable
private fun BoxScope.FastScroller(state: LazyListState, count: Int, labelAt: (Int) -> String) {
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    // While dragging we own the position; otherwise we follow the list.
    val shown = if (dragging) fraction else {
        val first = state.firstVisibleItemIndex.toFloat()
        if (count > 1) (first / (count - 1)).coerceIn(0f, 1f) else 0f
    }

    // Wide enough for the date bubble to sit inside it: a narrower box clamps the
    // label to the rail's width and wraps it one character per line.
    BoxWithConstraints(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(160.dp)
    ) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxHeight.toPx() }
        val travel = (trackPx - with(density) { THUMB.toPx() }).coerceAtLeast(1f)
        val offsetY = with(density) { (shown * travel).toDp() }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = offsetY)
                .padding(end = 4.dp)
                .size(8.dp, THUMB)
                .clip(RoundedCornerShape(4.dp))
                .background(if (dragging) Amber else Muted.copy(alpha = .4f))
                .pointerInput(count) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dy ->
                        change.consume()
                        fraction = (fraction + dy / travel).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem(((count - 1) * fraction).toInt()) }
                    }
                }
        )

        if (dragging) {
            val label = labelAt(((count - 1) * fraction).toInt())
            if (label.isNotEmpty()) Surface(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = offsetY + (THUMB - 30.dp) / 2)
                    .padding(end = 24.dp),
                shape = RoundedCornerShape(6.dp), color = Amber,
            ) {
                Text(
                    label, Modifier.padding(12.dp, 6.dp), color = Ink, fontSize = 14.sp,
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

private val THUMB = 100.dp

// ---- player ----

@Composable
private fun MiniPlayer(vm: Vm, e: Episode) = Surface(
    Modifier.fillMaxWidth().clickable { vm.showPlayer = true }, color = Panel
) {
    Column {
        LinearProgressIndicator(
            progress = { if (vm.duration > 0) (vm.position.toFloat() / vm.duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = Amber, trackColor = Ink,
        )
        Row(Modifier.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(e.image, 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                Text(e.feedTitle, color = Muted, fontSize = 11.sp, maxLines = 1)
            }
            IconButton(vm::toggle) {
                Icon(
                    if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/pause", tint = Amber, modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(vm: Vm, e: Episode) = ModalBottomSheet(
    onDismissRequest = { vm.showPlayer = false },
    containerColor = Panel,
    // Without this the sheet opens half-height and the transport controls sit below
    // the fold — the one thing you opened it for.
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(24.dp, 0.dp, 24.dp, 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Art(e.image, 180.dp)
        Spacer(Modifier.height(18.dp))
        Text(e.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 3)
        Text(e.feedTitle, color = Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))

        Slider(
            value = if (vm.duration > 0) (vm.position.toFloat() / vm.duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { vm.seekTo((it * vm.duration).toLong()) },
            colors = SliderDefaults.colors(
                thumbColor = Amber, activeTrackColor = Amber,
                inactiveTrackColor = Muted.copy(alpha = .25f),
            ),
            modifier = Modifier.padding(top = 20.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(hms(vm.position), color = Muted, fontSize = 12.sp)
            Text("-${hms((vm.duration - vm.position).coerceAtLeast(0))}", color = Muted, fontSize = 12.sp)
        }

        Row(
            Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton({ vm.skip(-15_000) }) { Icon(Icons.Default.Replay, "Back 15s", tint = Muted) }
            FilledIconButton(
                vm::toggle, Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Amber),
            ) {
                Icon(
                    if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/pause", tint = Ink, modifier = Modifier.size(32.dp),
                )
            }
            IconButton({ vm.skip(30_000) }) { Icon(Icons.Default.FastForward, "Forward 30s", tint = Muted) }
        }

        Row(
            Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val speeds = listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f)
            AssistChip(
                onClick = { vm.setSpeed(speeds[(speeds.indexOf(vm.settings.speed) + 1) % speeds.size]) },
                label = { Text("${vm.settings.speed}x") },
            )
            AssistChip(
                onClick = { vm.sleepTimer(if (vm.sleepMinutes > 0) 0 else 30) },
                leadingIcon = { Icon(Icons.Default.Bedtime, null, Modifier.size(16.dp)) },
                label = { Text(if (vm.sleepMinutes > 0) "${vm.sleepMinutes}m" else "Sleep") },
            )
            AssistChip(
                onClick = { if (e.downloaded) vm.deleteDownload(e) else vm.download(e) },
                leadingIcon = {
                    Icon(
                        if (e.downloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                        null, Modifier.size(16.dp),
                    )
                },
                label = { Text(if (e.downloaded) "On device" else "Download") },
            )
        }

        if (e.description.isNotBlank()) Text(
            stripHtml(e.description), Modifier.padding(top = 20.dp),
            color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
        )
    }
}

// ---- settings ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(vm: Vm) = ModalBottomSheet(
    onDismissRequest = { vm.showSettings = false },
    containerColor = Panel,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var wifiOnly by remember { mutableStateOf(vm.settings.wifiOnly) }
    var days by remember { mutableStateOf(vm.settings.autoDeleteDays) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(24.dp, 0.dp, 24.dp, 40.dp)
    ) {
        Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Medium)

        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Download on Wi-Fi only")
                Text("Background syncs wait for an unmetered network", color = Muted, fontSize = 12.sp)
            }
            Switch(wifiOnly, {
                wifiOnly = it
                vm.settings.wifiOnly = it
                SyncWorker.schedule(vm.getApplication(), it)
            })
        }

        Text("Delete finished downloads after", Modifier.padding(top = 24.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in listOf(0, 7, 30, 90)) FilterChip(
                selected = days == d,
                onClick = { days = d; vm.settings.autoDeleteDays = d },
                label = { Text(if (d == 0) "Never" else "$d days") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Amber, selectedLabelColor = Ink, labelColor = Muted,
                ),
            )
        }

        Text("Sideloaded audio", Modifier.padding(top = 24.dp))
        Text(
            "Adopt files pushed by tools/sideload.py instead of downloading them again",
            color = Muted, fontSize = 12.sp,
        )
        OutlinedButton(vm::adoptSideloaded, Modifier.padding(top = 8.dp)) {
            Text("Adopt sideloaded files")
        }

        val onDevice = vm.feeds.sumOf { it.downloaded }
        Text(
            "$onDevice episode${if (onDevice == 1) "" else "s"} on device",
            Modifier.padding(top = 24.dp), color = Muted, fontSize = 13.sp,
        )
        Text("Deadzone 0.1.0", Modifier.padding(top = 16.dp), color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun AddFeedDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Add a feed") },
        text = {
            OutlinedTextField(
                url, { url = it }, singleLine = true,
                placeholder = { Text("https://…/feed.xml", color = Muted) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
        },
        confirmButton = {
            TextButton({ onAdd(url) }, enabled = url.isNotBlank()) { Text("Add", color = Amber) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Muted) } },
    )
}

// ---- bits ----

@Composable
private fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) = Row(
    Modifier.fillMaxWidth().padding(4.dp, 8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    Column(Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 0.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = Muted, fontSize = 12.sp) }
    }
    actions()
}

@Composable
private fun Art(url: String?, size: androidx.compose.ui.unit.Dp) = Box(
    Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Panel),
    contentAlignment = Alignment.Center,
) {
    if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Icon(Icons.Default.Podcasts, null, tint = Muted, modifier = Modifier.size(size / 2))
}

@Composable
private fun Empty(title: String, body: String, action: @Composable () -> Unit) = Column(
    Modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    Icon(Icons.Default.SignalCellularOff, null, tint = Muted, modifier = Modifier.size(40.dp))
    Text(title, Modifier.padding(top = 16.dp), fontSize = 16.sp)
    Text(body, Modifier.padding(top = 6.dp), color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(20.dp))
    action()
}

private val day = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private val month = SimpleDateFormat("MMM yyyy", Locale.getDefault())

fun hms(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
