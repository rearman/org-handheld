package com.rearman.orgpalm.org

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

// One field of a record set to a new value. Null clears the field.
sealed interface FieldChange {
	data class Title(val title: String) : FieldChange
	data class State(val keyword: String?) : FieldChange
	data class Priority(val value: String?) : FieldChange
	data class Tags(val tags: List<String>) : FieldChange
	data class Scheduled(val timestamp: Timestamp?) : FieldChange
	data class Deadline(val timestamp: Timestamp?) : FieldChange
	data class Property(val key: String, val value: String?) : FieldChange
	data class Timestamps(val timestamps: List<Timestamp>) : FieldChange
	data class Body(val text: String) : FieldChange

	companion object {
		const val ID = "ID"
		const val WARN_TIME = "APPT_WARNTIME"
		const val CATEGORY = "CATEGORY"
		const val LAST_REPEAT = "LAST_REPEAT"
		const val REPEAT_TO_STATE = "REPEAT_TO_STATE"

		fun warnMinutes(minutes: Int?): FieldChange = Property(WARN_TIME, minutes?.toString())

		// A category rename writes both the heading text and :CATEGORY: (DESIGN.md section 3).
		fun categoryName(name: String): List<FieldChange> = listOf(Title(name), Property(CATEGORY, name))
	}
}

// An edit to one file, found by :ID: (or a category by name), so that it can be applied again to
// fresh text (Rebase.kt).
sealed interface Edit {
	data class Update(val id: String, val changes: List<FieldChange>) : Edit

	// A new record at the end of the heading [parentId], or at the end of the file when it is null.
	data class Append(val parentId: String?, val changes: List<FieldChange>) : Edit

	// A new task or note at the end of the category named [category], made first (w/ an :ID: and
	// :CATEGORY:) when the file has none of that name, as the first filing under Unfiled does.
	data class AppendTo(val category: String, val changes: List<FieldChange>) : Edit

	// A task or note moved to the end of the category named [category] (made when missing), its text
	// and :ID: kept, as org-refile moves it.
	data class Move(val id: String, val category: String) : Edit

	data class Delete(val id: String) : Edit
}

sealed interface WriteResult {
	// The new text of the file, and the :ID: of the record written (a new one for an append).
	data class Written(val text: String, val id: String) : WriteResult

	// The record (or, for an Append, the parent) w/ this :ID: is gone from the text.
	data class RecordDeleted(val id: String) : WriteResult

	// The record is read-only (OrgFile.isReadOnly): the app opens it w/o an editable form, and an
	// Update to it changes naught.
	data class ReadOnly(val id: String) : WriteResult

	// The heading w/ this :ID: is no task or note at its file's record level, so it cannot move
	// between categories.
	data class NotARecord(val id: String) : WriteResult
}

// Span edits per DESIGN.md section 4: each change rewrites only the lines its field owns, in the way the
// Org command for that field rewrites them (tools/make-fixtures.el makes the fixtures that check this).
// [clock] gives the time of a CLOSED stamp, of a repeat, and of today; [newId] the UUID of a new :ID:.
class OrgWriter(
	private val clock: () -> LocalDateTime,
	private val newId: () -> String = { UUID.randomUUID().toString() },
) {
	// Apply [edit], then give each record and category heading that lacks an :ID: a new one, so that a
	// heading Emacs added gets its ID on the app's next write (DESIGN.md section 3).
	fun apply(text: String, kind: FileKind, edit: Edit): WriteResult {
		val result = applyOnly(OrgFile.parse(kind, text), edit)
		return if (result is WriteResult.Written) result.copy(text = addMissingIds(result.text, kind)) else result
	}

	// A block body: the branches return early, which Kotlin forbids in an expression body.
	private fun applyOnly(file: OrgFile, edit: Edit): WriteResult { return when (edit) {
		is Edit.Update -> {
			val h = file.find(edit.id) ?: return WriteResult.RecordDeleted(edit.id)
			if (file.isReadOnly(h)) return WriteResult.ReadOnly(edit.id)
			val buf = Buffer(file)
			buf.applyChanges(h.start, h.level, edit.changes)
			WriteResult.Written(buf.render(), edit.id)
		}
		is Edit.Delete -> {
			val h = file.find(edit.id) ?: return WriteResult.RecordDeleted(edit.id)
			val buf = Buffer(file)
			buf.lines.subList(h.start, h.subtreeEnd).clear()
			WriteResult.Written(buf.render(), edit.id)
		}
		is Edit.Append -> {
			val parent = edit.parentId?.let { file.find(it) ?: return WriteResult.RecordDeleted(it) }
			append(file, parent, edit.changes)
		}
		is Edit.AppendTo -> {
			val (target, parent) = withCategory(file, edit.category)
			append(target, parent, edit.changes)
		}
		is Edit.Move -> {
			val h = file.find(edit.id) ?: return WriteResult.RecordDeleted(edit.id)
			if (!file.kind.hasCategories || !file.isRecord(h)) return WriteResult.NotARecord(edit.id)
			val cut = Buffer(file)
			val span = cut.lines.subList(h.start, h.subtreeEnd).toList()
			cut.lines.subList(h.start, h.subtreeEnd).clear()
			val (target, parent) = withCategory(OrgFile.parse(file.kind, cut.render()), edit.category)
			val buf = Buffer(target)
			buf.paste(parent.subtreeEnd, span, parent.level + 1 - h.level)
			WriteResult.Written(buf.render(), edit.id)
		}
	} }

	// A new heading at the end of [parent] (the file w/o one), w/ a new :ID: and [changes].
	private fun append(file: OrgFile, parent: Heading?, changes: List<FieldChange>): WriteResult.Written {
		val buf = Buffer(file)
		val level = (parent?.level ?: 0) + 1
		val start = buf.appendHeading(parent, level)
		val id = newId()
		buf.applyChanges(start, level, listOf(FieldChange.Property(FieldChange.ID, id)) + changes)
		return WriteResult.Written(buf.render(), id)
	}

	// The category heading [name] of [file], made at the file's end when missing, w/ the file that
	// holds it. A name that is blank once normalized, or a file w/o categories, is refused.
	private fun withCategory(file: OrgFile, name: String): Pair<OrgFile, Heading> {
		require(file.kind.hasCategories) { "${file.kind.fileName} has no categories" }
		val normal = file.categoryName(name)
		require(normal.isNotEmpty()) { "A category needs a name" }
		file.category(normal)?.let { return file to it }
		val made = OrgFile.parse(file.kind, append(file, null, FieldChange.categoryName(normal)).text)
		val heading = made.category(normal) ?: throw IllegalArgumentException("Category $normal does not read back")
		return made to heading
	}

	// Give each record heading (and each category heading) that lacks an :ID: a new one, as Org's
	// org-id-get-create would. Deeper headings, such as the children of a task, get none.
	fun addMissingIds(text: String, kind: FileKind): String {
		val file = OrgFile.parse(kind, text)
		val missing = file.document.headings.filter { (file.isRecord(it) || file.isCategory(it)) && file.id(it) == null }
		if (missing.isEmpty()) return text
		val ids = missing.map { newId() }
		val buf = Buffer(file)
		// From the last heading back, so that the lines added under one leave the earlier ones in place.
		for (i in missing.indices.reversed()) buf.applyChanges(missing[i].start, missing[i].level, listOf(FieldChange.Property(FieldChange.ID, ids[i])))
		return buf.render()
	}

	// The lines of one file under edit.
	private inner class Buffer(private val file: OrgFile) {
		val lines: MutableList<Line> = file.document.lines.toMutableList()
		private val eol = file.document.lineEnding
		private val keywords = file.keywords

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

		private fun section(start: Int, timestamps: Boolean) = Section.of(lines, start, timestamps)

		private fun headline(i: Int): MatchResult =
			HeadlineRegex.complex(keywords.all).matchEntire(lines[i].text) ?: error("Not a heading line: ${lines[i].text}")

		// The changes go in a fixed order. The planning changes come before the state, since Org's
		// org-schedule and org-deadline drop CLOSED, and a task marked done in the same edit keeps it.
		// The :ID: (first in an append) goes ahead of the other properties.
		fun applyChanges(start: Int, level: Int, changes: List<FieldChange>) {
			val timestamps = file.kind == FileKind.CALENDAR && level == file.kind.recordLevel
			for (c in changes.sortedBy { rank(it) }) {
				when (c) {
					is FieldChange.Title -> setTitle(start, c.title)
					is FieldChange.Priority -> setPriority(start, c.value)
					is FieldChange.Tags -> setTags(start, c.tags)
					is FieldChange.Scheduled -> setPlanning(start, PlanKey.SCHEDULED, c.timestamp)
					is FieldChange.Deadline -> setPlanning(start, PlanKey.DEADLINE, c.timestamp)
					is FieldChange.State -> setState(start, c.keyword)
					is FieldChange.Property -> setProperty(start, timestamps, c.key, c.value)
					is FieldChange.Timestamps -> setTimestamps(start, c.timestamps)
					is FieldChange.Body -> setBody(start, timestamps, c.text)
				}
			}
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
		// org-insert-heading w/ respect-content places it from [parent] (from the last top-level
		// heading w/o one): after the last non-blank line, w/ a blank line ahead of it when that
		// heading has one (org-blank-before-new-entry 'auto). Returns the index of the new heading line,
		// which holds the stars and a space.
		fun appendHeading(parent: Heading?, level: Int): Int {
			val anchor = parent ?: file.document.roots.lastOrNull()
			val blank = anchor != null && blankBefore(anchor)
			val from = parent?.start ?: 0
			val last = OrgDocument.lastNonBlank(lines, from, parent?.subtreeEnd ?: lines.size)
			val texts = (if (blank) listOf("") else emptyList()) + ("*".repeat(level) + " ")
			if (last < 0) {
				lines.addAll(0, texts.map { Line(it, eol) })
				return texts.size - 1
			}
			insertAfter(last, texts)
			return last + texts.size
		}

		// Org's blank-before-heading test: the line before the heading is empty, or, for a heading on the
		// first line, the line before the next heading is.
		private fun blankBefore(h: Heading): Boolean {
			if (h.start > 0) return OrgDocument.isBlank(lines[h.start - 1].text)
			val next = file.document.headings.firstOrNull { it.start > h.start } ?: return false
			return OrgDocument.isBlank(lines[next.start - 1].text)
		}

		// org-paste-subtree, as org-refile calls it: the subtree [span] goes in whole at line [at] (the
		// end of the new parent's subtree, after its trailing blank lines), its headings moved [delta]
		// levels. A subtree cut from the end of a file w/o a final newline gets one.
		fun paste(at: Int, span: List<Line>, delta: Int) {
			val moved = span.map { line ->
				val level = OrgDocument.headingLevel(line.text)
				if (level == 0 || delta == 0) line else line.copy(text = "*".repeat(level + delta) + line.text.substring(level))
			}.toMutableList()
			if (moved.last().eol.isEmpty()) moved[moved.lastIndex] = moved.last().copy(eol = eol)
			if (at > 0) ensureEol(at - 1)
			lines.addAll(at, moved)
		}

		// Give line [i] the file's line ending when it has none, as Emacs inserts a newline after the
		// last line ere it adds text below it.
		private fun ensureEol(i: Int) {
			if (lines[i].eol.isEmpty()) lines[i] = lines[i].copy(eol = eol)
		}

		// org-edit-headline: replace the title, realign the tags, and drop trailing blanks.
		private fun setTitle(hi: Int, raw: String) {
			val new = Escape.title(raw, keywords.all)
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

		// org-todo: replace the keyword, remove CLOSED on a return to an open state (or to none), write
		// CLOSED on entry to a done state, and realign the tags. On entry to a done state of a heading
		// w/ a repeater, org-auto-repeat-maybe runs instead of the CLOSED stamp.
		private fun setState(hi: Int, keyword: String?) {
			val old = headline(hi).groups[2]?.value
			if (old == keyword) return
			require(keyword == null || keyword in keywords.all) { "Not a TODO keyword of this file: $keyword" }
			val nowDone = keywords.isDone(keyword) && !keywords.isDone(old)
			if (nowDone && keyword != null && repeats(hi)) {
				// Org stops w/ a user error on an hourly repeat of a stamp w/o a time; refuse the edit whole.
				require(repeating(hi).none { it.repeater?.unit == RepeatUnit.HOUR && it.start == null }) {
					"Cannot repeat in hours w/o a time"
				}
				repeat(hi, old, keyword)
				return
			}
			replaceKeyword(hi, keyword)
			if (keyword == null || (keyword in keywords.open && old !in keywords.open)) addPlanning(hi, null, null, listOf(PlanKey.CLOSED))
			if (nowDone) addPlanning(hi, PlanKey.CLOSED, closedStamp().format(), emptyList())
			alignTags(hi)
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

		private fun closedStamp(): Timestamp = Timestamp(now().toLocalDate(), now().toLocalTime(), active = false)

		// The active timestamps of the entry that carry a repeater (habits too), in file order: those
		// that org-repeat-re finds.
		private fun repeating(hi: Int): List<Timestamp> {
			val s = section(hi, false)
			return (s.start until s.end).flatMap { i ->
				Timestamp.ACTIVE.findAll(lines[i].text).mapNotNull { Timestamp.parse(it.value)?.takeIf { ts -> ts.repeater != null } }.toList()
			}
		}

		// org-get-repeat: the first repeating timestamp of the entry decides, and a count of zero
		// repeats naught.
		private fun repeats(hi: Int): Boolean = (repeating(hi).firstOrNull()?.repeater?.value ?: 0) > 0

		// org-auto-repeat-maybe, w/ org-log-repeat 'time, org-log-into-drawer nil, and
		// org-todo-repeat-to-state nil: the keyword goes back to the head of its sequence (or stays,
		// for a #+TYP_TODO: one; :REPEAT_TO_STATE: wins), CLOSED goes, :LAST_REPEAT: takes the time, a
		// SCHEDULED w/o a repeater goes, each repeating active timestamp of the entry moves on, and the
		// state change is logged after the property drawer.
		private fun repeat(hi: Int, old: String?, doneWord: String) {
			val sequence = keywords.sequenceOf(old)
			val toState = section(hi, false).property(lines, FieldChange.REPEAT_TO_STATE)?.value?.takeIf { it in keywords.all }
				?: when {
					sequence == null -> null
					sequence.type -> old
					else -> sequence.head
				}
			replaceKeyword(hi, toState)
			addPlanning(hi, null, null, listOf(PlanKey.CLOSED))
			val stamp = closedStamp().format()
			setProperty(hi, false, FieldChange.LAST_REPEAT, stamp)
			val scheduled = section(hi, false).planning(lines).scheduled
			if (scheduled != null && scheduled.repeater == null) removePlanning(hi, PlanKey.SCHEDULED)
			val today = now().toLocalDate()
			val entry = section(hi, false)
			for (i in entry.start until entry.end) {
				val text = lines[i].text
				val moved = Timestamp.ACTIVE.replace(text) { m ->
					val ts = Timestamp.parse(m.value)
					if (ts == null || ts.repeater == null) m.value else nextRepeat(ts, today).format()
				}
				if (moved != text) setText(i, moved)
			}
			// Org quotes the old state even when there was none: from "".
			insertLogNote(hi, String.format(Locale.ROOT, "- State %-12s from %-12s %s", "\"$doneWord\"", "\"${old.orEmpty()}\"", stamp))
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

		// org-log-beginning and org-store-log-note: from the end of the property drawer, the note skips
		// blank lines and goes ahead of the first line w/ text, which moves down. At the end of the file
		// the note gets no newline (and a last line w/o one gets one). A last line of blanks w/o a newline
		// takes the note at its start.
		private fun insertLogNote(hi: Int, note: String) {
			var i = section(hi, false).metaEnd
			while (i < lines.size && OrgDocument.isBlank(lines[i].text) && lines[i].eol.isNotEmpty()) i++
			when {
				i >= lines.size -> {
					if (i > 0) ensureEol(i - 1)
					lines.add(Line(note, ""))
				}
				OrgDocument.isBlank(lines[i].text) -> setText(i, note + lines[i].text)
				else -> lines.add(i, Line(note, eol))
			}
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

		// Org's tag alignment w/ org-tags-column -77: the tags end at column 77, w/ at least one space
		// before them. Spaces only; the line is left alone when the tags already start at that column.
		private fun alignTags(hi: Int) {
			val text = lines[hi].text
			val g = HeadlineRegex.TAG_LINE.matchEntire(text)?.groups?.get(1) ?: return
			val tagsStart = g.range.first
			val blank = OrgDocument.blankStart(text, tagsStart)
			val blankCol = Width.columns(text, blank)
			val want = maxOf(TAGS_COLUMN - Width.columns(g.value, g.value.length), blankCol + 1)
			if (want == Width.columns(text, tagsStart)) return
			setText(hi, text.substring(0, blank) + " ".repeat(want - blankCol) + text.substring(tagsStart))
		}

		// SCHEDULED or DEADLINE: org-schedule and org-deadline write through org-add-planning-info,
		// which also drops CLOSED; a removal goes through org-remove-timestamp-with-keyword.
		private fun setPlanning(hi: Int, key: PlanKey, ts: Timestamp?) {
			val current = section(hi, false).planning(lines)
			val old = if (key == PlanKey.DEADLINE) current.deadline else current.scheduled
			if (old == ts) return
			if (ts == null) removePlanning(hi, key) else addPlanning(hi, key, ts.format(), listOf(PlanKey.CLOSED))
		}

		// org-add-planning-info: remove [what] and the [remove] keywords from the planning line (each up
		// to the next keyword), then put "WHAT stamp" first on the line. W/o [what], a line left empty
		// goes away.
		private fun addPlanning(hi: Int, what: PlanKey?, stamp: String?, remove: List<PlanKey>) {
			val pi = section(hi, false).planningLine
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

		// org-remove-timestamp-with-keyword, on the planning line only (Org searches the whole entry):
		// remove each "KEY <...>" and the blanks after it, then one space before it, or the whole line
		// when naught is left.
		private fun removePlanning(hi: Int, key: PlanKey) {
			val pi = section(hi, false).planningLine ?: return
			val re = key.removal
			var s = lines[pi].text
			for (m in re.findAll(s).toList().asReversed()) {
				val at = m.range.first
				s = s.removeRange(m.range)
				// Org tests the text before the stamp w/ "\\S-", any non-whitespace character, so
				// isWhitespace (not isBlankChar) is the match here.
				if (at > 0 && s.substring(0, at).any { !it.isWhitespace() } && s[at - 1] == ' ') {
					s = s.removeRange(at - 1, at)
				} else if (OrgDocument.isBlank(s)) {
					lines.removeAt(pi)
					return
				}
			}
			setText(pi, s)
		}

		// org-entry-put: replace the property line, or add one before :END:, creating the drawer after
		// the planning line when the heading has none. org-entry-delete for a null value: every line of
		// the key (and of KEY+) goes, and a drawer left empty goes too.
		private fun setProperty(hi: Int, timestamps: Boolean, key: String, value: String?) {
			var s = section(hi, timestamps)
			val cur = s.property(lines, key)
			val clean = value?.let { Escape.oneLine(it) }
			if (cur?.value == clean) return
			if (clean == null) {
				val doomed = s.properties(lines).filter { it.key.equals(key, true) || it.key.equals("$key+", true) }
				for (p in doomed.asReversed()) lines.removeAt(p.index)
				s = section(hi, timestamps)
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
				s = section(hi, timestamps)
			}
			lines.add(s.drawerEnd!!, Line(formatted, eol))
		}

		// org-insert-property-drawer: ":PROPERTIES:" and ":END:" after the heading or planning line;
		// at the end of a file w/o a final newline, ":END:" gets one.
		private fun insertDrawer(anchor: Int) {
			insertAfter(anchor, listOf(":PROPERTIES:", ":END:"))
			ensureEol(anchor + 2)
		}

		// The timestamp lines of an event: only the lines whose values change are rewritten. The lines
		// that open and close the list unchanged stay byte for byte, and the rest is replaced.
		private fun setTimestamps(hi: Int, stamps: List<Timestamp>) {
			val s = section(hi, true)
			val current = s.timestamps(lines)
			if (current == stamps) return
			var head = 0
			while (head < current.size && head < stamps.size && current[head] == stamps[head]) head++
			var tail = 0
			while (tail < current.size - head && tail < stamps.size - head &&
				current[current.size - 1 - tail] == stamps[stamps.size - 1 - tail]
			) tail++
			replace(s.timestampStart + head, s.timestampEnd - tail, stamps.subList(head, stamps.size - tail).map { it.format() })
		}

		// The body: the lines after the meta lines, the drawers, and (in calendar.org) the timestamp
		// lines, up to the last non-blank line of the section. Trailing blank lines stay.
		private fun setBody(hi: Int, timestamps: Boolean, text: String) {
			val s = section(hi, timestamps)
			val new = Escape.bodyLines(text, timestamps)
			if (s.body(lines) == new.joinToString("\n") { Escape.unBodyLine(it, timestamps) }) return
			replace(s.bodyStart, s.bodyEnd, new)
		}
	}

	companion object {
		// org-tags-column, negated: the column at which the tags end.
		const val TAGS_COLUMN = 77
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
