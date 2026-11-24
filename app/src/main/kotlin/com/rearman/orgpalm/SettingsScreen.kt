package com.rearman.orgpalm

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rearman.orgpalm.org.LogMode
import com.rearman.orgpalm.org.OrgSettings
import com.rearman.orgpalm.org.Position
import com.rearman.orgpalm.org.Priorities

// The settings page (PLAN.md phase 4): a row per Emacs variable of DESIGN.md section 3, the capture
// target, & the folders w/ their subfolder switches. Save stores the settings & reads every file again
// under them; a field that does not read as its type keeps its stored value.
@Composable
fun SettingsScreen(model: AppModel, folders: List<Folder>, onAddFolder: () -> Unit) {
	val s = model.settings.value
	var keywords by remember(s) { mutableStateOf(SettingsCodec.keywordsText(s.todoKeywords)) }
	var tagsColumn by remember(s) { mutableStateOf(s.tagsColumn.toString()) }
	var excluded by remember(s) { mutableStateOf(s.tagsExcludedFromInheritance.joinToString(" ")) }
	var logDone by remember(s) { mutableStateOf(s.logDone) }
	var logRepeat by remember(s) { mutableStateOf(s.logRepeat) }
	var logReschedule by remember(s) { mutableStateOf(s.logReschedule) }
	var logRedeadline by remember(s) { mutableStateOf(s.logRedeadline) }
	var drawer by remember(s) { mutableStateOf(s.logIntoDrawer ?: "") }
	var afterDrawers by remember(s) { mutableStateOf(s.logNotesAfterDrawers) }
	var refile by remember(s) { mutableStateOf(s.refilePosition) }
	var capture by remember(s) { mutableStateOf(s.capturePosition) }
	var enforceTodo by remember(s) { mutableStateOf(s.enforceTodoDependencies) }
	var enforceBoxes by remember(s) { mutableStateOf(s.enforceCheckboxDependencies) }
	var highest by remember(s) { mutableStateOf(Priorities.text(s.priorityHighest) ?: "") }
	var lowest by remember(s) { mutableStateOf(Priorities.text(s.priorityLowest) ?: "") }
	var default by remember(s) { mutableStateOf(Priorities.text(s.priorityDefault) ?: "") }
	var warningDays by remember(s) { mutableStateOf(s.deadlineWarningDays.toString()) }
	val stored = model.captureTarget.value
	var targetFile by remember(stored) { mutableStateOf(stored.file) }
	var targetHeading by remember(stored) { mutableStateOf(stored.heading) }
	var picking by remember { mutableStateOf(false) }

	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text("Folders", style = MaterialTheme.typography.titleMedium)
		for (f in folders) {
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				Text(DocumentsContract.getTreeDocumentId(Uri.parse(f.uri)), Modifier.weight(1f))
				Text("Subfolders", style = MaterialTheme.typography.labelMedium)
				Spacer(Modifier.width(8.dp))
				Switch(checked = f.recursive, onCheckedChange = { model.setRecursive(f, it) })
			}
		}
		OutlinedButton(onClick = onAddFolder) { Text("Add folder") }
		HorizontalDivider()

		Text("Capture", style = MaterialTheme.typography.titleMedium)
		OutlinedTextField(targetFile, { targetFile = it }, Modifier.fillMaxWidth(), label = { Text("Capture file") }, singleLine = true)
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text("Heading: ${targetHeading.joinToString(" / ").ifEmpty { "top level" }}", Modifier.weight(1f))
			OutlinedButton(onClick = { picking = true }) { Text("Pick") }
		}
		Toggle("Capture goes first (:prepend)", capture == Position.FIRST) { capture = if (it) Position.FIRST else Position.LAST }
		HorizontalDivider()

		Text("Emacs settings", style = MaterialTheme.typography.titleMedium)
		OutlinedTextField(keywords, { keywords = it }, Modifier.fillMaxWidth(), label = { Text("org-todo-keywords, as #+TODO: lines") })
		NumberField("org-tags-column", tagsColumn) { tagsColumn = it }
		OutlinedTextField(excluded, { excluded = it }, Modifier.fillMaxWidth(), label = { Text("org-tags-exclude-from-inheritance") }, singleLine = true)
		Log("org-log-done", logDone) { logDone = it }
		Log("org-log-repeat", logRepeat) { logRepeat = it }
		Log("org-log-reschedule", logReschedule) { logReschedule = it }
		Log("org-log-redeadline", logRedeadline) { logRedeadline = it }
		OutlinedTextField(drawer, { drawer = it }, Modifier.fillMaxWidth(), label = { Text("org-log-into-drawer (empty for nil)") }, singleLine = true)
		Toggle("org-log-state-notes-insert-after-drawers", afterDrawers) { afterDrawers = it }
		Toggle("org-reverse-note-order (refile goes first)", refile == Position.FIRST) { refile = if (it) Position.FIRST else Position.LAST }
		Toggle("org-enforce-todo-dependencies", enforceTodo) { enforceTodo = it }
		Toggle("org-enforce-todo-checkbox-dependencies", enforceBoxes) { enforceBoxes = it }
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedTextField(highest, { highest = it }, Modifier.weight(1f), label = { Text("Highest") }, singleLine = true)
			OutlinedTextField(lowest, { lowest = it }, Modifier.weight(1f), label = { Text("Lowest") }, singleLine = true)
			OutlinedTextField(default, { default = it }, Modifier.weight(1f), label = { Text("Default") }, singleLine = true)
		}
		NumberField("org-deadline-warning-days", warningDays) { warningDays = it }

		Button(onClick = {
			val next = OrgSettings(
				todoKeywords = SettingsCodec.keywords(keywords).filter { it.names.isNotEmpty() }.ifEmpty { s.todoKeywords },
				tagsColumn = tagsColumn.trim().toIntOrNull() ?: s.tagsColumn,
				priorityHighest = priority(highest) ?: s.priorityHighest,
				priorityLowest = priority(lowest) ?: s.priorityLowest,
				priorityDefault = priority(default) ?: s.priorityDefault,
				tagsExcludedFromInheritance = excluded.split(' ', '\t').filter { it.isNotEmpty() }.toSet(),
				logDone = logDone,
				logRepeat = logRepeat,
				logReschedule = logReschedule,
				logRedeadline = logRedeadline,
				logIntoDrawer = drawer.trim().ifEmpty { null },
				logNotesAfterDrawers = afterDrawers,
				refilePosition = refile,
				capturePosition = capture,
				enforceTodoDependencies = enforceTodo,
				enforceCheckboxDependencies = enforceBoxes,
				deadlineWarningDays = warningDays.trim().toIntOrNull() ?: s.deadlineWarningDays,
			)
			model.saveSettings(next, CaptureTarget(targetFile.trim().ifEmpty { stored.file }, targetHeading))
		}) { Text("Save settings") }
	}

	if (picking) {
		PlacePicker(model, onCreate = false, onDismiss = { picking = false }) {
			targetFile = it.file.path
			targetHeading = it.headingPath()
			picking = false
		}
	}
}

// A priority as Org's value: a number, else the code of one letter A to Z.
private fun priority(text: String): Int? {
	val t = text.trim()
	return t.toIntOrNull() ?: t.singleOrNull()?.takeIf { it in 'A'..'Z' }?.code
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(label, Modifier.weight(1f))
		Switch(checked = value, onCheckedChange = onChange)
	}
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
	OutlinedTextField(
		value,
		onChange,
		Modifier.fillMaxWidth(),
		label = { Text(label) },
		singleLine = true,
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
	)
}

// A log variable's value as Emacs names it: nil, time, or note.
@Composable
private fun Log(label: String, value: LogMode, onChange: (LogMode) -> Unit) {
	val names = mapOf(LogMode.NONE to "nil", LogMode.TIME to "time", LogMode.NOTE to "note")
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(label, Modifier.weight(1f))
		Choice("", names[value], names.values.toList()) { picked -> onChange(names.entries.firstOrNull { it.value == picked }?.key ?: LogMode.NONE) }
	}
}
