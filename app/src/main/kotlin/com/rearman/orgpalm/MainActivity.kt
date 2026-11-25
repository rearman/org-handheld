package com.rearman.orgpalm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rearman.orgpalm.org.OrgFile
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent { OrgPalmTheme { App() } }
	}
}

@Composable
private fun OrgPalmTheme(content: @Composable () -> Unit) {
	val dark = isSystemInDarkTheme()
	val context = LocalContext.current
	val colors = when {
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
		dark -> darkColorScheme()
		else -> lightColorScheme()
	}
	MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(model: AppModel = viewModel()) {
	val folders by model.folders.collectAsStateWithLifecycle()
	val loaded by model.loaded.collectAsStateWithLifecycle()
	val files = remember(loaded) { loaded.map { it.file } }
	val error by model.error.collectAsStateWithLifecycle()
	val filters by model.filters.collectAsStateWithLifecycle()
	var showSettings by rememberSaveable { mutableStateOf(false) }
	var level by rememberSaveable { mutableStateOf(Level.AGENDA) }
	var focus by rememberSaveable { mutableStateOf(LocalDate.now()) }
	var editing by remember { mutableStateOf<Place?>(null) }
	var capturing by remember { mutableStateOf(false) }
	var filter by rememberSaveable { mutableStateOf<String?>(null) }
	var reveal by remember { mutableStateOf<Place?>(null) }
	val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { model.addFolder(it) } }

	// Alarms post through a notification, which API 33 & up asks the user to allow.
	val context = LocalContext.current
	val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
	LaunchedEffect(Unit) {
		val asks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
		if (asks && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			notify.launch(Manifest.permission.POST_NOTIFICATIONS)
		}
	}

	// On start & on each return to the front.
	LifecycleEventEffect(Lifecycle.Event.ON_START) { model.walk() }

	val settingsShown = showSettings || folders.isEmpty()
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(if (settingsShown) "Settings" else "org-palm") },
				actions = {
					if (!settingsShown) ViewsMenu(filter, filters) { filter = it; reveal = null }
					TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Done" else "Settings") }
				},
			)
		},
		floatingActionButton = {
			if (!settingsShown) FloatingActionButton(onClick = { capturing = true }) { Text("+", style = MaterialTheme.typography.titleLarge) }
		},
	) { padding ->
		Column(Modifier.padding(padding).fillMaxSize()) {
			error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
			if (settingsShown) {
				SettingsScreen(model, folders) { pick.launch(null) }
			} else {
				val open = { row: AgendaRow -> editing = Place(row.file, row.entry) }
				val show = { row: AgendaRow -> reveal = Place(row.file, row.entry); filter = OUTLINE }
				val saved = filters.firstOrNull { FILTER + it.name == filter }
				when {
					filter == EDIT_FILTERS -> FilterEditor(model) { filter = null }
					filter == OUTLINE -> OutlineTree(files, Modifier.fillMaxSize(), null, bodies = true, reveal = reveal) { file, e -> editing = Place(file, e) }
					saved != null -> FilterView(files, saved, open, show)
					else -> Timeline(files, level, { level = it }, focus, { focus = it }, open, show)
				}
			}
		}
	}

	editing?.let { EntrySheet(model, it, null) { editing = null } }
	if (capturing) EntrySheet(model, null, findTarget(model)) { capturing = false }
}

private val DAY = DateTimeFormatter.ofPattern("EEEE")
private val DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun AgendaList(agenda: Agenda, onOpen: (AgendaRow) -> Unit, onShow: (AgendaRow) -> Unit) {
	LazyColumn(Modifier.fillMaxSize(), rememberCenteredListState()) {
		for (day in agenda.days) {
			item(key = day.date.toString()) {
				Header("${DAY.format(day.date)}, ${DATE.format(day.date)}")
			}
			items(day.rows) { AgendaLine(it, onOpen, onShow) }
		}
		item(key = "todo") { Header("All TODO entries") }
		items(agenda.todos) { AgendaLine(it, onOpen, onShow) }
	}
}

@Composable
private fun Header(text: String) {
	Column {
		HorizontalDivider()
		Text(
			text,
			Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
			style = MaterialTheme.typography.titleSmall,
			color = MaterialTheme.colorScheme.primary,
		)
	}
}

// Time, leaders, state, priority, title, tags (own, then inherited), & category. A tap opens the sheet,
// & a long press shows the entry in the outline. Each row sits in a bubble of the colors of the Day &
// Week grids: a timed row's, else an untimed row's.
@Composable
private fun AgendaLine(row: AgendaRow, onOpen: (AgendaRow) -> Unit, onShow: (AgendaRow) -> Unit) {
	val e = row.entry
	val muted = MaterialTheme.colorScheme.onSurfaceVariant
	val colors = MaterialTheme.colorScheme
	val (fill, ink) = if (row.time != null) colors.primaryContainer to colors.onPrimaryContainer else colors.secondaryContainer to colors.onSecondaryContainer
	CompositionLocalProvider(LocalContentColor provides ink) {
		Column(
			Modifier
				.fillMaxWidth()
				.padding(horizontal = 8.dp, vertical = 2.dp)
				.clip(RoundedCornerShape(4.dp))
				.combinedClickable(onLongClick = { onShow(row) }) { onOpen(row) }
				.background(fill)
				.padding(horizontal = 8.dp, vertical = 4.dp),
		) {
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				row.time?.let { Text(TIME.format(it), color = muted) }
				for (leader in row.leaders) Text(leader, color = MaterialTheme.colorScheme.error)
				e.state?.let { Text(it, fontWeight = FontWeight.Bold, color = if (e.done) muted else MaterialTheme.colorScheme.tertiary) }
				e.priority?.let { Text("[#$it]", color = MaterialTheme.colorScheme.secondary) }
				Text(e.title, Modifier.weight(1f))
			}
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(e.category, style = MaterialTheme.typography.labelMedium, color = muted)
				if (e.tags.isNotEmpty()) Text(e.tags.joinToString(":", ":", ":"), style = MaterialTheme.typography.labelMedium)
				if (e.inheritedTags.isNotEmpty()) {
					Text(e.inheritedTags.joinToString(":", ":", ":"), style = MaterialTheme.typography.labelMedium, color = muted)
				}
			}
		}
	}
}

// A saved filter's blocks, each under its header; a byTag block heads each tag's rows w/ the tag.
@Composable
fun FilterView(files: List<OrgFile>, filter: SavedFilter, onOpen: (AgendaRow) -> Unit, onShow: (AgendaRow) -> Unit) {
	val today = LocalDate.now()
	val blocks = remember(files, filter, today) { filter.blocks.map { Filters.rows(it, files, today) } }
	LazyColumn(Modifier.fillMaxSize(), rememberCenteredListState()) {
		blocks.forEachIndexed { i, b ->
			item(key = "block$i") { Header(b.block.header) }
			var last: String? = null
			for ((n, pair) in b.rows.withIndex()) {
				val (group, row) = pair
				if (b.block.byTag && (n == 0 || group != last)) {
					val label = group ?: "(no tag)"
					item(key = "block$i-group$n") {
						Text(label, Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)
					}
				}
				last = group
				item(key = "block$i-row$n") { AgendaLine(row, onOpen, onShow) }
			}
		}
	}
}

// The views of the overflow menu besides the timeline; a saved filter goes by FILTER & its name, so
// that no filter's name can clash w/ another view.
private const val OUTLINE = "Outline"
private const val EDIT_FILTERS = "Edit filters"
private const val FILTER = "filter:"

// The overflow menu: the timeline, the outline browser, a saved filter, or the filter editor.
@Composable
private fun ViewsMenu(current: String?, filters: List<SavedFilter>, onPick: (String?) -> Unit) {
	var open by remember { mutableStateOf(false) }
	TextButton(onClick = { open = true }) { Text(current?.removePrefix(FILTER) ?: "Timeline") }
	DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
		DropdownMenuItem(text = { Text("Timeline") }, onClick = { onPick(null); open = false })
		DropdownMenuItem(text = { Text(OUTLINE) }, onClick = { onPick(OUTLINE); open = false })
		for (f in filters) DropdownMenuItem(text = { Text(f.name) }, onClick = { onPick(FILTER + f.name); open = false })
		DropdownMenuItem(text = { Text(EDIT_FILTERS) }, onClick = { onPick(EDIT_FILTERS); open = false })
	}
}
