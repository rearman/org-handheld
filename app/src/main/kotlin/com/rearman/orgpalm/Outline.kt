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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rearman.orgpalm.org.Entry
import com.rearman.orgpalm.org.OrgFile

// The outline browser (PLAN.md phase 7): each file, & its headings as a collapsible tree. A file opens
// to its top-level headings, as Org's overview shows them, & each heading w/ children opens a level
// more. A tap on a file's name calls [onFile] (the top level of the file, for the picker), & on a
// heading [onHeading]; the [+] & [-] marks open & close.
@Composable
fun OutlineTree(files: List<OrgFile>, modifier: Modifier, onFile: ((OrgFile) -> Unit)?, onHeading: (OrgFile, Entry) -> Unit) {
	// Open files & headings, by path & by path & heading line index.
	val open = remember { mutableStateMapOf<String, Boolean>() }
	LazyColumn(modifier) {
		for (file in files) {
			val fileKey = file.path
			val fileOpen = open[fileKey] == true
			item(key = "f:" + file.path + "@" + System.identityHashCode(file)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Toggle(fileOpen) { open[fileKey] = !fileOpen }
					Text(
						file.path,
						Modifier.weight(1f).let { m -> onFile?.let { f -> m.clickable { f(file) } } ?: m.clickable { open[fileKey] = !fileOpen } }.padding(8.dp),
						style = MaterialTheme.typography.titleSmall,
					)
				}
			}
			if (!fileOpen) continue
			val entries = file.entries
			// Show an entry when every ancestor is open: track the level below which entries hide.
			var hideBelow = Int.MAX_VALUE
			entries.forEachIndexed { i, e ->
				if (e.level > hideBelow) return@forEachIndexed
				hideBelow = Int.MAX_VALUE
				val key = "$fileKey#${e.anchor.lineIndex}"
				val hasChildren = i + 1 < entries.size && entries[i + 1].level > e.level
				val headingOpen = open[key] == true
				if (hasChildren && !headingOpen) hideBelow = e.level
				item(key = "h:$key@" + System.identityHashCode(file)) {
					Row(Modifier.fillMaxWidth().padding(start = (16 * e.level).dp), verticalAlignment = Alignment.CenterVertically) {
						if (hasChildren) Toggle(headingOpen) { open[key] = !headingOpen } else Text("", Modifier.width(48.dp))
						Text(
							listOfNotNull(e.state, e.title).joinToString(" "),
							Modifier.weight(1f).clickable { onHeading(file, e) }.padding(8.dp),
							fontWeight = if (e.level == 1) FontWeight.Medium else FontWeight.Normal,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun Toggle(isOpen: Boolean, onClick: () -> Unit) {
	TextButton(onClick = onClick, Modifier.width(48.dp)) { Text(if (isOpen) "[-]" else "[+]") }
}
