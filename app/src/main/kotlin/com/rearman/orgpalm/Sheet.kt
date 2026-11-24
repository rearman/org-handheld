package com.rearman.orgpalm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import android.provider.DocumentsContract
import com.rearman.orgpalm.org.Edit
import com.rearman.orgpalm.org.Entry
import com.rearman.orgpalm.org.FieldChange
import com.rearman.orgpalm.org.FormText
import com.rearman.orgpalm.org.OrgFile
import com.rearman.orgpalm.org.RepeatUnit
import com.rearman.orgpalm.org.Repeater
import com.rearman.orgpalm.org.Timestamp
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// A place in the files: a file, & a heading of it by its anchor, or its top level for none.
data class Place(val file: OrgFile, val heading: Entry?)

// The form's fields (DESIGN.md section 2, Bottom sheet), seeded from [entry], or blank for a new one.
// The event fields hold the leading stamp lines (Entry.event); [event] says whether the form shows
// them.
private class Form(entry: Entry?) {
	var title by mutableStateOf(entry?.title ?: "")
	var state by mutableStateOf(entry?.state)
	var note by mutableStateOf("")
	var priority by mutableStateOf(entry?.priority)
	var scheduled by mutableStateOf(entry?.scheduled)
	var deadline by mutableStateOf(entry?.deadline)
	var tags by mutableStateOf(entry?.tags ?: emptyList())
	var body by mutableStateOf(entry?.event?.body ?: entry?.body ?: "")

	private val first: Timestamp? = entry?.event?.timestamps?.first()
	var event by mutableStateOf(entry?.event != null)
	var date by mutableStateOf(first?.date ?: LocalDate.now())
	var start by mutableStateOf(first?.start)
	var end by mutableStateOf(first?.end)
	var repeater by mutableStateOf(first?.repeater)
	var weekdays by mutableStateOf(entry?.event?.weekdays ?: emptySet())
	var warn by mutableStateOf(entry?.event?.warnMinutes?.toString() ?: "")

	// The event's stamps: the first on [date], w/ the time span, the repeater, & the warning cookie of
	// the stamp it replaces; a weekly repeat on several weekdays gives one stamp per day.
	fun stamps(): List<Timestamp> {
		val stamp = Timestamp(date, start, end.takeIf { start != null }, repeater, first?.warning, true, first?.warningFirst ?: false)
		val days = weekdays + date.dayOfWeek
		return if (repeater?.unit == RepeatUnit.WEEK && days.size > 1) OrgFile.weekdayTimestamps(stamp, days) else listOf(stamp)
	}

	// The changes that turn [entry] into the form, or all set fields for a new entry. A locked planning
	// field is left out, as the form shows it read-only.
	fun changes(entry: Entry?): List<FieldChange> {
		val out = ArrayList<FieldChange>()
		val title = FormText.oneLine(title)
		if (title != (entry?.title ?: "")) out.add(FieldChange.Title(title))
		if (state != entry?.state) out.add(FieldChange.State(state, note))
		if (priority != entry?.priority) out.add(FieldChange.Priority(priority))
		if (tags != (entry?.tags ?: emptyList<String>())) out.add(FieldChange.Tags(tags))
		if (entry?.scheduledLocked != true && scheduled != entry?.scheduled) out.add(FieldChange.Scheduled(scheduled))
		if (entry?.deadlineLocked != true && deadline != entry?.deadline) out.add(FieldChange.Deadline(deadline))
		if (event) {
			val stamps = stamps()
			if (stamps != entry?.event?.timestamps) out.add(FieldChange.Timestamps(stamps))
			val minutes = warn.trim().toIntOrNull()
			if (minutes != entry?.event?.warnMinutes) out.add(FieldChange.warnMinutes(minutes))
		}
		val oldBody = entry?.event?.body ?: entry?.body ?: ""
		if (body != oldBody) out.add(FieldChange.Body(body))
		return out
	}
}

private val STAMP_DATE = DateTimeFormatter.ofPattern("EEE MMM d, yyyy")
private val STAMP_TIME = DateTimeFormatter.ofPattern("HH:mm")

// The one sheet for capture & edit. [subject] is the entry to edit, or null for a new one under
// [target], the capture target as found (null when its file or heading is missing, which sends the
// user to the picker).
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntrySheet(model: AppModel, subject: Place?, target: Place?, onClose: () -> Unit) {
	var editing by remember { mutableStateOf(subject) }
	val original = subject?.heading
	val form = remember(subject) { Form(original) }
	var place by remember { mutableStateOf(target) }
	var picking by remember { mutableStateOf(subject == null && target == null) }
	var refiling by remember { mutableStateOf(false) }
	var message by remember { mutableStateOf<String?>(if (subject == null && target == null) "The capture target is missing; pick a place." else null) }
	var conflict by remember { mutableStateOf(false) }
	val scope = rememberCoroutineScope()
	val keywords = (editing?.file ?: place?.file)?.keywords
	val priorities = (editing?.file ?: place?.file)?.priorities

	fun show(result: Saved) {
		conflict = false
		when (result) {
			Saved.Done -> onClose()
			Saved.Conflict -> {
				conflict = true
				message = "The heading changed since it was read; thine edit is kept. Retry applies it to the heading as it now stands."
			}
			Saved.Locked -> {
				message = "A date field is locked; the form shows the file as it now stands."
				editing = editing?.let { fresh(model, it) }
			}
			is Saved.Blocked -> message = "Blocked by ${result.reason}"
			Saved.CopyLeft -> message = "The source changed after the copy; the entry now stands in both files."
			is Saved.Failed -> message = result.message
		}
	}

	ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState()) {
		Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedTextField(form.title, { form.title = it }, Modifier.fillMaxWidth(), label = { Text("Title") })
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
				Choice("State", form.state, keywords?.all ?: emptyList()) { form.state = it }
				Choice("Priority", form.priority, priorities?.values ?: emptyList()) { form.priority = it }
			}
			val asks = editing?.let { e -> e.heading?.let { model.asksNote(e.file, it, form.state) } } == true
			if (asks) OutlinedTextField(form.note, { form.note = it }, Modifier.fillMaxWidth(), label = { Text("Note") })
			StampRow("Scheduled", form.scheduled, original?.scheduledLocked == true) { form.scheduled = it }
			StampRow("Deadline", form.deadline, original?.deadlineLocked == true) { form.deadline = it }
			Tags(form.tags) { form.tags = it }
			if (original == null) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text("Event", Modifier.weight(1f))
					Switch(form.event, { form.event = it })
				}
			}
			if (form.event) EventFields(form)
			OutlinedTextField(form.body, { form.body = it }, Modifier.fillMaxWidth(), label = { Text("Body") }, minLines = 3)
			val at = editing ?: place
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(at?.let { where(it) } ?: "No place", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
				TextButton(onClick = { if (subject == null) picking = true else refiling = true }) { Text(if (subject == null) "Place" else "Refile") }
			}
			message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
			Row(Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button(onClick = {
					scope.launch {
						val e = editing
						val p = place
						when {
							e?.heading != null -> show(model.save(e.file, Edit.Update(e.heading.anchor, form.changes(original))))
							p != null -> show(model.capture(p.file, p.heading?.anchor, form.changes(null)))
							else -> message = "Pick a place first."
						}
					}
				}) { Text("Save") }
				if (conflict) {
					OutlinedButton(onClick = {
						val again = editing?.let { relocate(model, it) }
						if (again == null) message = "The heading is gone or has a twin; edit it in Emacs." else {
							editing = again
							scope.launch { show(model.save(again.file, Edit.Update(again.heading!!.anchor, form.changes(original)))) }
						}
					}) { Text("Retry") }
				}
				TextButton(onClick = onClose) { Text("Cancel") }
			}
		}
	}

	if (picking) {
		PlacePicker(model, onCreate = true, onDismiss = { picking = false }) {
			place = it
			picking = false
			message = null
		}
	}
	if (refiling) {
		PlacePicker(model, onCreate = false, onDismiss = { refiling = false }) { to ->
			refiling = false
			val e = editing ?: return@PlacePicker
			scope.launch { show(model.refile(e.file, e.heading!!.anchor, to.file, to.heading?.anchor)) }
		}
	}
}

// The file & outline path of a place.
private fun where(p: Place): String =
	listOf(p.file.path).plus(p.heading?.let { it.outlinePath + it.title } ?: emptyList()).joinToString(" / ")

// [place] as the last walk read it: its file anew, & the entry at the same anchor line.
private fun fresh(model: AppModel, place: Place): Place? {
	val uri = model.uri(place.file) ?: return null
	val file = model.current(uri) ?: return null
	val line = place.heading?.anchor?.lineIndex
	return Place(file, file.entries.firstOrNull { it.anchor.lineIndex == line })
}

// The edited heading in the file as the last walk read it, found by its heading line alone, for a
// retry after a conflict; null when no heading or several have that line.
private fun relocate(model: AppModel, place: Place): Place? {
	val uri = model.uri(place.file) ?: model.loaded.value.firstOrNull { it.file.path == place.file.path }?.uri ?: return null
	val file = model.current(uri) ?: return null
	val line = place.heading?.anchor?.headingLine ?: return null
	val match = file.entries.filter { it.anchor.headingLine == line }.singleOrNull() ?: return null
	return Place(file, match)
}

// A value picked from [options], or none.
@Composable
fun Choice(label: String, value: String?, options: List<String>, onPick: (String?) -> Unit) {
	var open by remember { mutableStateOf(false) }
	Column {
		OutlinedButton(onClick = { open = true }) { Text(if (label.isEmpty()) value ?: "none" else "$label: ${value ?: "none"}") }
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			DropdownMenuItem(text = { Text("none") }, onClick = { onPick(null); open = false })
			for (o in options) DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false })
		}
	}
}

// A SCHEDULED or DEADLINE field: its date & time, or none. A change of the date or time keeps the
// stamp's repeater & warning. A locked field shows read-only (DESIGN.md section 4 rule 4).
@Composable
private fun StampRow(label: String, value: Timestamp?, locked: Boolean, onChange: (Timestamp?) -> Unit) {
	if (locked) {
		Text("$label: locked; edit it in Emacs", style = MaterialTheme.typography.bodySmall)
		return
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(label, Modifier.width(80.dp))
		DateButton(value?.date) { d -> onChange(value?.copy(date = d) ?: Timestamp(d)) }
		if (value != null) {
			TimeButton(value.start) { t -> onChange(value.copy(start = t, end = value.end.takeIf { t != null })) }
			TextButton(onClick = { onChange(null) }) { Text("Clear") }
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateButton(value: LocalDate?, onPick: (LocalDate) -> Unit) {
	var open by remember { mutableStateOf(false) }
	OutlinedButton(onClick = { open = true }) { Text(value?.let { STAMP_DATE.format(it) } ?: "none") }
	if (open) {
		val start = (value ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
		val state = rememberDatePickerState(initialSelectedDateMillis = start)
		DatePickerDialog(
			onDismissRequest = { open = false },
			confirmButton = {
				TextButton(onClick = {
					state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
					open = false
				}) { Text("OK") }
			},
			dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
		) { DatePicker(state) }
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeButton(value: LocalTime?, onPick: (LocalTime?) -> Unit) {
	var open by remember { mutableStateOf(false) }
	OutlinedButton(onClick = { open = true }) { Text(value?.let { STAMP_TIME.format(it) } ?: "no time") }
	if (open) {
		val state = rememberTimePickerState(value?.hour ?: 9, value?.minute ?: 0, is24Hour = true)
		AlertDialog(
			onDismissRequest = { open = false },
			confirmButton = {
				TextButton(onClick = {
					onPick(LocalTime.of(state.hour, state.minute))
					open = false
				}) { Text("OK") }
			},
			dismissButton = {
				TextButton(onClick = {
					onPick(null)
					open = false
				}) { Text("No time") }
			},
			text = { TimePicker(state) },
		)
	}
}

// The tags as chips, each removed by a tap, & a field that adds one.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tags(tags: List<String>, onChange: (List<String>) -> Unit) {
	var draft by remember { mutableStateOf("") }
	FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
		for (t in tags) FilterChip(selected = true, onClick = { onChange(tags - t) }, label = { Text("$t  x") })
	}
	Row(verticalAlignment = Alignment.CenterVertically) {
		OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), label = { Text("Tag") }, singleLine = true)
		TextButton(onClick = {
			val t = draft.trim()
			if (t.isNotEmpty() && t !in tags) onChange(tags + t)
			draft = ""
		}) { Text("Add") }
	}
}

// The event fields: date, time span, repeat, weekday set (for a weekly repeat), & alarm.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventFields(form: Form) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		Text("Date", Modifier.width(80.dp))
		DateButton(form.date) { form.date = it }
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		Text("Time", Modifier.width(80.dp))
		TimeButton(form.start) {
			form.start = it
			if (it == null) form.end = null
		}
		if (form.start != null) {
			Text("to")
			TimeButton(form.end) { form.end = it }
		}
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		Text("Repeat", Modifier.width(80.dp))
		val r = form.repeater
		OutlinedTextField(
			r?.value?.toString() ?: "",
			{ text ->
				val n = text.trim().toIntOrNull()
				form.repeater = if (n == null || n <= 0) null else Repeater(r?.mark ?: "+", n, r?.unit ?: RepeatUnit.WEEK)
			},
			Modifier.width(72.dp),
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
		)
		val units = listOf(RepeatUnit.DAY to "days", RepeatUnit.WEEK to "weeks", RepeatUnit.MONTH to "months", RepeatUnit.YEAR to "years")
		Choice("Every", units.firstOrNull { it.first == r?.unit }?.second, units.map { it.second }) { picked ->
			val unit = units.firstOrNull { it.second == picked }?.first
			form.repeater = if (unit == null) null else Repeater(r?.mark ?: "+", r?.value ?: 1, unit)
		}
	}
	if (form.repeater?.unit == RepeatUnit.WEEK) {
		FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			for (d in WEEK) {
				val on = d in form.weekdays || d == form.date.dayOfWeek
				FilterChip(
					selected = on,
					onClick = { if (d != form.date.dayOfWeek) form.weekdays = if (on) form.weekdays - d else form.weekdays + d },
					label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
				)
			}
		}
	}
	OutlinedTextField(
		form.warn,
		{ form.warn = it },
		Modifier.fillMaxWidth(),
		label = { Text("Alarm, minutes before") },
		singleLine = true,
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
	)
}

// The days of the week from Sunday, as calendar-week-start-day 0 orders them.
private val WEEK = listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }

// The file & heading picker for capture & refile (PLAN.md phase 4; the outline browser replaces it in
// phase 7): each file, then its headings indented by level. W/ [onCreate], a missing capture file can
// be created in a folder, on request.
@Composable
fun PlacePicker(model: AppModel, onCreate: Boolean, onDismiss: () -> Unit, onPick: (Place) -> Unit) {
	val loaded by model.loaded.collectAsStateWithLifecycle()
	val target = model.captureTarget.value
	val scope = rememberCoroutineScope()
	AlertDialog(
		onDismissRequest = onDismiss,
		confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
		title = { Text("Pick a place") },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState())) {
				if (onCreate && loaded.none { it.file.path == target.file }) {
					for (folder in model.folders.value) {
						TextButton(onClick = { scope.launch { model.createFile(folder, target.file.substringAfterLast('/')) } }) {
							Text("Create ${target.file} in ${DocumentsContract.getTreeDocumentId(Uri.parse(folder.uri))}")
						}
					}
				}
				for ((_, file) in loaded) {
					TextButton(onClick = { onPick(Place(file, null)) }) { Text(file.path, style = MaterialTheme.typography.titleSmall) }
					for (e in file.entries) {
						TextButton(onClick = { onPick(Place(file, e)) }, Modifier.padding(start = (12 * e.level).dp)) { Text(e.title) }
					}
				}
			}
		},
	)
}

// The capture target as the last walk found it, or null when its file or heading is missing.
fun findTarget(model: AppModel): Place? {
	val t = model.captureTarget.value
	val file = model.loaded.value.firstOrNull { it.file.path == t.file }?.file ?: return null
	if (t.heading.isEmpty()) return Place(file, null)
	val e = file.entries.firstOrNull { it.outlinePath + it.title == t.heading } ?: return null
	return Place(file, e)
}

// The outline path & title of a place's heading, as CaptureTarget stores it; none for the top level.
fun Place.headingPath(): List<String> = heading?.let { it.outlinePath + it.title } ?: emptyList()

