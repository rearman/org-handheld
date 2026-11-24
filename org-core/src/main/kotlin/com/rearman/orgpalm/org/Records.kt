package com.rearman.orgpalm.org

import java.time.DayOfWeek
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// The three files of DESIGN.md section 3, and the heading level that holds their records.
enum class FileKind(val fileName: String, val recordLevel: Int) {
	CALENDAR("calendar.org", 1),
	TODO("todo.org", 2),
	MEMOS("memos.org", 2);

	val hasCategories: Boolean get() = recordLevel == 2
}

data class Category(val id: String?, val name: String)

data class Event(
	val id: String?,
	val title: String,
	val timestamps: List<Timestamp>,
	val repeater: Repeater?,
	val warnMinutes: Int?,
	val tags: List<String>,
	val body: String,
	val weekdays: Set<DayOfWeek>,
	val readOnly: Boolean,
) {
	val isPrivate: Boolean get() = PRIVATE in tags
}

data class Task(
	val id: String?,
	val categoryId: String?,
	val category: String,
	val state: String?,
	val priority: String?,
	val title: String,
	val scheduled: Timestamp?,
	val deadline: Timestamp?,
	val closed: Timestamp?,
	val tags: List<String>,
	val body: String,
	val readOnly: Boolean,
) {
	val isPrivate: Boolean get() = PRIVATE in tags
}

data class Note(
	val id: String?,
	val categoryId: String?,
	val category: String,
	val title: String,
	val body: String,
)

const val PRIVATE = "private"

// One sequence of TODO keywords, as one #+TODO: line gives it: [names] in order, the [done] ones among
// them, and [type] for a #+TYP_TODO: line.
data class TodoSequence(val names: List<String>, val done: List<String>, val type: Boolean = false) {
	val head: String get() = names.first()
}

// The TODO keywords of a file, as org-set-regexps-and-options reads them: the #+TYP_TODO: lines first,
// then the #+TODO: lines, then the #+SEQ_TODO: lines, each in file order; TODO | DONE w/o any.
data class TodoKeywords(val sequences: List<TodoSequence>) {
	val all: List<String> = sequences.flatMap { it.names }
	val done: List<String> = sequences.flatMap { it.done }
	val open: List<String> = all.filter { it !in done }

	fun isDone(keyword: String?): Boolean = keyword != null && keyword in done

	// The first sequence that holds [keyword], as Org's org-todo-kwd-alist finds it.
	fun sequenceOf(keyword: String?): TodoSequence? = keyword?.let { k -> sequences.firstOrNull { k in it.names } }

	companion object {
		val DEFAULT = TodoKeywords(listOf(TodoSequence(listOf("TODO", "DONE"), listOf("DONE"))))
		private val LINE = Regex("""[ \t]*#\+(TODO|SEQ_TODO|TYP_TODO):(.*)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

		// A keyword w/o its fast-access key and logging marks: WAIT(w@/!) gives WAIT.
		private val NAME = Regex("""(.*?)(?:\(([^!@/])?.*?\))?""", RegexOption.DOT_MATCHES_ALL)

		fun of(doc: OrgDocument): TodoKeywords {
			val byKind = mapOf("TYP_TODO" to ArrayList<List<String>>(), "TODO" to ArrayList<List<String>>(), "SEQ_TODO" to ArrayList<List<String>>())
			for (line in doc.lines) {
				val m = LINE.matchEntire(line.text) ?: continue
				val words = OrgDocument.words(m.groupValues[2])
				if (words.isNotEmpty()) byKind.getValue(m.groupValues[1].uppercase(Locale.ROOT)).add(words)
			}
			val sequences = byKind.getValue("TYP_TODO").map { sequence(it, true) } +
				(byKind.getValue("TODO") + byKind.getValue("SEQ_TODO")).map { sequence(it, false) }
			val kept = sequences.filter { it.names.isNotEmpty() }
			return if (kept.isEmpty()) DEFAULT else TodoKeywords(kept)
		}

		// One line's sequence: w/o a "|", its last keyword is the done one.
		private fun sequence(words: List<String>, type: Boolean): TodoSequence {
			val bar = words.indexOf("|")
			val name = { w: String -> NAME.matchEntire(w)?.groupValues?.get(1) ?: w }
			val names = words.filter { it != "|" }.map(name)
			val done = if (bar >= 0) words.drop(bar + 1).filter { it != "|" }.map(name) else names.takeLast(1)
			return TodoSequence(names, done, type)
		}
	}
}

// The priority range of a file, as Org's values: a number 0 to 64, or the code of a letter A to Z. Its
// first #+PRIORITIES: line (highest, lowest, default) governs, which Org reads alone
// (org-collect-keywords takes the first value), else A, C, B.
data class Priorities(val highest: Int, val lowest: Int, val default: Int) {
	// The values from highest to lowest, as the cookies write them, for the form's pop-up list. Org's
	// org-priority-to-string refuses a value outside 0 to 64 and A to Z, so the list skips one.
	val values: List<String> get() = (highest..lowest).mapNotNull { text(it) }

	companion object {
		val DEFAULT = Priorities('A'.code, 'C'.code, 'B'.code)
		private val LINE = Regex("""[ \t]*#\+PRIORITIES:(.*)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
		private val DIGITS = Regex("[0-9]+")

		// A cookie's text for a value, or null for none Org allows.
		fun text(value: Int): String? = when (value) {
			in 0..64 -> value.toString()
			in 'A'.code..'Z'.code -> value.toChar().toString()
			else -> null
		}

		// org-priority-to-value, per word: the first run of digits as a number, else the code of the
		// first character. A run too long for an Int gives null, and the file keeps the defaults.
		private fun value(word: String): Int? {
			val digits = DIGITS.find(word) ?: return word[0].code
			return digits.value.toIntOrNull()
		}

		fun of(doc: OrgDocument): Priorities {
			val line = doc.lines.firstNotNullOfOrNull { LINE.matchEntire(it.text) } ?: return DEFAULT
			val words = OrgDocument.words(line.groupValues[1])
			if (words.size < 3) return DEFAULT
			val h = value(words[0]) ?: return DEFAULT
			val l = value(words[1]) ?: return DEFAULT
			val d = value(words[2]) ?: return DEFAULT
			return Priorities(h, l, d)
		}
	}
}

// A heading line split as org-complex-heading-regexp splits it. [title] has its escapes undone.
data class Headline(
	val level: Int,
	val keyword: String?,
	val priority: String?,
	val title: String,
	val tags: List<String>,
) {
	companion object {
		fun parse(text: String, keywords: List<String>): Headline {
			val m = HeadlineRegex.complex(keywords).matchEntire(text) ?: error("Not a heading line: $text")
			val cookie = m.groups[3]?.value
			return Headline(
				m.groupValues[1].length,
				m.groups[2]?.value,
				cookie?.let { HeadlineRegex.cookieValue(it) },
				Escape.unTitle(m.groups[4]?.value ?: "", keywords),
				HeadlineRegex.splitTags(m.groups[5]?.value),
			)
		}
	}
}

// The regexps of a heading line, after Org's.
internal object HeadlineRegex {
	// Org's tag characters, [:alnum:]_@#%. [:alnum:] in Emacs is a Unicode letter or digit; \p{L}\p{Nd}
	// is close and misses only the rarer digit categories.
	const val TAG_CHARS = """\p{L}\p{Nd}_@#%"""
	val TAG = Regex("[$TAG_CHARS]+")

	// A priority cookie, as org-priority-value-regexp allows its value.
	val COOKIE = Regex("""\[#(?:[A-Z]|[0-9]|[1-5][0-9]|6[0-4])\]""")

	// org-tag-line-re: group 1 is the tag group at the end of the line.
	val TAG_LINE = Regex("""\*+ (?:.*[ \t])?(:[$TAG_CHARS:]+:)[ \t]*""", RegexOption.DOT_MATCHES_ALL)

	private val cache = ConcurrentHashMap<List<String>, Regex>()

	// The keywords as one alternation, longest first; one that matches naught when there are none.
	fun keywordAlternation(keywords: List<String>): String =
		if (keywords.isEmpty()) "(?!)" else keywords.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

	// org-complex-heading-regexp: 1 stars, 2 keyword, 3 priority cookie, 4 title, 5 tags.
	fun complex(keywords: List<String>): Regex = cache.getOrPut(keywords) {
		Regex(
			"""(\*+)(?: +(${keywordAlternation(keywords)}))?(?: +(${COOKIE.pattern}))?(?: +(.*?))??(?:[ \t]+(:[$TAG_CHARS:]+:))?[ \t]*""",
			RegexOption.DOT_MATCHES_ALL,
		)
	}

	// The value of a cookie such as "[#A]".
	fun cookieValue(cookie: String): String = cookie.substring(2, cookie.length - 1)

	// The tags of a tag group such as ":work:plc:".
	fun splitTags(group: String?): List<String> = group?.split(':')?.filter { it.isNotEmpty() } ?: emptyList()
}

// The planning keywords, each w/ the regexp that finds it and its timestamp (group 1, w/o brackets),
// after org-closed-time-regexp, org-deadline-time-regexp, and org-scheduled-time-regexp.
internal enum class PlanKey(val word: String, val regex: Regex, private val open: String, private val close: String) {
	CLOSED("CLOSED:", Regex("""\bCLOSED: *\[([^\]]+)\]"""), "[", "]"),
	DEADLINE("DEADLINE:", Regex("""\bDEADLINE: *<([^>]+)>"""), "<", ">"),
	SCHEDULED("SCHEDULED:", Regex("""\bSCHEDULED: *<([^>]+)>"""), "<", ">");

	fun stamp(m: MatchResult): Timestamp? = Timestamp.parse(open + m.groupValues[1] + close)

	// The pattern of org-remove-timestamp-with-keyword, which takes an active stamp w/ the blanks after
	// it and wants at least one space after the keyword. [regex] (org-deadline-time-regexp and its kin)
	// allows none and leaves the blanks, so Org keeps two patterns and so does this.
	val removal: Regex = Regex("\\b" + Regex.escape(word) + " +<[^>\\n]+>[ \\t]*")

	companion object {
		// The three keywords w/o their colons, as a regexp alternation.
		val ALTERNATION: String = entries.joinToString("|") { it.name }

		// org-keyword-time-not-clock-regexp: any planning keyword w/ its timestamp.
		val ANY = Regex("""\b(?:$ALTERNATION): *[\[<][^\]>]+[\]>]""")
	}
}

// The planning line of a heading. A keyword whose timestamp does not parse sets [unreadable].
data class Planning(
	val closed: Timestamp?,
	val deadline: Timestamp?,
	val scheduled: Timestamp?,
	val unreadable: Boolean,
) {
	companion object {
		val NONE = Planning(null, null, null, false)

		fun parse(text: String): Planning {
			var unreadable = false
			val stamps = PlanKey.entries.associateWith { key ->
				key.regex.find(text)?.let { m -> key.stamp(m).also { if (it == null) unreadable = true } }
			}
			return Planning(stamps[PlanKey.CLOSED], stamps[PlanKey.DEADLINE], stamps[PlanKey.SCHEDULED], unreadable)
		}
	}
}

// A parsed app file: its records, read per DESIGN.md section 3.
class OrgFile(val kind: FileKind, val document: OrgDocument) {
	val keywords: TodoKeywords = TodoKeywords.of(document)
	val priorities: Priorities = Priorities.of(document)

	fun headline(h: Heading): Headline = Headline.parse(document.lines[h.start].text, keywords.all)

	// The section of a heading; the timestamp lines count only for the events of calendar.org.
	fun section(h: Heading): Section =
		Section.of(document.lines, h.start, kind == FileKind.CALENDAR && isRecord(h))

	fun id(h: Heading): String? = section(h).property(document.lines, FieldChange.ID)?.value?.takeIf { it.isNotEmpty() }

	// The heading whose :ID: is [id], or null.
	fun find(id: String): Heading? = document.headings.firstOrNull { id(it) == id }

	// The category heading named [name], or null. The name is compared as a heading would hold it:
	// w/ its line breaks as spaces and its ends trimmed.
	fun category(name: String): Heading? {
		if (!kind.hasCategories) return null
		val wanted = categoryName(name)
		return topLevel.firstOrNull { headline(it).title == wanted }
	}

	// [name] as a category heading reads it back once written.
	fun categoryName(name: String): String = Escape.oneLine(name)

	// A record the app shows w/ a lock (DESIGN.md section 4 item 4): an event whose timestamps it
	// cannot read, or a task whose planning line it cannot.
	fun isReadOnly(h: Heading): Boolean = when {
		!isRecord(h) -> false
		kind == FileKind.CALENDAR -> readEvent(h).readOnly
		kind == FileKind.TODO -> planning(h).unreadable
		else -> false
	}

	// The level-1 headings: the events of calendar.org, the categories of the other two. A heading of
	// a deeper level ahead of the first level-1 one is a root too, but neither record nor category.
	private val topLevel: List<Heading> get() = document.roots.filter { it.level == 1 }

	// A record: an event at level 1 of calendar.org, or a task or note at level 2 under a category.
	fun isRecord(h: Heading): Boolean =
		h.level == kind.recordLevel && (!kind.hasCategories || h.parent?.level == 1)

	// A category heading: level 1 of todo.org or memos.org.
	fun isCategory(h: Heading): Boolean = kind.hasCategories && h.level == 1

	val categories: List<Category> by lazy {
		if (!kind.hasCategories) emptyList() else topLevel.map { Category(id(it), headline(it).title) }
	}

	val events: List<Event> by lazy {
		if (kind != FileKind.CALENDAR) emptyList() else topLevel.map { readEvent(it) }
	}

	val tasks: List<Task> by lazy {
		if (kind != FileKind.TODO) emptyList() else topLevel.flatMap { cat -> cat.children.filter { isRecord(it) }.map { readTask(it, cat) } }
	}

	val notes: List<Note> by lazy {
		if (kind != FileKind.MEMOS) emptyList() else topLevel.flatMap { cat -> cat.children.filter { isRecord(it) }.map { readNote(it, cat) } }
	}

	private fun planning(h: Heading): Planning =
		section(h).planning(document.lines)

	private fun readEvent(h: Heading): Event {
		val s = section(h)
		val head = headline(h)
		val read = s.timestampLines(document.lines)
		val stamps = read.mapNotNull { it.stamp }
		// A line of a shape the app does not model (inactive, a habit, a diary sexp, a range, and the
		// like), an hourly repeat, or a timestamp in the title.
		val unreadable = read.any { it.shape == LineShape.UNREADABLE }
		var readOnly = unreadable || stamps.any { it.repeater?.unit == RepeatUnit.HOUR } || Timestamp.containsActive(head.title)
		var weekdays: Set<DayOfWeek> = emptySet()
		if (!readOnly && stamps.isNotEmpty()) {
			if (isWeekdaySet(stamps)) weekdays = stamps.map { it.date.dayOfWeek }.toSortedSet() else readOnly = true
		}
		return Event(
			id = id(h),
			title = head.title,
			timestamps = stamps,
			repeater = stamps.firstOrNull()?.repeater,
			warnMinutes = s.property(document.lines, FieldChange.WARN_TIME)?.value?.trim { OrgDocument.isBlankChar(it) }?.toIntOrNull(),
			tags = head.tags,
			body = s.body(document.lines),
			weekdays = weekdays,
			readOnly = readOnly,
		)
	}

	private fun readTask(h: Heading, category: Heading): Task {
		val head = headline(h)
		val planning = planning(h)
		return Task(
			id = id(h),
			categoryId = id(category),
			category = headline(category).title,
			state = head.keyword,
			priority = head.priority,
			title = head.title,
			scheduled = planning.scheduled,
			deadline = planning.deadline,
			closed = planning.closed,
			tags = head.tags,
			body = section(h).body(document.lines),
			readOnly = planning.unreadable,
		)
	}

	private fun readNote(h: Heading, category: Heading): Note =
		Note(id(h), id(category), headline(category).title, headline(h).title, section(h).body(document.lines))

	companion object {
		fun parse(kind: FileKind, text: String): OrgFile = OrgFile(kind, OrgDocument.parse(text))

		// Several timestamps make one event when they repeat weekly (+Nw, .+Nw, or ++Nw), share the
		// time, the repeater, and the warning, and their dates rise within the six days after the first
		// (DESIGN.md section 3). One timestamp is always a set of its own day.
		fun isWeekdaySet(stamps: List<Timestamp>): Boolean {
			val first = stamps.first()
			if (stamps.size > 1 && first.repeater?.unit != RepeatUnit.WEEK) return false
			val last = first.date.plusDays(6)
			for (i in 1 until stamps.size) {
				val t = stamps[i]
				if (t.start != first.start || t.end != first.end || t.repeater != first.repeater) return false
				if (t.warning != first.warning || t.warningFirst != first.warningFirst || t.active != first.active) return false
				if (!t.date.isAfter(stamps[i - 1].date) || t.date.isAfter(last)) return false
			}
			return true
		}

		// The timestamps of a weekly event on [days]: [first] on its own date, and a copy w/ the same
		// time and repeater on each other day of [days] within the six days after it.
		fun weekdayTimestamps(first: Timestamp, days: Set<DayOfWeek>): List<Timestamp> =
			(0L..6L).map { first.date.plusDays(it) }
				.filter { it == first.date || it.dayOfWeek in days }
				.map { first.copy(date = it) }
	}
}
