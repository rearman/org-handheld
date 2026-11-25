package com.rearman.orgpalm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rearman.orgpalm.org.Entry
import com.rearman.orgpalm.org.OrgFile

// One line of the tree: a file's name, a heading, or a heading's body.
private sealed class Line(val key: String) {
	class FileName(key: String, val file: OrgFile, val isOpen: Boolean) : Line(key)
	class Heading(key: String, val file: OrgFile, val index: Int, val entry: Entry, val toggles: Boolean, val isOpen: Boolean) : Line(key)
	class Body(key: String, val entry: Entry) : Line(key)
}

// The outline browser (PLAN.md phase 7): each file, & its headings as a collapsible tree. A file opens
// to its top-level headings, as Org's overview shows them, & each heading opens to its body, when
// [bodies] is set, & its children, one level a time: a close closes all below it. A tap on a file's
// name calls [onFile] (the top level of the file, for the picker), & on a heading [onHeading]; the
// [+] & [-] marks open & close. [reveal] opens the tree to its heading, & that heading itself, &
// centers it. The open marks & the line at the center outlive a rotation.
@Composable
fun OutlineTree(
	files: List<OrgFile>,
	modifier: Modifier,
	onFile: ((OrgFile) -> Unit)?,
	bodies: Boolean = false,
	reveal: Place? = null,
	onHeading: (OrgFile, Entry) -> Unit,
) {
	// Open files & headings, by path & by path & heading line index.
	val open = rememberSaveable(saver = OpenSaver) { mutableStateMapOf<String, Boolean>().apply { reveal?.let { putAll(pathTo(it)) } } }
	val lines = ArrayList<Line>()
	for (file in files) {
		val fileKey = file.path
		val id = "@" + System.identityHashCode(file)
		val fileOpen = open[fileKey] == true
		lines += Line.FileName("f:$fileKey$id", file, fileOpen)
		if (!fileOpen) continue
		val entries = file.entries
		// Show an entry when every ancestor is open: track the level below which entries hide.
		var hideBelow = Int.MAX_VALUE
		entries.forEachIndexed { i, e ->
			if (e.level > hideBelow) return@forEachIndexed
			hideBelow = Int.MAX_VALUE
			val key = "$fileKey#${e.anchor.lineIndex}"
			val hasChildren = i + 1 < entries.size && entries[i + 1].level > e.level
			val hasBody = bodies && e.body.isNotBlank()
			val headingOpen = open[key] == true
			if (hasChildren && !headingOpen) hideBelow = e.level
			lines += Line.Heading("h:$key$id", file, i, e, hasChildren || hasBody, headingOpen)
			if (hasBody && headingOpen) lines += Line.Body("b:$key$id", e)
		}
	}
	val revealKey = reveal?.heading?.let { "h:${reveal.file.path}#${it.anchor.lineIndex}@" }
	val state = rememberCenteredListState(revealKey?.let { k -> lines.indexOfFirst { it.key.startsWith(k) } }?.takeIf { it >= 0 })
	LazyColumn(modifier, state) {
		for (line in lines) {
			item(key = line.key) {
				when (line) {
					is Line.FileName -> Row(verticalAlignment = Alignment.CenterVertically) {
						val fileKey = line.file.path
						Toggle(line.isOpen) { if (line.isOpen) closeFile(open, fileKey) else open[fileKey] = true }
						Text(
							line.file.path,
							Modifier.weight(1f).let { m -> onFile?.let { f -> m.clickable { f(line.file) } } ?: m.clickable { if (line.isOpen) closeFile(open, fileKey) else open[fileKey] = true } }.padding(8.dp),
							style = MaterialTheme.typography.titleSmall,
						)
					}
					is Line.Heading -> Row(Modifier.fillMaxWidth().padding(start = (16 * line.entry.level).dp), verticalAlignment = Alignment.CenterVertically) {
						val key = "${line.file.path}#${line.entry.anchor.lineIndex}"
						if (line.toggles) Toggle(line.isOpen) { if (line.isOpen) closeHeading(open, line.file, line.index) else open[key] = true } else Text("", Modifier.width(48.dp))
						Text(
							listOfNotNull(line.entry.state, line.entry.title).joinToString(" "),
							Modifier.weight(1f).clickable { onHeading(line.file, line.entry) }.padding(8.dp),
							fontWeight = if (line.entry.level == 1) FontWeight.Medium else FontWeight.Normal,
						)
					}
					is Line.Body -> Text(
						line.entry.body.trimEnd(),
						Modifier.fillMaxWidth().padding(start = (16 * line.entry.level + 56).dp, end = 8.dp, bottom = 8.dp),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}

// The open marks, saved as the list of the open keys.
private val OpenSaver = listSaver<SnapshotStateMap<String, Boolean>, String>(
	save = { m -> m.filterValues { it }.keys.toList() },
	restore = { keys -> mutableStateMapOf<String, Boolean>().apply { keys.forEach { put(it, true) } } },
)

// Closes a file & every heading in it.
private fun closeFile(open: SnapshotStateMap<String, Boolean>, path: String) {
	open.keys.filter { it == path || it.startsWith("$path#") }.forEach { open.remove(it) }
}

// Closes the heading at [index] of [file] & every heading below it.
private fun closeHeading(open: SnapshotStateMap<String, Boolean>, file: OrgFile, index: Int) {
	val entries = file.entries
	val level = entries[index].level
	var j = index
	do {
		open.remove("${file.path}#${entries[j].anchor.lineIndex}")
		j++
	} while (j < entries.size && entries[j].level > level)
}

// The open marks that show [place]'s heading opened: its file, each ancestor, & the heading itself.
private fun pathTo(place: Place): Map<String, Boolean> {
	val heading = place.heading ?: return mapOf(place.file.path to true)
	val entries = place.file.entries
	val at = entries.indexOfFirst { it.anchor.lineIndex == heading.anchor.lineIndex }
	val marks = mutableMapOf(place.file.path to true, "${place.file.path}#${heading.anchor.lineIndex}" to true)
	var level = heading.level
	for (j in at - 1 downTo 0) {
		if (entries[j].level < level) {
			level = entries[j].level
			marks["${place.file.path}#${entries[j].anchor.lineIndex}"] = true
		}
	}
	return marks
}

@Composable
private fun Toggle(isOpen: Boolean, onClick: () -> Unit) {
	TextButton(onClick = onClick, Modifier.width(48.dp)) { Text(if (isOpen) "[-]" else "[+]") }
}
