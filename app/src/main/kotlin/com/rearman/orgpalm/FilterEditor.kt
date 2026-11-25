package com.rearman.orgpalm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// A block as the editor holds it: each set as the text typed, words split on save.
private data class BlockDraft(
	val header: String = "",
	val span: String = "",
	val states: String = "",
	val tags: String = "",
	val categories: String = "",
	val stuck: Boolean = false,
	val byTag: Boolean = false,
) {
	fun block(): Block = Block(
		header = header.trim(),
		span = span.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0,
		states = words(states),
		tags = words(tags),
		categories = words(categories),
		stuck = stuck,
		byTag = byTag,
	)

	companion object {
		fun of(b: Block) = BlockDraft(
			b.header,
			if (b.span > 0) b.span.toString() else "",
			b.states.joinToString(" "),
			b.tags.joinToString(" "),
			b.categories.joinToString(" "),
			b.stuck,
			b.byTag,
		)

		private fun words(text: String): Set<String> = text.split(' ', '\t', ',').filter { it.isNotEmpty() }.toSet()
	}
}

private data class FilterDraft(val name: String, val blocks: List<BlockDraft>)

// The editor of the saved filters, in two pages. The first lists the filters by name; a tap on one
// opens the second, which holds that filter's name & its blocks, each in a card of its own, w/
// blocks added & removed & the filter deleted. Back (the button or the gesture) returns to the list.
// A block w/ a span shows that many days of the agenda from today; else it lists the entries that
// match its states, tags, & categories (words apart), or the stuck projects, sorted by tag w/ "By
// tag". Save on the list stores them all; a filter w/o a name is dropped.
@Composable
fun FilterEditor(model: AppModel, onDone: () -> Unit) {
	val drafts = remember { mutableStateListOf<FilterDraft>().apply { addAll(model.filters.value.map { f -> FilterDraft(f.name, f.blocks.map { BlockDraft.of(it) }) }) } }
	var opened by remember { mutableStateOf<Int?>(null) }
	val fi = opened
	if (fi == null) {
		FilterList(
			drafts,
			onOpen = { opened = it },
			onAdd = { drafts.add(FilterDraft("", listOf(BlockDraft()))); opened = drafts.lastIndex },
			onSave = {
				model.saveFilters(drafts.filter { it.name.isNotBlank() }.map { d -> SavedFilter(d.name.trim(), d.blocks.map { it.block() }) })
				onDone()
			},
			onCancel = onDone,
		)
	} else {
		BackHandler { opened = null }
		FilterPage(drafts[fi], { drafts[fi] = it }, onBack = { opened = null }, onDelete = { drafts.removeAt(fi); opened = null })
	}
}

// The filters by name, each w/ the headers of its blocks beneath.
@Composable
private fun FilterList(drafts: List<FilterDraft>, onOpen: (Int) -> Unit, onAdd: () -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Column {
			drafts.forEachIndexed { fi, f ->
				ListItem(
					headlineContent = { Text(f.name.ifBlank { "(no name)" }) },
					supportingContent = { Text(f.blocks.joinToString(", ") { it.header.ifBlank { "(no header)" } }) },
					modifier = Modifier.clickable { onOpen(fi) },
				)
				HorizontalDivider()
			}
		}
		OutlinedButton(onClick = onAdd) { Text("Add filter") }
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(onClick = onSave) { Text("Save filters") }
			TextButton(onClick = onCancel) { Text("Cancel") }
		}
	}
}

// One filter's options: its name, then each block in a card.
@Composable
private fun FilterPage(f: FilterDraft, onChange: (FilterDraft) -> Unit, onBack: () -> Unit, onDelete: () -> Unit) {
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			TextButton(onClick = onBack) { Text("Back") }
			Spacer(Modifier.weight(1f))
			TextButton(onClick = onDelete) { Text("Delete filter") }
		}
		OutlinedTextField(f.name, { onChange(f.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("Filter name") }, singleLine = true)
		f.blocks.forEachIndexed { bi, b ->
			val set = { nb: BlockDraft -> onChange(f.copy(blocks = f.blocks.toMutableList().also { it[bi] = nb })) }
			OutlinedCard(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text("Block ${bi + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
						TextButton(onClick = { onChange(f.copy(blocks = f.blocks.filterIndexed { i, _ -> i != bi })) }) { Text("Remove") }
					}
					OutlinedTextField(b.header, { set(b.copy(header = it)) }, Modifier.fillMaxWidth(), label = { Text("Block header") }, singleLine = true)
					OutlinedTextField(
						b.span,
						{ set(b.copy(span = it)) },
						Modifier.fillMaxWidth(),
						label = { Text("Agenda days from today (empty for a list)") },
						singleLine = true,
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					)
					OutlinedTextField(b.states, { set(b.copy(states = it)) }, Modifier.fillMaxWidth(), label = { Text("TODO states") }, singleLine = true)
					OutlinedTextField(b.tags, { set(b.copy(tags = it)) }, Modifier.fillMaxWidth(), label = { Text("Tags") }, singleLine = true)
					OutlinedTextField(b.categories, { set(b.copy(categories = it)) }, Modifier.fillMaxWidth(), label = { Text("Categories") }, singleLine = true)
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text("Stuck projects", Modifier.weight(1f))
						Switch(b.stuck, { set(b.copy(stuck = it)) })
					}
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text("By tag", Modifier.weight(1f))
						Switch(b.byTag, { set(b.copy(byTag = it)) })
					}
				}
			}
		}
		OutlinedButton(onClick = { onChange(f.copy(blocks = f.blocks + BlockDraft())) }) { Text("Add block") }
	}
}
