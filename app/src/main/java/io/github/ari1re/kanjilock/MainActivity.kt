package io.github.ari1re.kanjilock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        Cards.refresh(this)
    }

    /** Message from the system installer (success / failure), shown as a dialog. */
    private val installMessage = mutableStateOf<String?>(null)
    /** Set when opened from the "update available" notification: download + install right away. */
    private val autoInstall = mutableStateOf(false)
    /** Bumped on every resume so the UI can re-check things like "install unknown apps". */
    private val resumeTick = mutableIntStateOf(0)

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        Scheduler.ensure(this)
        Updater.scheduleChecks(this)
        if (prefs.lockNotification) requestNotifications()
        Cards.refresh(this)
        handleIntent(intent)
        setContent {
            KanjiTheme {
                SettingsScreen(prefs, ::requestNotifications, autoInstall, resumeTick.intValue)
                installMessage.value?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { installMessage.value = null },
                        confirmButton = { TextButton(onClick = { installMessage.value = null }) { Text("OK") } },
                        text = { Text(msg) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        Updater.clearNotifications(this)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Updater.ACTION_INSTALL_STATUS) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm != null) runCatching { startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PackageInstaller.STATUS_SUCCESS ->
                    installMessage.value = "Updated to ${Updater.installedVersionName(this)}."
                PackageInstaller.STATUS_FAILURE_ABORTED ->
                    installMessage.value = "Update cancelled."
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    installMessage.value = "Update failed: the new APK is signed with a different key than the installed app. " +
                        "Uninstall KanjiLock once and install the latest APK from GitHub."
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    installMessage.value = "Update failed (status $status)${if (msg.isNullOrBlank()) "" else ": $msg"}"
                }
            }
            intent.action = null
        } else if (intent.getBooleanExtra(Updater.EXTRA_AUTO_INSTALL, false)) {
            intent.removeExtra(Updater.EXTRA_AUTO_INSTALL)
            autoInstall.value = true
        }
    }
}

private val JA = LocaleList("ja")

@Composable
private fun jaStyle(): TextStyle = LocalTextStyle.current.copy(localeList = JA)

@Composable
fun KanjiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val INTERVALS = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 180 to "3 hours", 360 to "6 hours", 1440 to "Daily")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(prefs: Prefs, requestNotifications: () -> Unit, autoInstall: MutableState<Boolean>, resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var entry by remember { mutableStateOf(Cards.current(ctx)) }
    var levels by remember { mutableStateOf(prefs.levels) }
    var types by remember { mutableStateOf(prefs.types) }
    var source by remember { mutableStateOf(prefs.source) }
    var interval by remember { mutableIntStateOf(prefs.intervalMinutes) }
    var romajiAll by remember { mutableStateOf(prefs.romajiAll) }
    var lockOn by remember { mutableStateOf(prefs.lockNotification) }
    var decks by remember { mutableStateOf(AnkiStore.decks(ctx)) }
    var active by remember { mutableStateOf(prefs.ankiActive) }
    var importing by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var poolSize by remember { mutableIntStateOf(Cards.pool(ctx, prefs).size) }

    /** Re-read the current card after a settings change and push it everywhere. */
    fun sync() {
        val cur = Cards.current(ctx)
        entry = cur
        poolSize = Cards.pool(ctx, prefs).size
        Cards.refresh(ctx, cur)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AnkiStore.import(ctx, uri) } }
            importing = false
            result.onSuccess { meta ->
                decks = AnkiStore.decks(ctx)
                active = prefs.ankiActive + meta.id
                prefs.ankiActive = active
                source = Source.ANKI
                prefs.source = Source.ANKI
                importMsg = "Imported “${meta.name}” — ${meta.noteCount} notes."
                withContext(Dispatchers.IO) { Cards.pool(ctx, prefs) } // warm the cache
                sync()
            }.onFailure { t ->
                importMsg = (t as? AnkiStore.ImportException)?.message ?: "Import failed: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("KanjiLock", style = MaterialTheme.typography.headlineMedium)

            CardPreview(entry, romajiAll, onNext = { entry = Cards.advance(ctx) })

            Section("Cards from") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = source == Source.DICTIONARY,
                        onClick = { source = Source.DICTIONARY; prefs.source = source; sync() },
                        label = { Text("JLPT dictionary") },
                    )
                    FilterChip(
                        selected = source == Source.ANKI,
                        enabled = decks.isNotEmpty(),
                        onClick = { source = Source.ANKI; prefs.source = source; sync() },
                        label = { Text("My Anki decks") },
                    )
                }
                Text(
                    "$poolSize cards in rotation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (source == Source.DICTIONARY || decks.isEmpty()) {
                Section("Level") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 4, 3, 2, 1).forEach { lv ->
                            FilterChip(
                                selected = lv in levels,
                                onClick = {
                                    val n = if (lv in levels) levels - lv else levels + lv
                                    if (n.isNotEmpty()) { levels = n; prefs.levels = n; sync() }
                                },
                                label = { Text("N$lv") },
                            )
                        }
                    }
                }
                Section("Show me") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EntryType.dictionaryTypes.forEach { t ->
                            FilterChip(
                                selected = t in types,
                                onClick = {
                                    val n = if (t in types) types - t else types + t
                                    if (n.isNotEmpty()) { types = n; prefs.types = n; sync() }
                                },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            }

            Section("Anki decks") {
                Text(
                    "Import an .apkg exported from Anki or AnkiDroid. Turn on “My Anki decks” above to show random cards from the decks you tick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                decks.forEach { deck ->
                    DeckCard(
                        deck = deck,
                        active = deck.id in active || active.isEmpty(),
                        onToggle = { on ->
                            val base = if (active.isEmpty()) decks.map { it.id }.toSet() else active
                            val n = if (on) base + deck.id else base - deck.id
                            active = n; prefs.ankiActive = n; sync()
                        },
                        onMapping = { ntId, map ->
                            AnkiStore.setMapping(ctx, deck.id, ntId, map)
                            decks = AnkiStore.decks(ctx)
                            sync()
                        },
                        onDelete = {
                            AnkiStore.delete(ctx, deck.id)
                            decks = AnkiStore.decks(ctx)
                            active = active - deck.id; prefs.ankiActive = active
                            if (decks.isEmpty()) { source = Source.DICTIONARY; prefs.source = source }
                            sync()
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !importing, onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text("Import .apkg")
                    }
                    if (importing) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            Section("Romaji under") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !romajiAll, onClick = { romajiAll = false; prefs.romajiAll = false; sync() }, label = { Text("Kanji only") })
                    FilterChip(selected = romajiAll, onClick = { romajiAll = true; prefs.romajiAll = true; sync() }, label = { Text("Every word") })
                }
            }

            Section("New card every") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    INTERVALS.forEach { (m, label) ->
                        FilterChip(
                            selected = interval == m,
                            onClick = { interval = m; prefs.intervalMinutes = m; Scheduler.ensure(ctx, replace = true) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            Section("Lock screen") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Card on the lock screen", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "A silent card notification. Works on every phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = lockOn, onCheckedChange = {
                        lockOn = it; prefs.lockNotification = it
                        if (it) requestNotifications()
                        Cards.refresh(ctx, entry)
                    })
                }
                Text(
                    "Widget: long-press your home screen → Widgets → KanjiLock. On Android 16+ phones with " +
                        "“Widgets on lock screen” (Settings → Display → Lock screen), swipe left on the lock " +
                        "screen and add KanjiLock there too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Updates") { UpdatesSection(prefs, autoInstall, resumeTick) }
        }
    }

    importMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { importMsg = null },
            confirmButton = { TextButton(onClick = { importMsg = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardPreview(entry: Entry, romajiAll: Boolean, onNext: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.text,
                    style = jaStyle(),
                    fontSize = when {
                        entry.text.length <= 1 -> 64.sp
                        entry.text.length <= 3 -> 40.sp
                        else -> 26.sp
                    },
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    if (entry.reading.isNotBlank()) Text(entry.reading, style = jaStyle())
                    if (entry.romaji.isNotBlank()) Text(
                        entry.romaji,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.meaning.isNotBlank()) Text(entry.meaning, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (entry.example.isNotEmpty()) {
                FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    entry.example.forEach { t ->
                        val show = t.romaji.isNotEmpty() && (romajiAll || t.hasKanji)
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 3.dp)) {
                            Text(t.surface, style = jaStyle(), fontSize = 20.sp)
                            Text(
                                if (show) t.romaji else " ",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (entry.exampleEn.isNotBlank()) Text(
                entry.exampleEn,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Button(onClick = onNext) { Text("Next") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckCard(
    deck: AnkiDeckMeta,
    active: Boolean,
    onToggle: (Boolean) -> Unit,
    onMapping: (Long, FieldMap) -> Unit,
    onDelete: () -> Unit,
) {
    var showFields by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(deck.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${deck.noteCount} notes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = active, onCheckedChange = onToggle)
            }
            Row {
                TextButton(onClick = { showFields = !showFields }) { Text(if (showFields) "Hide fields" else "Fields…") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = true }) { Text("Remove") }
            }
            if (showFields) {
                deck.noteTypes.forEach { nt ->
                    HorizontalDivider()
                    Text(
                        "${nt.name} (${nt.noteCount})",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Role.entries.forEach { role ->
                        Text(role.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (role != Role.FRONT) FilterChip(
                                selected = nt.map.get(role) < 0,
                                onClick = { onMapping(nt.id, nt.map.with(role, -1)) },
                                label = { Text("—") },
                            )
                            nt.fields.forEachIndexed { i, f ->
                                FilterChip(
                                    selected = nt.map.get(role) == i,
                                    onClick = { onMapping(nt.id, nt.map.with(role, i)) },
                                    label = { Text(f) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            text = { Text("Remove “${deck.name}” from KanjiLock? Your Anki collection isn't touched.") },
        )
    }
}


private sealed interface Upd {
    data object Idle : Upd
    data object Checking : Upd
    data object UpToDate : Upd
    data class Available(val rel: Updater.Release) : Upd
    data object Downloading : Upd
    data object Installing : Upd
    data class Failed(val msg: String) : Upd
}

@Composable
private fun UpdatesSection(prefs: Prefs, autoInstall: MutableState<Boolean>, resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Upd>(Upd.Idle) }
    var progress by remember { mutableFloatStateOf(0f) }
    var token by remember { mutableStateOf(prefs.githubToken.orEmpty()) }
    var canInstall by remember { mutableStateOf(Updater.canInstall(ctx)) }

    LaunchedEffect(resumeTick) { canInstall = Updater.canInstall(ctx) }

    fun openInstallSettings() {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(rel: Updater.Release) {
        if (!Updater.canInstall(ctx)) { openInstallSettings(); return }
        state = Upd.Downloading
        progress = 0f
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    var last = -1f
                    val apk = Updater.download(ctx, rel) { p ->
                        if (p < 0f || p - last >= 0.01f || p >= 1f) { last = p; progress = p }
                    }
                    Updater.install(ctx, apk)
                }
            }
            state = r.fold({ Upd.Installing }, { Upd.Failed(it.message ?: it.javaClass.simpleName) })
        }
    }

    fun check(silent: Boolean, thenInstall: Boolean = false) {
        state = Upd.Checking
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.check(ctx) } }
            r.onSuccess { rel ->
                state = if (rel == null) Upd.UpToDate else Upd.Available(rel)
                if (rel != null && thenInstall) install(rel)
            }.onFailure {
                state = if (silent) Upd.Idle else Upd.Failed(it.message ?: it.javaClass.simpleName)
            }
        }
    }

    LaunchedEffect(Unit) { check(silent = true) }
    LaunchedEffect(autoInstall.value) {
        if (autoInstall.value) {
            autoInstall.value = false
            check(silent = false, thenInstall = true)
        }
    }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        "Installed: ${Updater.installedVersionName(ctx)} (build ${Updater.installedVersionCode(ctx)})",
        style = MaterialTheme.typography.bodyMedium,
    )
    when (val s = state) {
        Upd.Idle -> {}
        Upd.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
        Upd.UpToDate -> Text("You're on the latest build.", style = MaterialTheme.typography.bodySmall, color = muted)
        is Upd.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Build ${s.rel.versionCode} is ready", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { install(s.rel) }) { Text("Install update") }
        }
        Upd.Downloading -> if (progress < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Upd.Installing -> Text(
            "Installing… confirm on the system prompt if one appears. KanjiLock restarts when it's done.",
            style = MaterialTheme.typography.bodySmall, color = muted,
        )
        is Upd.Failed -> Text(s.msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (!canInstall) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Allow KanjiLock to install updates",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = muted,
            )
            TextButton(onClick = ::openInstallSettings) { Text("Allow") }
        }
    }
    OutlinedButton(
        enabled = state != Upd.Checking && state != Upd.Downloading,
        onClick = { check(silent = false) },
    ) { Text("Check for updates") }
    OutlinedTextField(
        value = token,
        onValueChange = { token = it; prefs.githubToken = it.trim().ifEmpty { null } },
        label = { Text("GitHub token (private repo only)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Updates come from ${Updater.OWNER}/${Updater.REPO} → Releases → latest. For a private repo, paste a " +
            "fine-grained token with read-only “Contents” access to that repo.",
        style = MaterialTheme.typography.bodySmall, color = muted,
    )
}
