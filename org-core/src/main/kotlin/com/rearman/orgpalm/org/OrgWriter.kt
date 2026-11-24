package com.rearman.orgpalm.org

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

// One field of an entry set to a new value. Null clears the field.
sealed interface FieldChange {
	data class Title(val title: String) : FieldChange

	// A new TODO state, w/ the note the form took when the state asks for one ("@"); null or empty
	// logs the line alone, as Org does for an empty note.
	data class State(val keyword: String?, val note: String? = null) : FieldChange
	data class Priority(val value: String?) : FieldChange
	data class Tags(val tags: List<String>) : FieldChange
	data class Scheduled(val timestamp: Timestamp?) : FieldChange
	data class Deadline(val timestamp: Timestamp?) : FieldChange
	data class Property(val key: String, val value: String?) : FieldChange

	// The leading stamp lines of the body (Section.stampEnd), rewritten alone.
	data class Timestamps(val timestamps: List<Timestamp>) : FieldChange

	// The body; for an event, the body after its leading stamp lines (Event.body).
	data class Body(val text: String) : FieldChange

	companion object {
		const val WARN_TIME = "APPT_WARNTIME"
		const val CATEGORY = "CATEGORY"
		const val LAST_REPEAT = "LAST_REPEAT"
		const val REPEAT_TO_STATE = "REPEAT_TO_STATE"

		fun warnMinutes(minutes: Int?): FieldChange = Property(WARN_TIME, minutes?.toString())
	}
}

// An edit to one file. Its headings are named by anchors, found again in the text as it stands at the
// save (DESIGN.md section 4 rule 3).
sealed interface Edit {
	// Fields of the heading at [anchor] set to new values.
	data class Update(val anchor: Anchor, val changes: List<FieldChange>) : Edit

	// A new heading w/ [changes] one level below [parent], or at level 1 when [parent] is null, placed
	// per OrgSettings.capturePosition: last, at the end of the parent's subtree (the end of the file),
	// or first, right after the parent's own section (atop the file's first heading).
	data class Insert(val parent: Anchor?, val changes: List<FieldChange>) : Edit

	// The heading at [anchor] removed w/ its whole subtree.
	data class Delete(val anchor: Anchor) : Edit

	// The subtree at [anchor] moved within its file under [target], its stars shifted to one level
	// below the target, as org-refile moves it, placed per OrgSettings.refilePosition; at level 1 when
	// [target] is null. A move to another file goes through OrgWriter.subtree, refileIn, and refileOut.
	data class Refile(val anchor: Anchor, val target: Anchor?) : Edit
}

sealed interface WriteResult {
	// The new text of the file, and the line index of the heading written: the edited one, the new one
	// of an Insert, the moved one of a Refile, or the line where a deleted one began.
	data class Written(val text: String, val line: Int) : WriteResult

	// An anchor (the edited heading, the parent of an Insert, or the target of a Refile) names no
	// heading of the fresh text, or two or more: the heading changed, went, or was copied since the app
	// read the file. The form keeps the edit for a retry after a re-read.
	data object Conflict : WriteResult

	// The edit sets a planning field whose stamp the app cannot read (Entry's planning locks); the form
	// shows such a field read-only, so this answers only an edit made against an older read.
	data object Locked : WriteResult

	// A done state that org-enforce-todo-dependencies or org-enforce-todo-checkbox-dependencies
	// forbids, w/ the reason Org gives: the blocking heading's title in quotes, or "contained
	// checkboxes" (OrgFile.blocker).
	data class Blocked(val reason: String) : WriteResult
}

// A subtree taken from one file for a move to another: the anchor of its heading in the source, its
// lines from the heading line to the end of its last descendant, and the level of its heading.
data class Subtree(val anchor: Anchor, val lines: List<Line>, val level: Int)

// Span edits per DESIGN.md section 4: each change rewrites only the lines its field owns, in the way the
// Org command for that field rewrites them under [settings] (tools/make-fixtures.el makes the fixtures
// that check this). [clock] gives the time of a CLOSED stamp, of a log note, of a repeat, and of today.
class OrgWriter(private val clock: () -> LocalDateTime, private val settings: OrgSettings = OrgSettings()) {
	// Apply [edit] to the text of a file as it stands now.
	fun apply(text: String, edit: Edit): WriteResult = apply(parse(text), edit)

	fun apply(file: OrgFile, edit: Edit): WriteResult = when (edit) {
		is Edit.Update -> update(file, edit)
		is Edit.Insert -> insert(file, edit)
		is Edit.Delete -> delete(file, edit)
		is Edit.Refile -> refile(file, edit)
	}

	private fun parse(text: String): OrgFile = OrgFile.parse("", text, settings)

	// Whether a change of [h] in [file] to [keyword] logs a note, so that the form asks for one, as
	// setState and repeat write it: the state line of a keyword marked "@" (on entry, or on leaving the
	// old state), a CLOSING NOTE per org-log-done, or on a repeat a line per org-log-repeat.
	fun asksNote(file: OrgFile, h: Heading, keyword: String?): Boolean = Buffer(file).asksNote(h.start, keyword)

	private fun update(file: OrgFile, edit: Edit.Update): WriteResult {
		val h = locate(file, edit.anchor) ?: return WriteResult.Conflict
		val entry = file.entry(h)
		if (locked(entry, edit.changes)) return WriteResult.Locked
		val state = edit.changes.filterIsInstance<FieldChange.State>().lastOrNull()
		if (state != null) file.blocker(h, entry.state, state.keyword)?.let { return WriteResult.Blocked(it) }
		val buf = Buffer(file)
		buf.applyChanges(h.start, edit.changes)
		return WriteResult.Written(buf.render(), h.start)
	}

	// True when a change sets a planning keyword whose stamp does not parse.
	private fun locked(entry: Entry, changes: List<FieldChange>): Boolean = changes.any { c ->
		when (c) {
			is FieldChange.Scheduled -> entry.scheduledLocked
			is FieldChange.Deadline -> entry.deadlineLocked
			else -> false
		}
	}

	private fun insert(file: OrgFile, edit: Edit.Insert): WriteResult {
		val parent = edit.parent?.let { locate(file, it) ?: return WriteResult.Conflict }
		val buf = Buffer(file)
		val level = (parent?.level ?: 0) + 1
		val start = if (file.settings.capturePosition == Position.FIRST) buf.prependHeading(parent, level) else buf.appendHeading(parent, level)
		buf.applyChanges(start, edit.changes)
		return WriteResult.Written(buf.render(), start)
	}

	private fun delete(file: OrgFile, edit: Edit.Delete): WriteResult {
		val h = locate(file, edit.anchor) ?: return WriteResult.Conflict
		val buf = Buffer(file)
		buf.lines.subList(h.start, h.subtreeEnd).clear()
		return WriteResult.Written(buf.render(), h.start)
	}

	// org-refile within one file: cut the subtree, then paste it under the target. A target inside the
	// moved subtree is refused, as Org refuses it.
	private fun refile(file: OrgFile, edit: Edit.Refile): WriteResult {
		val h = locate(file, edit.anchor) ?: return WriteResult.Conflict
		val target = edit.target?.let { locate(file, it) ?: return WriteResult.Conflict }
		require(target == null || target.start < h.start || target.start >= h.subtreeEnd) { "Cannot refile a subtree into itself" }
		val cut = Buffer(file)
		val span = cut.lines.subList(h.start, h.subtreeEnd).toList()
		cut.lines.subList(h.start, h.subtreeEnd).clear()
		val rest = OrgFile.parse(file.path, cut.render(), settings)
		val targetStart = target?.let { if (it.start > h.start) it.start - span.size else it.start }
		val moved = rest.document.headings.firstOrNull { it.start == targetStart }
		return pasteSubtree(rest, moved, span, h.level)
	}

	// The subtree [span] of level [level] pasted under [target] in [file] (at level 1 w/o a target), as
	// org-refile pastes it per the refile position: last, at the end of the target's subtree (the end of
	// the file); first, right after the target's own section, where outline-next-heading goes from the
	// target, or w/o a target where it goes from the file's start, which passes over a heading on the
	// first line, as Org's does.
	private fun pasteSubtree(file: OrgFile, target: Heading?, span: List<Line>, level: Int): WriteResult.Written {
		val buf = Buffer(file)
		val headings = file.document.headings
		val at = when {
			file.settings.refilePosition == Position.LAST -> target?.subtreeEnd ?: buf.lines.size
			target != null -> target.end
			else -> headings.firstOrNull { it.start > 0 }?.start ?: buf.lines.size
		}
		buf.paste(at, span, (target?.level ?: 0) + 1 - level)
		return WriteResult.Written(buf.render(), at)
	}

	// A move to another file, in three steps, so that a failure leaves a copy and never loses the entry
	// (DESIGN.md section 3): subtree() takes the subtree from the source text; refileIn() gives the new
	// target text, which the app writes first; refileOut() then gives the new source text.

	// The subtree at [anchor] of [sourceText], or null for a conflict: the anchor finds no heading.
	fun subtree(sourceText: String, anchor: Anchor): Subtree? {
		val file = parse(sourceText)
		val h = locate(file, anchor) ?: return null
		return Subtree(Anchor.of(file, h), file.document.lines.subList(h.start, h.subtreeEnd).toList(), h.level)
	}

	// [subtree] pasted into [targetText] under [target], its stars shifted to one level below it, per
	// the refile position; at level 1 when [target] is null.
	fun refileIn(targetText: String, target: Anchor?, subtree: Subtree): WriteResult {
		val file = parse(targetText)
		val heading = target?.let { locate(file, it) ?: return WriteResult.Conflict }
		return pasteSubtree(file, heading, subtree.lines, subtree.level)
	}

	// [subtree] removed from [sourceText] once it is found there unchanged; a Conflict otherwise, and the
	// target keeps its copy.
	fun refileOut(sourceText: String, subtree: Subtree): WriteResult {
		val file = parse(sourceText)
		val h = locate(file, subtree.anchor) ?: return WriteResult.Conflict
		val now = file.document.lines.subList(h.start, h.subtreeEnd).map { it.text }
		if (now != subtree.lines.map { it.text }) return WriteResult.Conflict
		return delete(file, Edit.Delete(subtree.anchor))
	}

	// The lines of one file under edit, w/ the file's own settings.
	private inner class Buffer(private val file: OrgFile) {
		val lines: MutableList<Line> = file.document.lines.toMutableList()
		private val eol = file.document.lineEnding
		private val keywords = file.keywords
		private val settings = file.settings

		// The log notes of the edit, each stored once its changes are done, as Org stores a note from
		// post-command-hook after the command.
		private val pending = ArrayList<List<String>>()

		fun render(): String = OrgDocument.render(file.document.bom, lines)

		private fun setText(i: Int, text: String) {
			lines[i] = lines[i].copy(text = text)
		}

		// Insert [texts] after line [i], as Emacs does when it inserts a newline and text at the end of
		// line i: line i and each new line but the last end w/ the file's line ending, and the last new
		// line takes the terminator that line i had.
		private fun insertAfter(i: Int, texts: List<String>) {
			if (texts.isEmpty()) return
			val old = lines[i].eol
			lines[i] = lines[i].copy(eol = eol)
			lines.addAll(i + 1, texts.mapIndexed { k, t -> Line(t, if (k == texts.lastIndex) old else eol) })
		}

		// Replace lines [from, to) w/ [texts]. The last new line keeps the terminator of the last old
		// one; the others take the file's line ending. An empty range inserts after line from - 1.
		private fun replace(from: Int, to: Int, texts: List<String>) {
			if (from == to) {
				insertAfter(from - 1, texts)
				return
			}
			val lastEol = lines[to - 1].eol
			lines.subList(from, to).clear()
			lines.addAll(from, texts.mapIndexed { k, t -> Line(t, if (k == texts.lastIndex) lastEol else eol) })
		}

		private fun section(start: Int) = Section.of(lines, start)

		private fun headline(i: Int): MatchResult =
			HeadlineRegex.complex(keywords.all).matchEntire(lines[i].text) ?: error("Not a heading line: ${lines[i].text}")

		// The changes go in a fixed order. The planning changes come before the state, since Org's
		// org-schedule and org-deadline drop CLOSED, and a task marked done in the same edit keeps it.
		// The timestamps go ahead of the body, which follows them in an event. The log notes go last.
		fun applyChanges(start: Int, changes: List<FieldChange>) {
			for (c in changes.sortedBy { rank(it) }) {
				when (c) {
					is FieldChange.Title -> setTitle(start, c.title)
					is FieldChange.Priority -> setPriority(start, c.value)
					is FieldChange.Tags -> setTags(start, c.tags)
					is FieldChange.Scheduled -> setPlanning(start, PlanKey.SCHEDULED, c.timestamp)
					is FieldChange.Deadline -> setPlanning(start, PlanKey.DEADLINE, c.timestamp)
					is FieldChange.State -> setState(start, c.keyword, c.note)
					is FieldChange.Property -> setProperty(start, c.key, c.value)
					is FieldChange.Timestamps -> setTimestamps(start, c.timestamps)
					is FieldChange.Body -> setBody(start, c.text)
				}
			}
			for (note in pending) insertLogNote(start, note)
			pending.clear()
		}

		private fun rank(c: FieldChange): Int = when (c) {
			is FieldChange.Title -> 0
			is FieldChange.Priority -> 1
			is FieldChange.Tags -> 2
			is FieldChange.Scheduled -> 3
			is FieldChange.Deadline -> 4
			is FieldChange.State -> 5
			is FieldChange.Property -> 6
			is FieldChange.Timestamps -> 7
			is FieldChange.Body -> 8
		}

		// A new heading line at the end of [parent]'s subtree (the file's end w/o a parent), as
		// org-insert-heading w/ respect-content places it: w/ org-blank-before-new-entry 'auto, a blank
		// line goes before it when [parent] (the last top-level heading w/o one) has one. The run of
		// blank lines before the insertion point is kept when a blank line is wanted and one is there;
		// else it becomes exactly the one wanted (or none), as org-N-empty-lines-before-current makes
		// it; and a blank line goes after the new heading when one is wanted and a heading follows. The
		// new line always ends w/ a line ending, as Org inserts the stars w/ a newline. Returns the index
		// of the new heading line, which holds the stars and a space.
		fun appendHeading(parent: Heading?, level: Int): Int {
			val ref = parent ?: file.document.roots.lastOrNull()
			val blank = ref != null && blankBefore(ref)
			val from = parent?.start ?: 0
			val end = parent?.subtreeEnd ?: lines.size
			val last = OrgDocument.lastNonBlank(lines, from, end)
			val stars = "*".repeat(level) + " "
			if (last < 0) {
				val texts = (if (blank) listOf("") else emptyList()) + stars
				lines.addAll(0, texts.map { Line(it, eol) })
				return texts.size - 1
			}
			var at = end
			if (at == lines.size) ensureEol(at - 1)
			if (!(blank && OrgDocument.isBlank(lines[at - 1].text))) {
				lines.subList(last + 1, at).clear()
				at = last + 1
				if (blank) lines.add(at++, Line("", eol))
			}
			lines.add(at, Line(stars, eol))
			if (blank && at + 1 < lines.size && OrgDocument.headingLevel(lines[at + 1].text) > 0) lines.add(at + 1, Line("", eol))
			return at
		}

		// A new heading first under [parent], as org-capture w/ :prepend t places an entry: right after
		// the parent's own section (where outline-next-heading goes), or atop the file's first heading at
		// the top level. The blank lines before the insertion point go, and one blank line comes back
		// when the heading there (or, at the end of the file, the last heading) has one before it and the
		// line before the insertion point is not blank (org-capture-empty-lines-before); the blank lines
		// after the new entry go too (org-capture-empty-lines-after), though at a heading or the end of
		// the file there are none.
		fun prependHeading(parent: Heading?, level: Int): Int {
			val headings = file.document.headings
			var at = if (parent != null) parent.end else headings.firstOrNull()?.start ?: lines.size
			if (at == lines.size && at > 0) ensureEol(at - 1)
			val ref = if (at < lines.size) headings.firstOrNull { it.start == at } else headings.lastOrNull()
			val blank = ref != null && blankBefore(ref) && !(at > 0 && OrgDocument.isBlank(lines[at - 1].text))
			val last = OrgDocument.lastNonBlank(lines, 0, at)
			lines.subList(last + 1, at).clear()
			at = last + 1
			if (blank) lines.add(at++, Line("", eol))
			lines.add(at, Line("*".repeat(level) + " ", eol))
			return at
		}

		// Org's blank-before-heading test: the line before the heading is empty, or, for a heading on the
		// first line, the line before the next heading is.
		private fun blankBefore(h: Heading): Boolean {
			if (h.start > 0) return OrgDocument.isBlank(lines[h.start - 1].text)
			val next = file.document.headings.firstOrNull { it.start > h.start } ?: return false
			return OrgDocument.isBlank(lines[next.start - 1].text)
		}

		// org-paste-subtree, as org-refile calls it: the subtree [span] goes in whole at line [at], its
		// headings moved [delta] levels. Each line takes this file's line ending, as Emacs saves a buffer
		// w/ its own coding, so a subtree cut from the end of a file w/o a final newline gets one. The
		// tags of each heading moved to another level realign, as org-demote and org-promote realign
		// them, and so do those of the pasted heading, which org-refile aligns.
		fun paste(at: Int, span: List<Line>, delta: Int) {
			val moved = span.map { line ->
				val level = OrgDocument.headingLevel(line.text)
				val text = if (level == 0 || delta == 0) line.text else "*".repeat(level + delta) + line.text.substring(level)
				Line(text, eol)
			}
			if (at > 0) ensureEol(at - 1)
			lines.addAll(at, moved)
			for (i in at until at + moved.size) {
				if (OrgDocument.headingLevel(lines[i].text) > 0 && (i == at || delta != 0)) alignTags(i)
			}
		}

		// Give line [i] the file's line ending when it has none, as Emacs inserts a newline after the
		// last line ere it adds text below it.
		private fun ensureEol(i: Int) {
			if (lines[i].eol.isEmpty()) lines[i] = lines[i].copy(eol = eol)
		}

		// org-edit-headline: replace the title, realign the tags, and drop trailing blanks.
		private fun setTitle(hi: Int, raw: String) {
			val new = FormText.oneLine(raw)
			val m = headline(hi)
			val g = m.groups[4]
			if (g?.value == new || (g == null && new.isEmpty())) return
			val text = lines[hi].text
			val endOfNew: Int
			if (g != null) {
				setText(hi, text.replaceRange(g.range, new))
				endOfNew = g.range.first + new.length
			} else {
				val at = (m.groups[3] ?: m.groups[2] ?: m.groups[1])!!.range.last + 1
				setText(hi, text.substring(0, at) + " " + new + text.substring(at))
				endOfNew = at + 1 + new.length
			}
			alignTags(hi)
			val t = lines[hi].text
			if (OrgDocument.isBlank(t.substring(endOfNew))) setText(hi, t.substring(0, endOfNew))
		}

		// org-todo under the settings. Logging is on when org-log-done is set or a keyword of the file
		// has log marks (org-todo-log-states): then CLOSED goes on a return to an open state (or to
		// none), CLOSED comes on entry to a done state per org-log-done (w/ a CLOSING NOTE when it asks
		// for a note and the states ask for naught), and a state line (or a note) logs per the marks of
		// the new state's entry or the old state's leaving. On entry to a done state of an entry w/ a
		// repeater, org-auto-repeat-maybe follows. The tags realign.
		private fun setState(hi: Int, keyword: String?, note: String?) {
			val old = headline(hi).groups[2]?.value
			if (old == keyword) return
			require(keyword == null || keyword in keywords.all) { "Not a TODO keyword of this file: $keyword" }
			val nowDone = keywords.isDone(keyword) && !keywords.isDone(old)
			val repeatNow = nowDone && keyword != null && repeats(hi)
			// Org stops w/ a user error on an hourly repeat of a stamp w/o a time; refuse the edit whole.
			require(!repeatNow || repeating(hi).none { it.repeater?.unit == RepeatUnit.HOUR && it.start == null }) {
				"Cannot repeat in hours w/o a time"
			}
			replaceKeyword(hi, keyword)
			val logging = settings.logDone != LogMode.NONE || keywords.sequences.any { it.logs.isNotEmpty() }
			var log: LogMode? = null
			var closing = false
			if (logging) {
				log = keywords.logOf(keyword)?.enter?.takeIf { it != LogMode.NONE }
					?: keywords.logOf(old)?.leave?.takeIf { it != LogMode.NONE }
				if (keyword == null || (keyword in keywords.open && old !in keywords.open)) addPlanning(hi, null, null, listOf(PlanKey.CLOSED))
				if (nowDone && settings.logDone != LogMode.NONE) {
					addPlanning(hi, PlanKey.CLOSED, logStamp(), emptyList())
					if (log == null && settings.logDone == LogMode.NOTE) {
						pending.add(noteLines("CLOSING NOTE ${logStamp()}", note))
						closing = true
					}
				}
			}
			val from = old?.let { "\"$it\"" } ?: ""
			val stateNote = if (keyword != null && log != null) stateLine(keyword, from, log, note) else null
			if (repeatNow && keyword != null) {
				repeat(hi, old, keyword, stateNote, from, closing, note)
				return
			}
			stateNote?.let { pending.add(it) }
			alignTags(hi)
		}

		// The note test of setState and repeat, w/o the edit.
		fun asksNote(hi: Int, keyword: String?): Boolean {
			val old = headline(hi).groups[2]?.value
			if (keyword == null || old == keyword) return false
			val nowDone = keywords.isDone(keyword) && !keywords.isDone(old)
			val logging = settings.logDone != LogMode.NONE || keywords.sequences.any { it.logs.isNotEmpty() }
			val log = if (!logging) null else keywords.logOf(keyword)?.enter?.takeIf { it != LogMode.NONE }
				?: keywords.logOf(old)?.leave?.takeIf { it != LogMode.NONE }
			val closing = logging && nowDone && settings.logDone != LogMode.NONE && log == null && settings.logDone == LogMode.NOTE
			if (nowDone && repeats(hi)) {
				return when {
					log != null -> log == LogMode.NOTE || settings.logRepeat == LogMode.NOTE
					closing -> true
					else -> settings.logRepeat == LogMode.NOTE
				}
			}
			return log == LogMode.NOTE || closing
		}

		// The lines of a state change log, as org-log-note-headings' "State %-12s from %-12S %t" gives
		// them, w/ the note when [how] asks for one.
		private fun stateLine(state: String, from: String, how: LogMode, note: String?): List<String> =
			noteLines(String.format(Locale.ROOT, "State %-12s from %-12s %s", "\"$state\"", from, logStamp()), if (how == LogMode.NOTE) note else null)

		// A log note as org-store-log-note writes it: "- " and the heading, and when the note has text,
		// " \\" after the heading and each note line below it indented to the item's body (two spaces).
		// The note loses its trailing whitespace and a leading line break.
		private fun noteLines(heading: String, note: String?): List<String> {
			val text = note?.replace("\r\n", "\n")?.replace('\r', '\n')?.trimEnd()?.trimStart('\n').orEmpty()
			if (text.isEmpty()) return listOf("- $heading")
			return listOf("- $heading \\\\") + text.split('\n').map { if (it.isEmpty()) it else "  $it" }
		}

		// The keyword and the blanks around it, as org-todo replaces them.
		private fun replaceKeyword(hi: Int, keyword: String?) {
			val text = lines[hi].text
			val stars = text.indexOfFirst { it != '*' }
			val rest = text.substring(stars)
			val m = Regex("^ +(?:${HeadlineRegex.keywordAlternation(keywords.all)})(?: +|[ \\t]*\\z)").find(rest)
				?: Regex("^ *").find(rest)!!
			val next = if (keyword == null) " " else " $keyword "
			setText(hi, text.substring(0, stars) + next + rest.substring(m.range.last + 1))
		}

		private fun now(): LocalDateTime = clock().withSecond(0).withNano(0)

		// The inactive stamp of now, as CLOSED, :LAST_REPEAT:, and the log notes write it.
		private fun logStamp(): String = Timestamp(now().toLocalDate(), now().toLocalTime(), active = false).format()

		// The active timestamps of the entry that carry a repeater (habits too), in file order: those
		// that org-repeat-re finds.
		private fun repeating(hi: Int): List<Timestamp> {
			val s = section(hi)
			return (s.start until s.end).flatMap { i ->
				Timestamp.ACTIVE.findAll(lines[i].text).mapNotNull { Timestamp.parse(it.value)?.takeIf { ts -> ts.repeater != null } }.toList()
			}
		}

		// org-get-repeat: the first repeating timestamp of the entry decides, and a count of zero
		// repeats naught.
		private fun repeats(hi: Int): Boolean = (repeating(hi).firstOrNull()?.repeater?.value ?: 0) > 0

		// org-auto-repeat-maybe w/ org-todo-repeat-to-state nil, after org-todo has logged the done
		// state: the keyword goes back to the head of its sequence (or stays, for a #+TYP_TODO: one;
		// :REPEAT_TO_STATE: wins), CLOSED goes, :LAST_REPEAT: takes the time when org-log-repeat is set
		// or the entry holds a clock line, a SCHEDULED w/o a repeater goes, and each repeating active
		// timestamp of the entry moves on. The log is the state line org-todo set up ([stateNote]),
		// turned into a note when org-log-repeat asks for one; or the CLOSING NOTE it set up ([closing]),
		// as org-auto-repeat-maybe leaves a setup in place; else a line of its own per org-log-repeat,
		// w/ the old state quoted even when there was none, as org-last-state gives it.
		private fun repeat(hi: Int, old: String?, doneWord: String, stateNote: List<String>?, from: String, closing: Boolean, note: String?) {
			val sequence = keywords.sequenceOf(old)
			val toState = section(hi).property(lines, FieldChange.REPEAT_TO_STATE)?.value?.takeIf { it in keywords.all }
				?: when {
					sequence == null -> null
					sequence.type -> old
					else -> sequence.head
				}
			replaceKeyword(hi, toState)
			addPlanning(hi, null, null, listOf(PlanKey.CLOSED))
			val stamp = logStamp()
			val s = section(hi)
			val clocked = (s.start + 1 until s.end).any { CLOCK_LINE.matches(lines[it].text) }
			if (settings.logRepeat != LogMode.NONE || clocked) setProperty(hi, FieldChange.LAST_REPEAT, stamp)
			val scheduled = section(hi).planning(lines).scheduled
			if (scheduled != null && scheduled.repeater == null) removePlanning(hi, PlanKey.SCHEDULED)
			val today = now().toLocalDate()
			val entry = section(hi)
			for (i in entry.start until entry.end) {
				val text = lines[i].text
				val moved = Timestamp.ACTIVE.replace(text) { m ->
					val ts = Timestamp.parse(m.value)
					if (ts == null || ts.repeater == null) m.value else nextRepeat(ts, today).format()
				}
				if (moved != text) setText(i, moved)
			}
			val log = when {
				stateNote != null && settings.logRepeat == LogMode.NOTE -> stateLine(doneWord, from, LogMode.NOTE, note)
				stateNote != null -> stateNote
				closing -> null
				settings.logRepeat != LogMode.NONE -> stateLine(doneWord, "\"${old.orEmpty()}\"", settings.logRepeat, note)
				else -> null
			}
			log?.let { pending.add(it) }
			alignTags(hi)
		}

		// The next date of a repeating timestamp, as org-auto-repeat-maybe moves it: "+" by one
		// interval; ".+" to one interval after today (after now, for hours); "++" to the first repeat
		// after today, found as Org finds it (step on until past today, back one, on one, each step w/
		// org-timestamp-change's month overflow). A habit's suffix stays as written. A count of zero:
		// ".+0d" still moves to today, as Org does; "+0d" stays; "++0d" stays too, where Org would step
		// in place and then ask whether to go on (a prompt the app cannot answer).
		private fun nextRepeat(ts: Timestamp, today: LocalDate): Timestamp {
			val r = ts.repeater ?: return ts
			val n = r.value.toLong()
			var t = ts
			when (r.mark) {
				".+" -> t = if (r.unit == RepeatUnit.HOUR) t.at(now()) else t.copy(date = today)
				"++" -> if (n > 0) {
					do {
						t = t.shifted(n, r.unit)
					} while (if (r.unit == RepeatUnit.HOUR) !t.moment.isAfter(now()) else !t.date.isAfter(today))
					t = t.shifted(-n, r.unit)
				}
			}
			// The last step is org-timestamp-change w/ suppress-tmp-delay, which drops a "--N" delay.
			val next = t.shifted(n, r.unit)
			return if (next.warning?.startsWith("--") == true) next.copy(warning = null, warningFirst = false) else next
		}

		// org-log-beginning and org-store-log-note, w/ org-log-states-order-reversed t (Org's default),
		// so each note goes above the older ones. W/o a log drawer, the note goes after the planning
		// line and the property drawer (and, w/ org-log-state-notes-insert-after-drawers, after every
		// drawer and clock line too), past blank lines, ahead of the first line w/ text, which moves
		// down; a blank line there takes the note at its start. At the end of the file the note gets no
		// newline (and a last line w/o one gets one). W/ a log drawer, the note goes first in the drawer
		// of that name, which is made after the planning line and the property drawer when missing.
		// Limit: a LOG_INTO_DRAWER or LOGGING property is not read.
		private fun insertLogNote(hi: Int, note: List<String>) {
			val drawer = settings.logIntoDrawer
			if (drawer != null) {
				noteAt(logDrawer(hi, drawer) + 1, note)
				return
			}
			var i = if (settings.logNotesAfterDrawers) afterDrawers(hi) else section(hi).metaEnd
			while (i < lines.size && OrgDocument.isBlank(lines[i].text) && lines[i].eol.isNotEmpty()) i++
			noteAt(i, note)
		}

		// The note lines put at the start of line [i], as org-store-log-note puts them at a line start.
		private fun noteAt(i: Int, note: List<String>) {
			when {
				i >= lines.size -> {
					if (i > 0) ensureEol(i - 1)
					lines.addAll(note.mapIndexed { k, t -> Line(t, if (k == note.lastIndex) "" else eol) })
				}
				OrgDocument.isBlank(lines[i].text) -> {
					setText(i, note.last() + lines[i].text)
					lines.addAll(i, note.dropLast(1).map { Line(it, eol) })
				}
				else -> lines.addAll(i, note.map { Line(it, eol) })
			}
		}

		// The line after the planning line, the property drawer, every other drawer, clock lines, and
		// blank lines, as org-end-of-meta-data t finds it.
		private fun afterDrawers(hi: Int): Int {
			val s = section(hi)
			var i = s.metaEnd
			while (i < s.end) {
				val text = lines[i].text
				when {
					OrgDocument.isBlank(text) || CLOCK_LINE.matches(text) -> i++
					Section.DRAWER.matches(text) -> i = ((i + 1 until s.end).firstOrNull { Section.END.matches(lines[it].text) } ?: return i) + 1
					else -> return i
				}
			}
			return i
		}

		// The index of the opening line of the log drawer [name] of the heading at [hi], made when
		// missing as org-log-beginning makes it: after the planning line and the property drawer, ahead
		// of the next heading or the text there; at the end of a file that ends w/ a newline, its :END:
		// gets none.
		private fun logDrawer(hi: Int, name: String): Int {
			val s = section(hi)
			val open = Regex("[ \\t]*:" + Regex.escape(name) + ":[ \\t]*", RegexOption.IGNORE_CASE)
			for (i in s.metaEnd until s.end) {
				if (open.matches(lines[i].text) && (i + 1 until s.end).any { Section.END.matches(lines[it].text) }) return i
			}
			val at = s.metaEnd
			val texts = listOf(":$name:", ":END:")
			when {
				at < s.end -> lines.addAll(at, texts.map { Line(it, eol) })
				at < lines.size || lines[at - 1].eol.isEmpty() -> insertAfter(at - 1, texts)
				else -> lines.addAll(at, listOf(Line(texts[0], eol), Line(texts[1], "")))
			}
			return at
		}

		// org-priority: replace, remove, or insert the cookie, and realign the tags.
		private fun setPriority(hi: Int, value: String?) {
			val m = headline(hi)
			val cookie = m.groups[3]
			val old = cookie?.value?.let { HeadlineRegex.cookieValue(it) }
			if (old == value) return
			require(value == null || value in file.priorities.values) { "Not a priority of this file: $value" }
			val text = lines[hi].text
			if (cookie != null) {
				if (value == null) {
					val after = cookie.range.last + 1
					val cut = if (after < text.length && text[after] == ' ') after + 1 else after
					setText(hi, text.removeRange(cookie.range.first, cut))
				} else {
					setText(hi, text.replaceRange(cookie.range, "[#$value]"))
				}
			} else if (value != null) {
				val at = when {
					m.groups[2] != null -> m.groups[2]!!.range.last + 1
					m.groups[4] != null -> m.groups[4]!!.range.first
					else -> m.groups[1]!!.range.last + 1
				}
				val cookieText = if (m.groups[2] == null && m.groups[4] != null) "[#$value] " else " [#$value]"
				setText(hi, text.substring(0, at) + cookieText + text.substring(at))
			}
			alignTags(hi)
		}

		// org-set-tags: drop the old tags and the blanks before them, append the new ones, and align.
		private fun setTags(hi: Int, tags: List<String>) {
			for (t in tags) require(HeadlineRegex.TAG.matches(t)) { "Not a valid tag: $t" }
			if (HeadlineRegex.splitTags(headline(hi).groups[5]?.value) == tags) return
			var text = lines[hi].text
			val tagsStart = HeadlineRegex.TAG_LINE.matchEntire(text)?.groups?.get(1)?.range?.first ?: text.length
			text = text.substring(0, OrgDocument.blankStart(text, tagsStart))
			if (OrgDocument.headingLevel(text) == 0) text += " "
			if (tags.isNotEmpty()) text += " :" + tags.joinToString(":") + ":"
			setText(hi, text)
			if (tags.isNotEmpty()) alignTags(hi)
		}

		// org--align-tags-here under org-tags-column (OrgSettings.tagsColumn): a negative column ends the
		// tags there, zero or more starts them there, w/ at least one space before them (so 0 gives one
		// space). Spaces only; the line is left alone when the tags already start at that column.
		private fun alignTags(hi: Int) {
			val text = lines[hi].text
			val g = HeadlineRegex.TAG_LINE.matchEntire(text)?.groups?.get(1) ?: return
			val tagsStart = g.range.first
			val blank = OrgDocument.blankStart(text, tagsStart)
			val blankCol = Width.columns(text, blank)
			val col = settings.tagsColumn
			val want = maxOf(if (col >= 0) col else -col - Width.columns(g.value, g.value.length), blankCol + 1)
			if (want == Width.columns(text, tagsStart)) return
			setText(hi, text.substring(0, blank) + " ".repeat(want - blankCol) + text.substring(tagsStart))
		}

		// SCHEDULED or DEADLINE: org-schedule and org-deadline write through org-add-planning-info,
		// which also drops CLOSED; a removal goes through org-remove-timestamp-with-keyword. A changed or
		// removed date logs per org-log-reschedule or org-log-redeadline, as org--deadline-or-schedule
		// sets up the note: the old stamp quoted w/ square brackets.
		private fun setPlanning(hi: Int, key: PlanKey, ts: Timestamp?) {
			val line = section(hi).planningLine?.let { lines[it].text }
			val current = line?.let { Planning.parse(it) } ?: Planning.NONE
			val old = if (key == PlanKey.DEADLINE) current.deadline else current.scheduled
			if (old == ts) return
			val oldText = line?.let { key.regex.find(it)?.groupValues?.get(1) }
			val log = if (key == PlanKey.DEADLINE) settings.logRedeadline else settings.logReschedule
			if (ts == null) removePlanning(hi, key) else addPlanning(hi, key, ts.format(), listOf(PlanKey.CLOSED))
			if (oldText != null && log != LogMode.NONE) {
				val heading = when {
					key == PlanKey.DEADLINE && ts == null -> "Removed deadline, was"
					key == PlanKey.DEADLINE -> "New deadline from"
					ts == null -> "Not scheduled, was"
					else -> "Rescheduled from"
				}
				pending.add(noteLines("$heading \"[$oldText]\" on ${logStamp()}", null))
			}
		}

		// org-add-planning-info: remove [what] and the [remove] keywords from the planning line (each up
		// to the next keyword), then put "WHAT stamp" first on the line. W/o [what], a line left empty
		// goes away.
		private fun addPlanning(hi: Int, what: PlanKey?, stamp: String?, remove: List<PlanKey>) {
			val pi = section(hi).planningLine
			var s: String
			val p: Int
			if (pi != null) {
				s = lines[pi].text
				p = s.indexOfFirst { !OrgDocument.isBlankChar(it) }.let { if (it < 0) s.length else it }
				for (type in listOfNotNull(what) + remove) {
					val m = type.regex.find(s, p) ?: continue
					val next = PlanKey.ANY.find(s, m.range.last + 1)
					s = s.removeRange(m.range.first, next?.range?.first ?: s.length)
				}
				if (what == null && OrgDocument.isBlank(s.substring(p))) {
					// The heading takes the terminator of the deleted line, as Emacs deletes from the end
					// of the heading line to the end of the planning line.
					lines[hi] = lines[hi].copy(eol = lines[pi].eol)
					lines.removeAt(pi)
					return
				}
				s = s.substring(0, OrgDocument.blankStart(s, s.length, p))
				if (what == null) {
					setText(pi, s)
					return
				}
			} else {
				if (what == null) return
				insertAfter(hi, listOf(""))
				s = ""
				p = 0
			}
			val word = what?.word ?: return
			val rest = s.substring(p)
			setText(hi + 1, s.substring(0, p) + word + " " + stamp.orEmpty() + (if (rest.isEmpty()) "" else " $rest"))
		}

		// org-remove-timestamp-with-keyword: from the end of the entry's own section back to its heading
		// line, remove each "KEY <...>" and the blanks after it, then one space before it, or the whole
		// line when naught is left. Org searches the whole entry, so a "DEADLINE: <...>" in the body goes
		// too, which DESIGN.md section 3 meant to keep.
		private fun removePlanning(hi: Int, key: PlanKey) {
			for (i in section(hi).end - 1 downTo hi) removeKeyword(i, key.removal)
		}

		// The removals of removePlanning on line [i], from its last match back.
		private fun removeKeyword(i: Int, re: Regex) {
			var s = lines[i].text
			val found = re.findAll(s).toList()
			if (found.isEmpty()) return
			for (m in found.asReversed()) {
				val at = m.range.first
				s = s.removeRange(m.range)
				// Org tests the text before the stamp w/ "\\S-", any non-whitespace character, so
				// isWhitespace (not isBlankChar) is the match here.
				if (at > 0 && s.substring(0, at).any { !it.isWhitespace() } && s[at - 1] == ' ') {
					s = s.removeRange(at - 1, at)
				} else if (OrgDocument.isBlank(s)) {
					lines.removeAt(i)
					return
				}
			}
			setText(i, s)
		}

		// org-entry-put: replace the property line, or add one before :END:, creating the drawer after
		// the planning line when the heading has none. org-entry-delete for a null value: every line of
		// the key (and of KEY+) goes, and a drawer left empty goes too.
		private fun setProperty(hi: Int, key: String, value: String?) {
			var s = section(hi)
			val cur = s.property(lines, key)
			val clean = value?.let { FormText.oneLine(it) }
			if (cur?.value == clean) return
			if (clean == null) {
				val doomed = s.properties(lines).filter { it.key.equals(key, true) || it.key.equals("$key+", true) }
				for (p in doomed.asReversed()) lines.removeAt(p.index)
				s = section(hi)
				val open = s.drawerStart
				val close = s.drawerEnd
				if (open != null && close != null && close - open == 1) lines.subList(open, close + 1).clear()
				return
			}
			val formatted = String.format(Locale.ROOT, "%-10s %s", ":$key:", clean).trim { OrgDocument.isBlankChar(it) }
			if (cur != null) {
				setText(cur.index, formatted)
				return
			}
			if (s.drawerStart == null) {
				insertDrawer(s.planningLine ?: hi)
				s = section(hi)
			}
			lines.add(s.drawerEnd!!, Line(formatted, eol))
		}

		// org-insert-property-drawer: ":PROPERTIES:" and ":END:" after the heading or planning line;
		// at the end of a file w/o a final newline, ":END:" gets one.
		private fun insertDrawer(anchor: Int) {
			insertAfter(anchor, listOf(":PROPERTIES:", ":END:"))
			ensureEol(anchor + 2)
		}

		// The leading stamp lines of the body (an event's timestamps): only the lines whose values change
		// are rewritten. The lines that open and close the list unchanged stay byte for byte, and the
		// rest is replaced; w/o stamp lines, the new ones go at the start of the body.
		private fun setTimestamps(hi: Int, stamps: List<Timestamp>) {
			val s = section(hi)
			val current = s.stamps(lines)
			if (current == stamps) return
			var head = 0
			while (head < current.size && head < stamps.size && current[head] == stamps[head]) head++
			var tail = 0
			while (tail < current.size - head && tail < stamps.size - head &&
				current[current.size - 1 - tail] == stamps[stamps.size - 1 - tail]
			) tail++
			replace(s.bodyStart + head, s.stampEnd - tail, stamps.subList(head, stamps.size - tail).map { it.format() })
		}

		// The body up to the last non-blank line of the section; trailing blank lines stay. In an event
		// (after any Timestamps change of the same edit, rank 7) the text replaces the body after the
		// leading stamp lines. The text goes in as typed.
		private fun setBody(hi: Int, text: String) {
			val s = section(hi)
			val from = if (s.isEvent(lines)) s.stampEnd else s.bodyStart
			val new = FormText.lines(text)
			if ((from until s.bodyEnd).map { lines[it].text } == new) return
			replace(from, s.bodyEnd, new)
		}
	}

	companion object {
		// A clock line, as org-clock-line-re finds one.
		private val CLOCK_LINE = Regex("[ \\t]*CLOCK:.*", RegexOption.DOT_MATCHES_ALL)
	}
}

// Display width, as Emacs's current-column counts it: tabs to the next multiple of 8, zero for
// combining marks and format characters (the zero-width space among them), two for East Asian wide
// characters. The wide ranges are the main blocks only, not Emacs's full char-width table.
internal object Width {
	fun columns(text: String, end: Int): Int {
		var col = 0
		var i = 0
		while (i < end) {
			val cp = text.codePointAt(i)
			col = if (cp == '\t'.code) (col / 8 + 1) * 8 else col + of(cp)
			i += Character.charCount(cp)
		}
		return col
	}

	private fun of(cp: Int): Int {
		val type = Character.getType(cp)
		if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() || type == Character.FORMAT.toInt()) return 0
		return if (wide(cp)) 2 else 1
	}

	private fun wide(cp: Int): Boolean =
		cp in 0x1100..0x115F || (cp in 0x2E80..0xA4CF && cp != 0x303F) || cp in 0xAC00..0xD7A3 ||
			cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 ||
			cp in 0x1F300..0x1F64F || cp in 0x1F900..0x1F9FF || cp in 0x20000..0x3FFFD
}
