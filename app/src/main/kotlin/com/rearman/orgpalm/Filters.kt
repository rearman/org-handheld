package com.rearman.orgpalm

import com.rearman.orgpalm.org.Entry
import com.rearman.orgpalm.org.OrgFile
import java.time.LocalDate

// One block of a saved filter, as one entry of a command in org-agenda-custom-commands gives it: the
// agenda of [span] days from today, or the entries that match [states], [tags], & [categories] (each
// empty set matching all), or, w/ [stuck], the stuck projects of org-stuck-projects. [byTag] sorts
// as tag-up, priority-down, effort-up, under a header per tag.
data class Block(
	val header: String,
	val span: Long = 0,
	val states: Set<String> = emptySet(),
	val tags: Set<String> = emptySet(),
	val categories: Set<String> = emptySet(),
	val stuck: Boolean = false,
	val byTag: Boolean = false,
)

// A named filter: its blocks, one after another, as Org's agenda shows a composite command.
data class SavedFilter(val name: String, val blocks: List<Block>)

// A block's rows, each w/ the group it heads under (the tag of a byTag block, else null).
data class BlockRows(val block: Block, val rows: List<Pair<String?, AgendaRow>>)

object Filters {
	// The two commands of init.el's org-agenda-custom-commands.
	val STARTING = listOf(
		SavedFilter(
			"GTD Dashboard",
			listOf(
				Block("Today", span = 1),
				Block("inbox", tags = setOf("inbox")),
				Block("NEXT", states = setOf("NEXT")),
				Block("WAITING", states = setOf("WAITING")),
				Block("STUCK", stuck = true),
			),
		),
		SavedFilter("Contexts", listOf(Block("NEXT by CONTEXT", states = setOf("NEXT"), byTag = true))),
	)

	// init.el's org-stuck-projects, '("+undertaking/-DONE-BELAYED" ("NEXT" "WAITING") nil ""): a
	// project is a heading tagged undertaking whose state is neither DONE nor BELAYED, & it is stuck
	// when no heading line of its subtree, its own included, starts w/ NEXT or WAITING.
	private const val PROJECT_TAG = "undertaking"
	private val PROJECT_NOT = setOf("DONE", "BELAYED")
	private val ACTIVE = listOf("NEXT", "WAITING")

	fun rows(block: Block, files: List<OrgFile>, today: LocalDate): BlockRows {
		if (block.span > 0) {
			val days = Agenda.days(files, today, today.plusDays(block.span - 1), today)
			return BlockRows(block, days.flatMap { d -> d.rows.map { null to it } })
		}
		val rows = ArrayList<AgendaRow>()
		for (file in files) {
			for (e in file.entries) {
				if (!e.inAgenda) continue
				val hit = if (block.stuck) stuck(file, e) else matches(block, e)
				if (hit) Agenda.priority(e, file).let { p -> rows.add(AgendaRow(file, e, null, p, p)) }
			}
		}
		if (!block.byTag) return BlockRows(block, rows.sortedByDescending { it.urgency }.map { null to it })
		val order = compareBy<AgendaRow, String?>(nullsLast()) { lastTag(it.entry) }
			.thenByDescending { it.priority }
			.thenBy { effortMinutes(it.entry) ?: Int.MAX_VALUE }
		return BlockRows(block, rows.sortedWith(order).map { lastTag(it.entry) to it })
	}

	// The tags view & todo list match: a state among [Block.states], a tag (own or inherited, as
	// org-use-tag-inheritance gives it) among [Block.tags], & a category among [Block.categories].
	private fun matches(block: Block, e: Entry): Boolean =
		(block.states.isEmpty() || e.state in block.states) &&
			(block.tags.isEmpty() || e.allTags.any { it in block.tags }) &&
			(block.categories.isEmpty() || e.category in block.categories)

	private fun stuck(file: OrgFile, e: Entry): Boolean {
		if (PROJECT_TAG !in e.allTags || e.state in PROJECT_NOT) return false
		val doc = file.document
		val h = doc.headings.firstOrNull { it.start == e.anchor.lineIndex } ?: return false
		val active = Regex("""^\*+[ \t]+(${ACTIVE.joinToString("|") { Regex.escape(it) }})(?:[ \t]|$)""")
		return doc.headings.none { it.start >= h.start && it.start < h.subtreeEnd && active.containsMatchIn(doc.lines[it.start].text) }
	}

	// The tag that org-cmp-tag compares: the last of the entry's tags, inherited ones first. Limit: Org
	// compares tags w/ the locale's collation (org-string<); plain string order serves here, & a
	// java.text.Collator is the upgrade.
	private fun lastTag(e: Entry): String? = e.allTags.lastOrNull()

	// The Effort property in minutes, as org-duration-to-minutes reads "H:MM", a bare number of
	// minutes, or a number & unit ("1h", "30min", "2d"); none sorts as high, as
	// org-agenda-sort-noeffort-is-high gives it.
	fun effortMinutes(e: Entry): Int? {
		val v = e.property("Effort")?.trim() ?: return null
		Regex("""(\d+):(\d{2})""").matchEntire(v)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
		v.toIntOrNull()?.let { return it }
		var total = 0.0
		var any = false
		for (m in Regex("""(\d+(?:\.\d+)?)\s*(min|h|d|w|m|y)""").findAll(v)) {
			val n = m.groupValues[1].toDouble()
			total += n * when (m.groupValues[2]) {
				"min" -> 1
				"h" -> 60
				"d" -> 1440
				"w" -> 10080
				"m" -> 43200
				else -> 525960
			}
			any = true
		}
		return if (any) total.toInt() else null
	}
}
