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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
	var showSettings by rememberSaveable { mutableStateOf(false) }
	var level by rememberSaveable { mutableStateOf(Level.AGENDA) }
	var focus by rememberSaveable { mutableStateOf(LocalDate.now()) }
	var editing by remember { mutableStateOf<Place?>(null) }
	var capturing by remember { mutableStateOf(false) }
	var filter by rememberSaveable { mutableStateOf<String?>(null) }
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
					if (!settingsShown) ViewsMenu(filter) { filter = it }
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
				val saved = Filters.STARTING.firstOrNull { it.name == filter }
				if (saved != null) FilterView(files, saved, open) else Timeline(files, level, { level = it }, focus, { focus = it }, open)
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
fun AgendaList(agenda: Agenda, onOpen: (AgendaRow) -> Unit) {
	LazyColumn(Modifier.fillMaxSize()) {
		for (day in agenda.days) {
			item(key = day.date.toString()) {
				Header("${DAY.format(day.date)}, ${DATE.format(day.date)}")
			}
			items(day.rows) { AgendaLine(it, onOpen) }
		}
		item(key = "todo") { Header("All TODO entries") }
		items(agenda.todos) { AgendaLine(it, onOpen) }
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

// Time, leaders, state, priority, title, tags (own, then inherited), & category. A tap opens the sheet.
@Composable
private fun AgendaLine(row: AgendaRow, onOpen: (AgendaRow) -> Unit) {
	val e = row.entry
	val muted = MaterialTheme.colorScheme.onSurfaceVariant
	Column(Modifier.fillMaxWidth().clickable { onOpen(row) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
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

// A saved filter's blocks, each under its header; a byTag block heads each tag's rows w/ the tag.
@Composable
fun FilterView(files: List<OrgFile>, filter: SavedFilter, onOpen: (AgendaRow) -> Unit) {
	val today = LocalDate.now()
	val blocks = remember(files, filter, today) { filter.blocks.map { Filters.rows(it, files, today) } }
	LazyColumn(Modifier.fillMaxSize()) {
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
				item(key = "block$i-row$n") { AgendaLine(row, onOpen) }
			}
		}
	}
}

// The overflow menu: the timeline, or a saved filter by name.
@Composable
private fun ViewsMenu(current: String?, onPick: (String?) -> Unit) {
	var open by remember { mutableStateOf(false) }
	TextButton(onClick = { open = true }) { Text(current ?: "Timeline") }
	DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
		DropdownMenuItem(text = { Text("Timeline") }, onClick = { onPick(null); open = false })
		for (f in Filters.STARTING) DropdownMenuItem(text = { Text(f.name) }, onClick = { onPick(f.name); open = false })
	}
}
